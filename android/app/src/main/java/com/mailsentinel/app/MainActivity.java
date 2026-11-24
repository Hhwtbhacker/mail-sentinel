package com.mailsentinel.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private int BG, WHITE, DARK, GREEN, MINT, MUTED, BORDER;
    private int FIELD, SUMMARY, CHAT_USER, HERO_BG, HERO_TEXT, HERO_MUTED, MARK_BG, BUTTON_TEXT;
    private boolean darkTheme;

    private void applyPalette() {
        darkTheme = "dark".equals(Api.prefs(this).getString("ui_theme", "light"));
        BG = darkTheme ? 0xff0e1820 : 0xffedf3ed;
        WHITE = darkTheme ? 0xff182931 : 0xffffffff;
        DARK = darkTheme ? 0xffe9f4f1 : 0xff1d3329;
        GREEN = darkTheme ? 0xffa0e4c6 : 0xff236b51;
        MINT = darkTheme ? 0xffa0e4c6 : 0xffd9efbb;
        MUTED = darkTheme ? 0xffa3bab8 : 0xff718579;
        BORDER = darkTheme ? 0xff31454d : 0xffdfe8dd;
        FIELD = darkTheme ? 0xff11212a : 0xfff9fbf7;
        SUMMARY = darkTheme ? 0xff203a3b : 0xffe9f4ed;
        CHAT_USER = darkTheme ? 0xff2a4c47 : 0xffe5f3df;
        HERO_BG = darkTheme ? 0xff172b32 : 0xff173b31;
        HERO_TEXT = darkTheme ? 0xffe9f4f1 : 0xffffffff;
        HERO_MUTED = darkTheme ? 0xffa3bab8 : 0xffc6ddc9;
        MARK_BG = darkTheme ? 0xff264f49 : 0xff2e5940;
        BUTTON_TEXT = darkTheme ? 0xff0e2523 : 0xffffffff;
        getWindow().setStatusBarColor(HERO_BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(darkTheme ? 0 : View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    private void chooseTheme(String theme) {
        Api.prefs(this).edit().putString("ui_theme", theme).apply();
        setTheme("dark".equals(theme) ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        applyPalette();
        show();
    }
    private enum Screen { HOME, CHAT, USAGE, SETTINGS, ADD_QQ, KEY }
    private Screen screen = Screen.HOME;
    private LinearLayout root;
    private LinearLayout content;
    private boolean registering;
    private final AtomicBoolean checkingNotifications = new AtomicBoolean(false);
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshHome = new Runnable() {
        @Override public void run() {
            if (!Api.prefs(MainActivity.this).getString("token", "").isEmpty()) {
                if (checkingNotifications.compareAndSet(false, true)) runAsync(() -> {
                    try { PollReceiver.checkAndNotify(MainActivity.this); }
                    catch (Exception ignored) { }
                    finally { checkingNotifications.set(false); }
                });
                if (screen == Screen.HOME) loadReminders();
            }
            refreshHandler.postDelayed(this, 5000);
        }
    };

    interface Task { void run() throws Exception; }
    void runAsync(Task task) {
        new Thread(() -> {
            try { task.run(); }
            catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private GradientDrawable shape(int color, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (stroke != 0) drawable.setStroke(dp(1), stroke);
        return drawable;
    }
    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }
    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }
    private LinearLayout.LayoutParams block(int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(bottom);
        return params;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }
    private Button button(String value, boolean primary, View.OnClickListener click) {
        Button view = new Button(this);
        view.setAllCaps(false);
        view.setText(value);
        view.setTextSize(14);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setTextColor(primary ? BUTTON_TEXT : GREEN);
        view.setBackground(new RippleDrawable(
            ColorStateList.valueOf(primary ? 0x55ffffff : (darkTheme ? 0x33a0e4c6 : 0x33256a49)),
            shape(primary ? GREEN : WHITE, 12, primary ? 0 : BORDER),
            shape(WHITE, 12, 0)));
        view.setMinHeight(dp(48));
        view.setPadding(dp(14), dp(10), dp(14), dp(10));
        view.setOnClickListener(click);
        return view;
    }
    private EditText input(String hint, int type) {
        EditText view = new EditText(this);
        view.setSingleLine(true);
        view.setTextSize(14);
        view.setTextColor(DARK);
        view.setHintTextColor(MUTED);
        view.setHint(hint);
        view.setInputType(type);
        view.setBackground(shape(FIELD, 12, BORDER));
        view.setPadding(dp(15), 0, dp(15), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(52));
        params.bottomMargin = dp(13);
        view.setLayoutParams(params);
        return view;
    }
    private LinearLayout panel() {
        LinearLayout view = column();
        view.setPadding(dp(20), dp(21), dp(20), dp(21));
        view.setBackground(shape(WHITE, 20, BORDER));
        view.setElevation(dp(2));
        return view;
    }
    private void sectionTitle(LinearLayout parent, String eyebrow, String title) {
        TextView overline = text(eyebrow, 10, GREEN, true);
        overline.setLetterSpacing(.15f);
        parent.addView(overline, block(7));
        parent.addView(text(title, 20, DARK, true), block(18));
    }
    private void note(LinearLayout parent, String value) {
        TextView view = text(value, 12, MUTED, false);
        view.setLineSpacing(dp(3), 1f);
        parent.addView(view, block(8));
    }
    private void gap(LinearLayout parent, int height) {
        View spacer = new View(this);
        parent.addView(spacer, new LinearLayout.LayoutParams(1, dp(height)));
    }
    private String appVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception ignored) { return "0.9.0"; }
    }

    @Override public void onCreate(Bundle saved) {
        if ("dark".equals(Api.prefs(this).getString("ui_theme", "light"))) setTheme(android.R.style.Theme_Material_NoActionBar);
        super.onCreate(saved);
        applyPalette();
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        if (!Api.prefs(this).getString("token", "").isEmpty()) PollReceiver.schedule(this);
        show();
        Updater.check(this, false);
    }
    @Override protected void onResume() {
        super.onResume();
        refreshHandler.removeCallbacks(refreshHome);
        refreshHandler.post(refreshHome);
        Updater.onResume(this);
    }
    @Override protected void onPause() {
        refreshHandler.removeCallbacks(refreshHome);
        super.onPause();
    }

    private void show() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        root = column();
        root.setPadding(dp(18), dp(22), dp(18), dp(42));
        scroll.addView(root);
        setContentView(scroll);
        if (Api.prefs(this).getString("token", "").isEmpty()) {
            showLogin();
        } else if (screen == Screen.ADD_QQ) {
            showAddQQ();
        } else if (screen == Screen.KEY) {
            showKey();
        } else {
            showDashboard();
        }
    }

    private void hero(String eyebrow, String headline, String subtitle) {
        LinearLayout card = column();
        card.setPadding(dp(23), dp(24), dp(23), dp(27));
        card.setBackground(shape(HERO_BG, darkTheme ? 16 : 24, 0));
        TextView mark = text("邮", 23, MINT, true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(shape(MARK_BG, 13, 0));
        card.addView(mark, new LinearLayout.LayoutParams(dp(46), dp(46)));
        gap(card, 25);
        TextView overline = text(eyebrow, 10, MINT, true);
        overline.setLetterSpacing(.16f);
        card.addView(overline, block(9));
        TextView title = text(headline, 29, HERO_TEXT, true);
        title.setLineSpacing(dp(3), 1f);
        card.addView(title, block(10));
        TextView caption = text(subtitle, 12, HERO_MUTED, false);
        caption.setLineSpacing(dp(3), 1f);
        card.addView(caption);
        root.addView(card, block(18));
    }

    private void showLogin() {
        hero("MAIL MANAGER", "重要来信，及时看见。", "多个邮箱集中管理，让值得阅读的邮件更早到达你。");
        LinearLayout form = panel();
        sectionTitle(form, "ACCOUNT", registering ? "创建你的账号" : "欢迎回来");
        note(form, "使用邮箱总管账号登录，邮箱授权码在登录后单独添加。");
        gap(form, 8);
        form.addView(text("服务器地址", 12, DARK, true), block(7));
        EditText server = input("https://1.95.120.199", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        server.setText(Api.base(this));
        form.addView(server);
        form.addView(text("账号邮箱", 12, DARK, true), block(7));
        EditText email = input("you@example.com", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        form.addView(email);
        form.addView(text("密码", 12, DARK, true), block(7));
        EditText password = input("至少 12 位", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(password);
        gap(form, 8);
        form.addView(button(registering ? "创建账号" : "登录", true,
            v -> auth(server, email, password, registering)), block(10));
        form.addView(button(registering ? "已有账号？返回登录" : "没有账号？创建账号", false, v -> {
            registering = !registering;
            show();
        }));
        root.addView(form, block(14));
        LinearLayout update = panel();
        sectionTitle(update, "APP", "应用更新");
        note(update, "当前版本 " + appVersion() + " · 更新包来自你的服务器");
        update.addView(button("检查更新", false, v -> {
            Api.prefs(this).edit().putString("base", server.getText().toString().trim().replaceAll("/+$", "")).apply();
            Updater.check(this, true);
        }));
        root.addView(update);
    }

    private void auth(EditText server, EditText email, EditText password, boolean register) {
        String url = server.getText().toString().trim().replaceAll("/+$", "");
        String address = email.getText().toString().trim();
        String secret = password.getText().toString();
        if (!url.startsWith("https://")) {
            Toast.makeText(this, "服务器必须使用 HTTPS", Toast.LENGTH_LONG).show();
            return;
        }
        Api.prefs(this).edit().putString("base", url).apply();
        runAsync(() -> {
            JSONObject payload = new JSONObject().put("email", address).put("password", secret);
            JSONObject result = Api.request(this, register ? "/api/register" : "/api/login", "POST", payload);
            Api.prefs(this).edit().putString("token", result.getString("token"))
                .putString("email", address).putLong("last_notice", System.currentTimeMillis() / 1000)
                .remove("last_notice_id").apply();
            PollReceiver.schedule(this);
            screen = Screen.HOME;
            runOnUiThread(this::show);
        });
    }

    private void showDashboard() {
        hero("YOUR MAIL DESK", screen == Screen.HOME ? "今天，有什么值得关注？" : screen == Screen.CHAT ? "和邮箱总管 Agent 聊聊。" : screen == Screen.USAGE ? "每封邮件，用量清楚。" : "管理你的邮箱总管空间",
            screen == Screen.HOME ? "先看总结，再决定是否打开原邮件。" : screen == Screen.CHAT ? "查询提醒、邮箱状态和 Token 用量。" : screen == Screen.USAGE ? "查看 DeepSeek 的累计、今日与单封 token 用量。" : "邮箱、智能总结和应用更新都在这里。");
        LinearLayout tabs = row();
        Button home = button("提醒", screen == Screen.HOME, v -> { screen = Screen.HOME; show(); });
        Button chat = button("聊天", screen == Screen.CHAT, v -> { screen = Screen.CHAT; show(); });
        Button usage = button("用量", screen == Screen.USAGE, v -> { screen = Screen.USAGE; show(); });
        Button settings = button("设置", screen == Screen.SETTINGS, v -> { screen = Screen.SETTINGS; show(); });
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, dp(48), 1);
        left.rightMargin = dp(5);
        tabs.addView(home, left);
        LinearLayout.LayoutParams chatTab = new LinearLayout.LayoutParams(0, dp(48), 1);
        chatTab.rightMargin = dp(5);
        tabs.addView(chat, chatTab);
        LinearLayout.LayoutParams middle = new LinearLayout.LayoutParams(0, dp(48), 1);
        middle.rightMargin = dp(5);
        tabs.addView(usage, middle);
        tabs.addView(settings, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(tabs, block(23));
        content = column();
        root.addView(content);
        if (screen == Screen.HOME) {
            content.addView(text("正在获取提醒…", 13, MUTED, false));
            loadReminders();
        } else if (screen == Screen.CHAT) {
            content.addView(text("正在获取聊天记录…", 13, MUTED, false));
            loadChat();
        } else if (screen == Screen.USAGE) {
            content.addView(text("正在获取 Token 用量…", 13, MUTED, false));
            loadUsage();
        } else {
            content.addView(text("正在获取设置…", 13, MUTED, false));
            loadSettings();
        }
    }

    private void loadReminders() {
        runAsync(() -> {
            JSONArray items = Api.reminders(this);
            JSONArray boxes = Api.mailboxes(this);
            runOnUiThread(() -> {
                if (screen != Screen.HOME || content == null) return;
                content.removeAllViews();
                LinearLayout heading = row();
                int unread = 0;
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item != null && item.isNull("read_at")) unread++;
                }
                TextView title = text("阅览提醒", 21, DARK, true);
                heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
                Button refresh = button("刷新 ↻", false, v -> loadReminders());
                heading.addView(refresh, new LinearLayout.LayoutParams(dp(90), dp(43)));
                content.addView(heading, block(10));
                String checkStatus = boxes.length() == 0 ? "尚未连接邮箱" : "正在定期检查";
                if (boxes.length() > 0) {
                    JSONObject box = boxes.optJSONObject(0);
                    if (box != null) {
                        String error = box.optString("last_error");
                        long checked = box.optLong("last_check");
                        checkStatus = !error.isEmpty() ? "检查失败：" + error : checked > 0
                            ? "最近检查 " + Math.max(0, System.currentTimeMillis() / 1000 - checked) + " 秒前" : "等待首次检查";
                    }
                }
                note(content, unread + " 条未阅 · 共 " + items.length() + " 条提醒 · " + checkStatus);
                gap(content, 10);
                if (unread > 0) {
                    Button markAll = button("全部已阅", false, v -> {
                        v.setEnabled(false);
                        runAsync(() -> {
                            JSONObject result = Api.request(this, "/api/reminders/read-all", "POST", null);
                            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                            for (int j = 0; j < items.length(); j++) {
                                JSONObject reminder = items.optJSONObject(j);
                                if (reminder != null && reminder.isNull("read_at")) manager.cancel(reminder.optInt("id"));
                            }
                            runOnUiThread(() -> Toast.makeText(this,
                                "已将 " + result.optInt("updated") + " 条提醒标为已阅", Toast.LENGTH_SHORT).show());
                            loadReminders();
                        });
                    });
                    content.addView(markAll, block(12));
                }
                if (items.length() == 0) {
                    LinearLayout empty = panel();
                    TextView icon = text("✉", 34, GREEN, true);
                    icon.setGravity(Gravity.CENTER);
                    empty.addView(icon, block(14));
                    TextView message = text("这里暂时很安静", 18, DARK, true);
                    message.setGravity(Gravity.CENTER);
                    empty.addView(message, block(7));
                    TextView sub = text("连接邮箱后，值得留意的新邮件会出现在这里。", 12, MUTED, false);
                    sub.setGravity(Gravity.CENTER);
                    empty.addView(sub);
                    content.addView(empty);
                    return;
                }
                for (int i = 0; i < Math.min(items.length(), 50); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item != null) content.addView(reminderCard(item), block(13));
                }
            });
        });
    }

    private LinearLayout reminderCard(JSONObject item) {
        LinearLayout card = panel();
        LinearLayout meta = row();
        boolean high = "high".equals(item.optString("priority"));
        TextView tag = text(high ? "● 优先查看" : "● 阅览提醒", 11, high ? 0xffb07337 : GREEN, true);
        meta.addView(tag, new LinearLayout.LayoutParams(0, -2, 1));
        String date = new SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)
            .format(new Date(item.optLong("created_at") * 1000));
        meta.addView(text(date, 10, MUTED, false));
        card.addView(meta, block(14));
        card.addView(text(item.optString("subject"), 18, DARK, true), block(7));
        card.addView(text(item.optString("sender"), 11, MUTED, false), block(16));

        LinearLayout summary = column();
        summary.setPadding(dp(15), dp(15), dp(15), dp(15));
        summary.setBackground(shape(SUMMARY, 13, 0));
        TextView summaryLabel = text("✦  AGENT 总结", 10, GREEN, true);
        summaryLabel.setLetterSpacing(.08f);
        summary.addView(summaryLabel, block(8));
        String value = item.optString("summary");
        TextView summaryText = text(value.isEmpty() ? "暂无正文总结。开启智能总结后，新邮件会显示简要内容。" : value,
            value.startsWith("验证码：") ? 20 : 13, value.isEmpty() ? MUTED : value.startsWith("验证码：") ? GREEN : DARK,
            value.startsWith("验证码："));
        summaryText.setLineSpacing(dp(3), 1f);
        summary.addView(summaryText);
        card.addView(summary, block(13));
        note(card, "提醒原因 · " + item.optString("reason"));
        if (!item.isNull("total_tokens"))
            note(card, "本封 Agent 用量 · " + item.optInt("total_tokens") + " token（输入 "
                + item.optInt("input_tokens") + " / 输出 " + item.optInt("output_tokens") + "）");

        String url = item.optString("mail_url");
        String subject = item.optString("subject");
        card.addView(button("打开邮箱查找  ↗", true, v -> {
            if (url.startsWith("https://")) startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        }), block(8));
        LinearLayout actions = row();
        Button copy = button("复制主题", false, v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("邮件主题", subject));
            Toast.makeText(this, "已复制邮件主题", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(43), 1);
        half.rightMargin = dp(8);
        actions.addView(copy, half);
        if (item.isNull("read_at")) {
            Button done = button("标为已阅", false, v -> runAsync(() -> {
                Api.request(this, "/api/reminders/" + item.optInt("id") + "/read", "POST", null);
                loadReminders();
            }));
            actions.addView(done, new LinearLayout.LayoutParams(0, dp(43), 1));
        } else {
            TextView read = text("已阅", 12, MUTED, false);
            read.setGravity(Gravity.CENTER);
            actions.addView(read, new LinearLayout.LayoutParams(0, dp(43), 1));
        }
        card.addView(actions);
        return card;
    }

    private void loadChat() {
        runAsync(() -> {
            JSONArray history = Api.chatHistory(this);
            JSONArray mailboxes = Api.mailboxes(this);
            runOnUiThread(() -> {
                if (screen != Screen.CHAT || content == null) return;
                content.removeAllViews();
                LinearLayout composer = panel();
                sectionTitle(composer, "AGENT CHAT", "发送消息");
                note(composer, "Agent 可查询你的站内提醒、邮箱状态和用量，无法发送或修改原邮件。");
                composer.addView(text("模型", 12, DARK, true), block(6));
                Spinner model = new Spinner(this);
                ArrayAdapter<String> models = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                    new String[]{"DeepSeek Flash", "DeepSeek V4 Pro"});
                models.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                model.setAdapter(models);
                model.setSelection(Api.prefs(this).getInt("chat_model", 0));
                composer.addView(model, block(12));
                composer.addView(text("思考强度", 12, DARK, true), block(6));
                Spinner effort = new Spinner(this);
                ArrayAdapter<String> efforts = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                    new String[]{"关闭", "低", "高", "最高"});
                efforts.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                effort.setAdapter(efforts);
                effort.setSelection(Api.prefs(this).getInt("chat_effort", 0));
                composer.addView(effort, block(12));
                EditText message = new EditText(this);
                message.setHint("例如：最近有什么需要处理的邮件？");
                message.setTextSize(14);
                message.setTextColor(DARK);
                message.setHintTextColor(MUTED);
                message.setMinLines(3);
                message.setMaxLines(6);
                message.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                message.setBackground(shape(FIELD, 12, BORDER));
                message.setPadding(dp(14), dp(12), dp(14), dp(12));
                composer.addView(message, block(12));
                Button send = button("发送给 Agent", true, v -> {
                    String value = message.getText().toString().trim();
                    if (value.isEmpty()) { Toast.makeText(this, "请输入消息", Toast.LENGTH_SHORT).show(); return; }
                    int modelIndex = model.getSelectedItemPosition();
                    int effortIndex = effort.getSelectedItemPosition();
                    String[] modelValues = {"deepseek-flash", "deepseek-v4-pro"};
                    String[] effortValues = {"none", "low", "high", "max"};
                    Api.prefs(this).edit().putInt("chat_model", modelIndex).putInt("chat_effort", effortIndex).apply();
                    sendChat(value, modelValues[modelIndex], effortValues[effortIndex], v);
                });
                composer.addView(send);
                content.addView(composer, block(14));
                content.addView(composeMailPanel(mailboxes, model, effort), block(14));
                if (history.length() == 0) note(content, "还没有对话。试着问问最近有哪些重要邮件。");
                int first = Math.max(0, history.length() - 20);
                for (int i = history.length() - 1; i >= first; i--) {
                    JSONObject latest = history.optJSONObject(i);
                    if (latest == null) continue;
                    JSONObject previous = i > first ? history.optJSONObject(i - 1) : null;
                    if ("assistant".equals(latest.optString("role")) && previous != null
                        && "user".equals(previous.optString("role"))) {
                        addChatBubble(previous);
                        addChatBubble(latest);
                        i--;
                    } else {
                        addChatBubble(latest);
                    }
                }
            });
        });
    }

    private void addChatBubble(JSONObject item) {
        LinearLayout bubble = panel();
        boolean user = "user".equals(item.optString("role"));
        if (user) bubble.setBackground(shape(CHAT_USER, 20, BORDER));
        bubble.addView(text(user ? "你" : "邮箱总管 Agent", 11, GREEN, true), block(8));
        TextView body = text(item.optString("content"), 13, DARK, false);
        body.setLineSpacing(dp(4), 1f);
        bubble.addView(body);
        if (!user && !item.isNull("total_tokens")) {
            gap(bubble, 8);
            bubble.addView(text(item.optInt("total_tokens") + " token · " + item.optString("model"), 10, MUTED, false));
        }
        content.addView(bubble, block(11));
    }

    private LinearLayout composeMailPanel(JSONArray mailboxes, Spinner model, Spinner effort) {
        LinearLayout form = panel();
        sectionTitle(form, "COMPOSE MAIL", "撰写并发送邮件");
        ArrayList<String> addresses = new ArrayList<>();
        ArrayList<Integer> ids = new ArrayList<>();
        for (int i = 0; i < mailboxes.length(); i++) {
            JSONObject box = mailboxes.optJSONObject(i);
            if (box != null && "qq".equals(box.optString("provider"))) {
                addresses.add(box.optString("address"));
                ids.add(box.optInt("id"));
            }
        }
        if (addresses.isEmpty()) {
            note(form, "请先在设置中连接 QQ 邮箱，才能生成并发送邮件。");
            return form;
        }
        form.addView(text("发件箱", 12, DARK, true), block(6));
        Spinner sender = new Spinner(this);
        ArrayAdapter<String> choices = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, addresses);
        choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sender.setAdapter(choices);
        form.addView(sender, block(12));
        form.addView(text("收件人", 12, DARK, true), block(6));
        EditText recipient = input("name@example.com", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        form.addView(recipient);
        form.addView(text("写作要求", 12, DARK, true), block(6));
        EditText prompt = new EditText(this);
        prompt.setHint("例如：给老师写一封礼貌的请假邮件，说明明天因病缺席。");
        prompt.setTextSize(14);
        prompt.setTextColor(DARK);
        prompt.setHintTextColor(MUTED);
        prompt.setMinLines(3);
        prompt.setMaxLines(6);
        prompt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        prompt.setBackground(shape(FIELD, 12, BORDER));
        prompt.setPadding(dp(14), dp(12), dp(14), dp(12));
        form.addView(prompt, block(12));
        Button generate = button("生成草稿", true, v -> {
            String to = recipient.getText().toString().trim();
            String request = prompt.getText().toString().trim();
            if (to.isEmpty() || request.isEmpty()) {
                Toast.makeText(this, "请填写收件人和写作要求", Toast.LENGTH_SHORT).show();
                return;
            }
            v.setEnabled(false);
            int boxId = ids.get(sender.getSelectedItemPosition());
            String[] models = {"deepseek-flash", "deepseek-v4-pro"};
            String[] efforts = {"none", "low", "high", "max"};
            int modelIndex = model.getSelectedItemPosition();
            int effortIndex = effort.getSelectedItemPosition();
            runAsync(() -> {
                try {
                    JSONObject draft = Api.request(this, "/api/mail/draft", "POST", new JSONObject()
                        .put("mailbox_id", boxId).put("recipient", to).put("prompt", request)
                        .put("model", models[modelIndex])
                        .put("effort", efforts[effortIndex]));
                    runOnUiThread(() -> showDraftPreview(boxId, draft));
                } finally {
                    runOnUiThread(() -> v.setEnabled(true));
                }
            });
        });
        form.addView(generate, block(9));
        note(form, "草稿生成后可修改。点击确认发送前，请核对全部内容；QQ 邮箱需开启 SMTP。");
        return form;
    }

    private void showDraftPreview(int boxId, JSONObject draft) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout fields = column();
        fields.setPadding(dp(18), dp(10), dp(18), dp(10));
        fields.addView(text("发件人 · " + draft.optString("sender"), 12, GREEN, true), block(13));
        fields.addView(text("收件人", 12, DARK, true), block(5));
        EditText to = input("收件人", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        to.setText(draft.optString("recipient"));
        fields.addView(to);
        fields.addView(text("主题", 12, DARK, true), block(5));
        EditText subject = input("主题", InputType.TYPE_CLASS_TEXT);
        subject.setText(draft.optString("subject"));
        fields.addView(subject);
        fields.addView(text("正文", 12, DARK, true), block(5));
        EditText body = new EditText(this);
        body.setText(draft.optString("body"));
        body.setTextSize(14);
        body.setTextColor(DARK);
        body.setMinLines(8);
        body.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        body.setBackground(shape(FIELD, 12, BORDER));
        body.setPadding(dp(14), dp(12), dp(14), dp(12));
        fields.addView(body);
        scroll.addView(fields);
        String requestId = UUID.randomUUID().toString();
        new AlertDialog.Builder(this).setTitle("审阅邮件草稿").setView(scroll)
            .setNegativeButton("取消", null)
            .setPositiveButton("确认发送", (dialog, which) -> runAsync(() -> {
                Api.request(this, "/api/mail/send", "POST", new JSONObject()
                    .put("mailbox_id", boxId).put("recipient", to.getText().toString().trim())
                    .put("subject", subject.getText().toString().trim())
                    .put("body", body.getText().toString().trim()).put("request_id", requestId));
                runOnUiThread(() -> Toast.makeText(this, "邮件已发送", Toast.LENGTH_LONG).show());
            })).show();
    }

    private void sendChat(String message, String model, String effort, View button) {
        button.setEnabled(false);
        runAsync(() -> {
            try {
                Api.request(this, "/api/chat", "POST", new JSONObject()
                    .put("message", message).put("model", model).put("effort", effort));
                loadChat();
            } catch (Exception error) {
                runOnUiThread(() -> {
                    button.setEnabled(true);
                    Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void loadUsage() {
        runAsync(() -> {
            JSONObject usage = Api.request(this, "/api/usage", "GET", null);
            runOnUiThread(() -> {
                if (screen != Screen.USAGE || content == null) return;
                content.removeAllViews();
                LinearLayout totals = panel();
                sectionTitle(totals, "DEEPSEEK USAGE", "Token 用量");
                totals.addView(text("累计  " + usage.optLong("total_tokens") + " token", 22, DARK, true), block(10));
                totals.addView(text("今日  " + usage.optLong("today_tokens") + " token", 18, GREEN, true), block(10));
                note(totals, "今日按北京时间计算。统计从本功能上线后开始，旧用量无法追溯。");
                content.addView(totals, block(14));
                LinearLayout list = panel();
                sectionTitle(list, "PER MESSAGE", "每封邮件");
                JSONArray items = usage.optJSONArray("items");
                if (items == null || items.length() == 0) note(list, "暂无可统计的 Agent 调用。");
                if (items != null) for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) continue;
                    list.addView(text(item.optString("subject"), 14, DARK, true), block(4));
                    String amount = item.isNull("total_tokens") ? "用量未返回" : item.optInt("total_tokens") + " token · 输入 "
                        + item.optInt("input_tokens") + " / 输出 " + item.optInt("output_tokens");
                    note(list, item.optString("address") + " · " + amount);
                    gap(list, 8);
                }
                content.addView(list);
            });
        });
    }

    private void loadSettings() {
        runAsync(() -> {
            JSONArray mailboxes = Api.mailboxes(this);
            JSONObject me = Api.request(this, "/api/me", "GET", null);
            runOnUiThread(() -> {
                if (screen != Screen.SETTINGS || content == null) return;
                content.removeAllViews();
                LinearLayout accounts = panel();
                sectionTitle(accounts, "01 / ACCOUNTS", "邮箱账号");
                if (mailboxes.length() == 0) note(accounts, "尚未连接邮箱。添加 QQ 邮箱后会自动检查。");
                for (int i = 0; i < mailboxes.length(); i++) {
                    JSONObject box = mailboxes.optJSONObject(i);
                    if (box == null) continue;
                    accounts.addView(text(box.optString("address"), 14, DARK, true), block(3));
                    String error = box.optString("last_error");
                    note(accounts, error.isEmpty() ? "服务器约每 5 秒检查新邮件" : "检查失败：" + error);
                    LinearLayout actions = row();
                    Button check = button("立即检查", false, v -> runAsync(() -> {
                        Api.request(this, "/api/mailboxes/" + box.optInt("id") + "/check", "POST", null);
                        loadSettings();
                        runOnUiThread(() -> Toast.makeText(this, "检查完成", Toast.LENGTH_SHORT).show());
                    }));
                    LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(42), 1);
                    half.rightMargin = dp(8);
                    actions.addView(check, half);
                    actions.addView(button("移除", false, v -> new AlertDialog.Builder(this)
                        .setTitle("移除邮箱？")
                        .setMessage("此邮箱的阅览提醒也会删除。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("移除", (dialog, which) -> runAsync(() -> {
                            Api.request(this, "/api/mailboxes/" + box.optInt("id"), "DELETE", null);
                            loadSettings();
                        })).show()), new LinearLayout.LayoutParams(0, dp(42), 1));
                    accounts.addView(actions, block(15));
                }
                accounts.addView(button("＋ 添加 QQ 邮箱", true, v -> { screen = Screen.ADD_QQ; show(); }));
                content.addView(accounts, block(14));

                LinearLayout appearance = panel();
                sectionTitle(appearance, "02 / APPEARANCE", "界面主题");
                note(appearance, "选择后立即应用，并保存在这台手机上。");
                appearance.addView(button((darkTheme ? "" : "✓  ") + "清爽工作台", !darkTheme,
                    v -> chooseTheme("light")), block(8));
                appearance.addView(button((darkTheme ? "✓  " : "") + "深色指挥台", darkTheme,
                    v -> chooseTheme("dark")));
                content.addView(appearance, block(14));

                LinearLayout intelligence = panel();
                sectionTitle(intelligence, "03 / INTELLIGENCE", "智能设置");
                note(intelligence, me.optBoolean("has_api_key") ? "DeepSeek API Key 已配置" : "DeepSeek API Key 尚未配置");
                intelligence.addView(button("设置 DeepSeek API Key", false, v -> { screen = Screen.KEY; show(); }), block(15));
                LinearLayout toggleRow = row();
                LinearLayout toggleText = column();
                toggleText.addView(text("邮件智能总结", 14, DARK, true), block(3));
                toggleText.addView(text("仅对新邮件生效", 11, MUTED, false));
                toggleRow.addView(toggleText, new LinearLayout.LayoutParams(0, -2, 1));
                Switch toggle = new Switch(this);
                toggle.setThumbTintList(ColorStateList.valueOf(WHITE));
                toggle.setTrackTintList(new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{GREEN, 0xffb5c3b7}));
                toggle.setChecked(me.optBoolean("body_summary_enabled"));
                toggle.setOnCheckedChangeListener((view, enabled) -> runAsync(() -> {
                    try {
                        Api.request(this, "/api/settings/body-summary", "PUT", new JSONObject().put("enabled", enabled));
                    } catch (Exception error) {
                        runOnUiThread(() -> {
                            toggle.setOnCheckedChangeListener(null);
                            toggle.setChecked(!enabled);
                            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
                            loadSettings();
                        });
                    }
                }));
                toggleRow.addView(toggle);
                intelligence.addView(toggleRow, block(10));
                note(intelligence, "系统读取有限正文识别验证码，不把验证码交给 DeepSeek；其他邮件仅在开启智能总结后才把正文片段交给 DeepSeek。");
                content.addView(intelligence, block(14));

                LinearLayout app = panel();
                sectionTitle(app, "04 / APP", "应用与通知");
                note(app, "当前版本 " + appVersion());
                note(app, "新邮件显示在通知栏，不弹出顶部横幅，也不显示常驻运行图标。App 打开时约每 5 秒同步；后台检查可能受安卓系统延迟。");
                app.addView(button("测试通知", false, v -> PollReceiver.sendTestNotification(this)), block(8));
                app.addView(button("通知设置", false, v -> startActivity(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                    .putExtra(Settings.EXTRA_CHANNEL_ID, PollReceiver.CHANNEL))), block(8));
                app.addView(button("检查 App 更新", false, v -> Updater.check(this, true)));
                content.addView(app, block(14));
                content.addView(button("退出登录", false, v -> logout()));
            });
        });
    }

    private void formHeader(String title, String caption) {
        Button back = button("← 返回设置", false, v -> { screen = Screen.SETTINGS; show(); });
        root.addView(back, block(18));
        hero("MAIL MANAGER", title, caption);
    }
    private void showAddQQ() {
        formHeader("连接 QQ 邮箱", "使用邮箱专用授权码，不要填写 QQ 登录密码。");
        LinearLayout form = panel();
        sectionTitle(form, "NEW ACCOUNT", "邮箱资料");
        form.addView(text("QQ 邮箱地址", 12, DARK, true), block(7));
        EditText address = input("name@qq.com", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        form.addView(address);
        form.addView(text("IMAP 专用授权码", 12, DARK, true), block(7));
        EditText code = input("在 QQ 邮箱设置中生成", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(code);
        note(form, "请先在 QQ 邮箱设置中开启 IMAP 服务，再生成授权码。");
        gap(form, 10);
        form.addView(button("保存并连接", true, v -> {
            String email = address.getText().toString().trim();
            String secret = code.getText().toString();
            runAsync(() -> {
                Api.request(this, "/api/mailboxes", "POST", new JSONObject()
                    .put("provider", "qq").put("address", email).put("secret", secret).put("interval_minutes", 5));
                screen = Screen.SETTINGS;
                runOnUiThread(this::show);
            });
        }));
        root.addView(form);
    }
    private void showKey() {
        formHeader("设置 DeepSeek", "密钥只保存在你的服务器，用于智能筛选与总结。");
        LinearLayout form = panel();
        sectionTitle(form, "API KEY", "连接智能体");
        EditText key = input("输入 DeepSeek API Key", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(key);
        note(form, "保存后可在设置页开启邮件智能总结。");
        gap(form, 10);
        form.addView(button("保存密钥", true, v -> {
            String value = key.getText().toString().trim();
            runAsync(() -> {
                Api.request(this, "/api/settings/api-key", "PUT", new JSONObject().put("api_key", value));
                screen = Screen.SETTINGS;
                runOnUiThread(this::show);
            });
        }));
        root.addView(form);
    }
    private void logout() {
        runAsync(() -> {
            try { Api.request(this, "/api/logout", "POST", null); } catch (Exception ignored) {}
            runOnUiThread(() -> {
                Api.prefs(this).edit().remove("token").remove("email").apply();
                screen = Screen.HOME;
                show();
            });
        });
    }
}
