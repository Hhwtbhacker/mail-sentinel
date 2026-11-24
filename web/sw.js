self.addEventListener("push", event => {
  let data = {};
  try { data = event.data.json(); } catch {}
  event.waitUntil(self.registration.showNotification(data.title || "邮箱总管提醒", {
    body: data.body || "有值得查看的新邮件",
    icon: "/assets/icon.svg",
    badge: "/assets/icon.svg",
    data: {url: "/"}
  }));
});
self.addEventListener("notificationclick", event => {
  event.notification.close();
  event.waitUntil(clients.openWindow(event.notification.data?.url || "/"));
});
