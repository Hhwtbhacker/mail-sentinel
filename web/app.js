const $ = s => document.querySelector(s);
let me = null, registering = false, draftRequestId = null, draftMailboxId = null;
const THEME_KEY = "mailManager.theme";
function applyTheme(value) {
  const theme = value === "dark" ? "dark" : "light";
  document.documentElement.dataset.theme = theme;
  $("#themeSelect").value = theme;
  try { localStorage.setItem(THEME_KEY, theme); } catch {}
}
$("#themeSelect").value = document.documentElement.dataset.theme === "dark" ? "dark" : "light";
$("#themeSelect").onchange = event => applyTheme(event.target.value);
document.querySelectorAll(".app-nav a").forEach(link => {
  link.addEventListener("click", () => {
    document.querySelectorAll(".app-nav a").forEach(other => other.classList.remove("nav-current"));
    link.classList.add("nav-current");
  });
});
async function api(path, method = "GET", body) {
  const r = await fetch(path, {
    method, credentials: "same-origin",
    headers: body ? {"Content-Type": "application/json"} : {},
    body: body ? JSON.stringify(body) : undefined
  });
  let data = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(data.detail || "请求失败");
  return data;
}
function toast(message) {
  const el = $("#toast"); el.textContent = message; el.classList.remove("hidden");
  setTimeout(() => el.classList.add("hidden"), 4000);
}
function text(tag, content, className) {
  const node = document.createElement(tag); node.textContent = content;
  if (className) node.className = className; return node;
}
function date(ts) {
  return ts ? new Date(ts * 1000).toLocaleString("zh-CN", {month:"numeric",day:"numeric",hour:"2-digit",minute:"2-digit"}) : "尚未检查";
}
async function boot() {
  try { me = await api("/api/me"); } catch { me = null; }
  $("#authView").classList.toggle("hidden", !!me);
  $("#dashboard").classList.toggle("hidden", !me);
  $("#logoutBtn").classList.toggle("hidden", !me);
  $("#accountName").textContent = me?.email || "";
  if (me) {
    $("#keyStatus").textContent = me.has_api_key ? "已配置" : "未配置";
    $("#summaryToggle").checked = me.body_summary_enabled;
    $("#googleBtn").disabled = !me.oauth_google;
    $("#microsoftBtn").disabled = !me.oauth_microsoft;
    await refresh();
    await loadChat();
  }
}
async function loadChat() {
  const history = await api("/api/chat");
  const box = $("#chatMessages"); box.replaceChildren();
  if (!history.length) box.append(text("p", "还没有对话。试着问问最近有哪些重要邮件。", "chat-empty"));
  history.forEach(item => {
    const bubble = document.createElement("div"); bubble.className = "chat-bubble " + item.role;
    bubble.append(text("span", item.role === "user" ? "你" : "邮箱总管 Agent", "chat-author"), text("p", item.content));
    if (item.role === "assistant" && item.total_tokens != null)
      bubble.append(text("small", item.model + " · " + item.total_tokens + " token"));
    box.append(bubble);
  });
  box.scrollTop = box.scrollHeight;
}
async function refresh() {
  const [boxes, items, usage] = await Promise.all([api("/api/mailboxes"), api("/api/reminders"), api("/api/usage")]);
  const draftMailbox = $("#draftMailbox");
  const selected = draftMailbox.value; draftMailbox.replaceChildren();
  boxes.filter(box => box.provider === "qq").forEach(box => {
    const option = document.createElement("option"); option.value = String(box.id); option.textContent = box.address;
    draftMailbox.append(option);
  });
  if (selected) draftMailbox.value = selected;
  $("#totalTokens").textContent = Number(usage.total_tokens).toLocaleString("zh-CN");
  $("#todayTokens").textContent = Number(usage.today_tokens).toLocaleString("zh-CN");
  const usageList = $("#usageList"); usageList.replaceChildren();
  if (!usage.items.length) usageList.append(text("p", "暂无可统计的 Agent 调用。", "empty-small"));
  usage.items.forEach(entry => {
    const row = document.createElement("div"); row.className = "usage-item";
    const heading = document.createElement("div"); heading.className = "usage-item-heading";
    heading.append(text("strong", entry.subject), text("b", entry.total_tokens == null ? "用量未返回" : Number(entry.total_tokens).toLocaleString("zh-CN") + " token"));
    row.append(heading, text("small", entry.address + " · " + date(entry.created_at) + (entry.total_tokens == null ? "" : " · 输入 " + entry.input_tokens + " / 输出 " + entry.output_tokens)));
    usageList.append(row);
  });
  $("#mailboxCount").textContent = boxes.length;
  const unreadCount = items.filter(x => !x.read_at).length;
  $("#unreadCount").textContent = unreadCount;
  $("#markAllRead").disabled = unreadCount === 0;
  $("#reminderBadge").textContent = items.length + " 条";
  const checks = boxes.map(x => x.last_check).filter(Boolean);
  $("#lastCheck").textContent = checks.length ? Math.max(0, Math.floor(Date.now() / 1000 - Math.max(...checks))) + " 秒前" : "—";
  const boxList = $("#mailboxList"); boxList.replaceChildren();
  if (!boxes.length) boxList.append(text("p", "还没有连接邮箱。添加后会自动开始检查。", "empty-small"));
  boxes.forEach(box => {
    const row = document.createElement("div"); row.className = "mailbox";
    const icon = text("span", {qq:"Q",google:"G",microsoft:"O"}[box.provider], "mailbox-icon " + box.provider);
    const details = document.createElement("div"); details.className = "mailbox-details";
    details.append(text("strong", box.address), text("small", box.last_error ? "检查失败：" + box.last_error : "约每 5 秒检查 · " + date(box.last_check)));
    const actions = document.createElement("div"); actions.className = "mailbox-actions";
    const check = text("button", "检查", "mini-button"); check.onclick = async () => {
      check.disabled = true; toast("正在检查邮箱…");
      try { await api("/api/mailboxes/" + box.id + "/check", "POST"); await refresh(); toast("检查完成"); }
      catch(e) { toast(e.message); } finally { check.disabled = false; }
    };
    const remove = text("button", "移除", "mini-button danger"); remove.onclick = async () => {
      if (!confirm("移除这个邮箱及其提醒？")) return;
      try { await api("/api/mailboxes/" + box.id, "DELETE"); await refresh(); } catch(e) { toast(e.message); }
    };
    actions.append(check, remove); row.append(icon, details, actions); boxList.append(row);
  });
  const list = $("#reminderList"); list.replaceChildren();
  if (!items.length) {
    const empty = document.createElement("div"); empty.className = "empty-state";
    empty.append(text("div", "✉", "empty-icon"), text("h3", "这里暂时很安静"), text("p", "连接邮箱后，值得留意的新邮件会出现在这里。"));
    list.append(empty);
  }
  items.forEach(item => {
    const card = document.createElement("article"); card.className = "reminder" + (item.read_at ? " read" : "");
    const meta = document.createElement("div"); meta.className = "reminder-meta";
    meta.append(text("span", item.address, "address"), text("span", date(item.created_at)));
    const title = text("h3", item.subject);
    const sender = text("p", item.sender, "sender");
    const summary = document.createElement("div"); summary.className = "ai-summary";
    summary.append(text("span", "✦ AGENT 总结", "summary-label"),
      text("p", item.summary || "这封邮件暂无正文总结。开启智能总结后，新邮件会显示简要内容。", item.summary ? "" : "summary-empty"));
    const reason = text("p", "提醒原因 · " + item.reason, "reason");
    const token = item.total_tokens == null ? null : text("p", "本封 Agent 用量 · " + Number(item.total_tokens).toLocaleString("zh-CN") + " token（输入 " + item.input_tokens + " / 输出 " + item.output_tokens + "）", "token-line");
    const bottom = document.createElement("div"); bottom.className = "reminder-bottom";
    const actions = document.createElement("div"); actions.className = "reminder-actions";
    const open = document.createElement("a"); open.className = "open-mail";
    open.href = item.mail_url; open.target = "_blank"; open.rel = "noopener noreferrer";
    open.textContent = "打开邮箱查找 ↗"; open.title = "IMAP 无法提供单封邮件的网页直达地址，请按主题查找";
    const copy = text("button", "复制主题", "link-button");
    copy.onclick = async () => { try { await navigator.clipboard.writeText(item.subject); toast("已复制邮件主题"); } catch { toast("复制失败，请手动选择主题"); } };
    actions.append(open, copy);
    if (!item.read_at) {
      const done = text("button", "标为已阅", "link-button");
      done.onclick = async () => { try { await api("/api/reminders/" + item.id + "/read", "POST"); await refresh(); } catch(e) {toast(e.message);} };
      actions.append(done);
    }
    bottom.append(text("span", item.priority === "high" ? "优先查看" : "阅览提醒", "priority " + item.priority), actions);
    card.append(meta, title, sender, summary, reason);
    if (token) card.append(token);
    card.append(bottom); list.append(card);
  });
}
$("#authSwitch").onclick = () => {
  registering = !registering;
  $("#authTitle").textContent = registering ? "创建你的账号" : "欢迎回来";
  $("#authHint").textContent = registering ? "一个地方管理所有邮箱提醒" : "登录后查看你的收件提醒";
  $("#authSubmit").textContent = registering ? "创建账号" : "登录";
  $("#authSwitchLead").textContent = registering ? "已有账号？" : "还没有账号？";
  $("#authSwitch").textContent = registering ? "返回登录" : "创建账号";
  $("#authError").textContent = "";
};
$("#authForm").onsubmit = async event => {
  event.preventDefault(); $("#authError").textContent = "";
  const f = new FormData(event.target);
  try {
    await api(registering ? "/api/register" : "/api/login", "POST", {email:f.get("email"), password:f.get("password")});
    event.target.reset(); await boot();
  } catch(e) { $("#authError").textContent = e.message; }
};
$("#logoutBtn").onclick = async () => { await api("/api/logout", "POST"); me = null; await boot(); };
$("#refreshBtn").onclick = () => refresh().catch(e => toast(e.message));
$("#markAllRead").onclick = async () => {
  const button = $("#markAllRead"); button.disabled = true;
  try {
    const result = await api("/api/reminders/read-all", "POST");
    await refresh();
    toast(result.updated ? `已将 ${result.updated} 条提醒标为已阅` : "没有未阅提醒");
  } catch (e) { toast(e.message); button.disabled = false; }
};
$("#chatForm").onsubmit = async event => {
  event.preventDefault();
  const message = $("#chatInput").value.trim();
  if (!message) return;
  const send = $("#chatSend"); send.disabled = true; send.textContent = "Agent 正在回复…";
  try {
    await api("/api/chat", "POST", {message, model:$("#chatModel").value, effort:$("#chatEffort").value});
    $("#chatInput").value = "";
    await Promise.all([loadChat(), refresh()]);
  } catch(e) { toast(e.message); }
  finally { send.disabled = false; send.textContent = "发送给 Agent"; }
};
$("#draftForm").onsubmit = async event => {
  event.preventDefault();
  const button = $("#draftGenerate"); button.disabled = true; button.textContent = "正在生成草稿…";
  try {
    draftMailboxId = Number($("#draftMailbox").value);
    const draft = await api("/api/mail/draft", "POST", {
      mailbox_id:draftMailboxId, recipient:$("#draftRecipient").value.trim(),
      prompt:$("#draftPrompt").value.trim(), model:$("#chatModel").value, effort:$("#chatEffort").value
    });
    draftRequestId = crypto.randomUUID();
    $("#draftSender").textContent = "发件人：" + draft.sender + (draft.usage ? " · 本次生成 " + draft.usage.total_tokens + " token" : "");
    $("#draftTo").value = draft.recipient; $("#draftSubject").value = draft.subject; $("#draftBody").value = draft.body;
    $("#draftPreview").classList.remove("hidden");
    await refresh();
  } catch(e) { toast(e.message); }
  finally { button.disabled = false; button.textContent = "生成草稿"; }
};
$("#draftSend").onclick = async () => {
  const recipient = $("#draftTo").value.trim(), subject = $("#draftSubject").value.trim(), body = $("#draftBody").value.trim();
  if (!recipient || !subject || !body) { toast("请填写收件人、主题和正文"); return; }
  if (!confirm("确认从 " + $("#draftSender").textContent.split(" · ")[0].replace("发件人：", "") + " 发送给 " + recipient + "？")) return;
  const button = $("#draftSend"); button.disabled = true; button.textContent = "正在发送…";
  try {
    await api("/api/mail/send", "POST", {mailbox_id:draftMailboxId, recipient, subject, body, request_id:draftRequestId});
    $("#draftPreview").classList.add("hidden"); draftRequestId = null;
    toast("邮件已发送");
  } catch(e) { toast(e.message + "。若需重试，请重新生成草稿。"); }
  finally { button.disabled = false; button.textContent = "确认发送邮件"; }
};
$("#mailboxForm").onsubmit = async event => {
  event.preventDefault(); const f = new FormData(event.target);
  try {
    await api("/api/mailboxes", "POST", {provider:"qq", address:f.get("address"), secret:f.get("secret"), interval_minutes:5});
    event.target.reset(); event.target.closest("details").open = false; await refresh(); toast("已添加邮箱");
  } catch(e) { toast(e.message); }
};
$("#keyForm").onsubmit = async event => {
  event.preventDefault();
  try { await api("/api/settings/api-key", "PUT", {api_key:$("#apiKey").value}); $("#apiKey").value = ""; me.has_api_key = true; $("#keyStatus").textContent = "已配置"; toast("密钥已保存"); }
  catch(e) { toast(e.message); }
};
$("#summaryToggle").onchange = async event => {
  const enabled = event.target.checked;
  try {
    await api("/api/settings/body-summary", "PUT", {enabled});
    me.body_summary_enabled = enabled;
    toast(enabled ? "已开启：新邮件将生成智能总结" : "已关闭邮件正文总结");
  } catch(e) { event.target.checked = !enabled; toast(e.message); }
};
async function oauth(provider) {
  const address = prompt(provider === "google" ? "请输入你的 Gmail 地址" : "请输入你的 Outlook 邮箱地址");
  if (!address) return;
  try { const result = await api("/api/oauth/" + provider + "/start", "POST", {address}); location.href = result.url; }
  catch(e) { toast(e.message); }
}
$("#googleBtn").onclick = () => oauth("google");
$("#microsoftBtn").onclick = () => oauth("microsoft");
$("#pushBtn").onclick = async () => {
  try {
    if (!me.push_public_key) throw Error("服务器尚未配置推送密钥");
    if (!("serviceWorker" in navigator) || !("PushManager" in window)) throw Error("此浏览器不支持网页推送");
    const registration = await navigator.serviceWorker.register("/assets/sw.js");
    const permission = await Notification.requestPermission();
    if (permission !== "granted") throw Error("未获得通知权限");
    const raw = atob((me.push_public_key + "=".repeat((4 - me.push_public_key.length % 4) % 4)).replace(/-/g, "+").replace(/_/g, "/"));
    const key = Uint8Array.from(raw, c => c.charCodeAt(0));
    const subscription = await registration.pushManager.subscribe({userVisibleOnly:true, applicationServerKey:key});
    await api("/api/push", "POST", subscription.toJSON());
    toast("推送提醒已开启");
  } catch(e) { toast(e.message); }
};
const oauthResult = new URLSearchParams(location.search).get("oauth");
if (oauthResult) { history.replaceState({}, "", "/"); setTimeout(() => toast(oauthResult === "success" ? "邮箱授权成功" : "邮箱授权失败，请重试"), 300); }
boot().catch(e => toast(e.message));
setInterval(() => { if (me) refresh().catch(() => {}); }, 5000);
