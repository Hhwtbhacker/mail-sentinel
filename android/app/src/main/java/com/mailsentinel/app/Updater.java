package com.mailsentinel.app;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

final class Updater {
    private static final long MAX_APK_BYTES = 100L * 1024 * 1024;
    private static boolean pendingInstall;

    static void check(MainActivity activity, boolean manual) {
        activity.runAsync(() -> {
            try {
                JSONObject release = Api.request(activity, "/assets/update.json", "GET", null);
                int latest = release.getInt("versionCode");
                int installed = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionCode;
                if (latest <= installed) {
                    if (manual) activity.runOnUiThread(() -> toast(activity, "已经是最新版本"));
                    return;
                }
                String version = release.optString("versionName", "新版本");
                String notes = release.optString("notes", "修复与改进");
                activity.runOnUiThread(() -> new AlertDialog.Builder(activity)
                    .setTitle("发现邮箱总管 " + version)
                    .setMessage(notes + "\n\n下载完成并校验后，安卓会请你确认安装。")
                    .setNegativeButton("稍后", null)
                    .setPositiveButton("下载更新", (dialog, which) -> download(activity, release))
                    .show());
            } catch (Exception error) {
                if (manual) activity.runOnUiThread(() -> toast(activity, "检查更新失败：" + error.getMessage()));
            }
        });
    }

    private static void download(MainActivity activity, JSONObject release) {
        activity.runAsync(() -> {
            String expected = release.getString("sha256").toLowerCase();
            if (!expected.matches("[0-9a-f]{64}")) throw new Exception("更新清单校验值无效");
            File target = ApkProvider.updateFile(activity);
            File directory = target.getParentFile();
            if (!directory.exists() && !directory.mkdirs()) throw new Exception("无法创建下载目录");
            File temporary = new File(directory, "update-download.apk");
            HttpURLConnection connection = null;
            try {
                String base = Api.base(activity);
                if (!base.startsWith("https://")) throw new Exception("更新必须使用 HTTPS");
                connection = (HttpURLConnection) new URL(base + "/assets/mail-sentinel-debug.apk").openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(30000);
                if (connection.getResponseCode() != 200) throw new Exception("下载失败：" + connection.getResponseCode());
                if (connection.getContentLengthLong() > MAX_APK_BYTES) throw new Exception("安装包过大");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long count = 0;
                try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(temporary)) {
                    byte[] buffer = new byte[8192];
                    int size;
                    while ((size = input.read(buffer)) != -1) {
                        count += size;
                        if (count > MAX_APK_BYTES) throw new Exception("安装包过大");
                        digest.update(buffer, 0, size);
                        output.write(buffer, 0, size);
                    }
                    output.getFD().sync();
                }
                if (count == 0) throw new Exception("安装包为空");
                StringBuilder actual = new StringBuilder();
                for (byte b : digest.digest()) actual.append(String.format("%02x", b & 0xff));
                if (!expected.equals(actual.toString())) throw new Exception("安装包校验失败");
                PackageInfo info = activity.getPackageManager().getPackageArchiveInfo(temporary.getAbsolutePath(), 0);
                if (info == null || !activity.getPackageName().equals(info.packageName) || info.versionCode != release.getInt("versionCode"))
                    throw new Exception("安装包身份或版本不匹配");
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                activity.runOnUiThread(() -> install(activity));
            } finally {
                if (connection != null) connection.disconnect();
                temporary.delete();
            }
        });
    }

    static void install(MainActivity activity) {
        if (!ApkProvider.updateFile(activity).isFile()) return;
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            pendingInstall = true;
            toast(activity, "请允许邮箱总管安装更新，然后返回应用");
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(settings);
            return;
        }
        pendingInstall = false;
        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        intent.setData(ApkProvider.URI);
        intent.setClipData(ClipData.newRawUri("邮箱总管更新", ApkProvider.URI));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { activity.startActivity(intent); }
        catch (Exception error) { toast(activity, "无法打开安卓安装界面：" + error.getMessage()); }
    }

    static void onResume(MainActivity activity) {
        if (pendingInstall && activity.getPackageManager().canRequestPackageInstalls()) install(activity);
    }

    private static void toast(MainActivity activity, String message) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }
}
