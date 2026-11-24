package com.mailsentinel.app;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;

public class PollReceiver extends BroadcastReceiver {
    static final String CHANNEL = "mail_reminders_quiet_v1";

    static void schedule(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, PollReceiver.class);
        PendingIntent pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        alarms.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 60000, AlarmManager.INTERVAL_FIFTEEN_MINUTES, pending);
    }

    static synchronized void checkAndNotify(Context context) throws Exception {
        if (Api.prefs(context).getString("token", "").isEmpty()) return;
        JSONArray items = Api.reminders(context);
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        ensureChannel(manager);
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;

        long lastId = Api.prefs(context).getLong("last_notice_id", -1);
        if (lastId < 0) {
            long oldTime = Api.prefs(context).getLong("last_notice", System.currentTimeMillis() / 1000);
            lastId = 0;
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null && item.optLong("created_at") <= oldTime) lastId = Math.max(lastId, item.optLong("id"));
            }
        }
        long newestId = lastId;
        for (int i = items.length() - 1; i >= 0; i--) {
            JSONObject item = items.getJSONObject(i);
            long id = item.optLong("id");
            if (!item.isNull("read_at")) manager.cancel((int) id);
            if (id <= lastId) continue;
            newestId = Math.max(newestId, id);
            if (!item.isNull("read_at")) continue;
            String summary = item.optString("summary", "");
            String detail = summary.isEmpty() ? item.optString("reason", "建议查看原邮件") : summary;
            String mailbox = item.optString("address", "未知邮箱");
            String notice = "收件邮箱：" + mailbox + "\n" + detail;
            Intent open = new Intent(context, MainActivity.class);
            PendingIntent click = PendingIntent.getActivity(context, (int) id, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle(item.optString("subject", "新邮件"))
                .setContentText("收件邮箱：" + mailbox + " · " + detail)
                .setSubText("收件邮箱：" + mailbox)
                .setStyle(new Notification.BigTextStyle().bigText(notice))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setPriority(Notification.PRIORITY_DEFAULT)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentIntent(click).setAutoCancel(true).build();
            manager.notify((int) id, notification);
        }
        Api.prefs(context).edit().putLong("last_notice_id", newestId).apply();
    }

    private static void ensureChannel(NotificationManager manager) {
        NotificationChannel channel = new NotificationChannel(CHANNEL, "新邮件通知", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("新邮件显示在通知栏，不显示顶部弹窗");
        channel.enableVibration(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        manager.createNotificationChannel(channel);
    }

    static void sendTestNotification(Context context) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(context, "请先允许邮箱总管发送通知", Toast.LENGTH_LONG).show();
            return;
        }
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        ensureChannel(manager);
        Notification notification = new Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("邮箱总管通知测试")
            .setContentText("新邮件会显示在通知栏")
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setPriority(Notification.PRIORITY_DEFAULT)
            .setAutoCancel(true).build();
        manager.notify(999999, notification);
    }

    @Override public void onReceive(Context context, Intent intent) {
        PendingResult pending = goAsync();
        new Thread(() -> {
            try { checkAndNotify(context); }
            catch (Exception ignored) { }
            finally { pending.finish(); }
        }).start();
    }
}
