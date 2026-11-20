package com.osmo.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * 诊断日志：logcat + 内存环形缓冲 + 落盘（files/logs/osv.log），可导出成文本文件。
 *
 * 播放链路（解封装、解码器、GL、音频、看门狗）里的异常都往这里写，
 * 出了「一路不播」这类偶发问题时，用户能把整段日志发回来。
 */
public final class Diag {

    private static final int MAX_LINES = 3000;
    private static final String TAG = "OsvDiag";

    private static final SimpleDateFormat LINE_TS =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
    private static final SimpleDateFormat FILE_TS =
            new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);

    private static final String PREFS = "osv_diag";
    private static final String KEY_ENABLED = "log_enabled";

    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final Object LOCK = new Object();
    private static File logFile;
    /** 默认关闭：只在用户主动开启后才记录，平时不写盘。 */
    private static volatile boolean enabled;

    private Diag() {}

    public static void init(Context ctx) {
        if (logFile != null) return;
        enabled = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false);
        File dir = new File(ctx.getFilesDir(), "logs");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, "osv.log");
        if (f.length() > 512 * 1024) f.delete();
        logFile = f;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(Context ctx, boolean on) {
        init(ctx);
        ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, on).apply();
        enabled = on;
        if (on) {
            append('I', TAG, "诊断日志已开启", null);
        } else {
            synchronized (LOCK) {
                LINES.clear();
                File f = logFile;
                if (f != null && f.exists()) f.delete();
            }
        }
    }

    public static void i(String tag, String msg) {
        append('I', tag, msg, null);
    }

    public static void w(String tag, String msg) {
        append('W', tag, msg, null);
    }

    public static void w(String tag, String msg, Throwable t) {
        append('W', tag, msg, t);
    }

    public static void e(String tag, String msg, Throwable t) {
        append('E', tag, msg, t);
    }

    private static void append(char level, String tag, String msg, Throwable t) {
        if (!enabled) return;   // 关闭时零开销、不写盘
        String stamp;
        synchronized (LINE_TS) {   // SimpleDateFormat 非线程安全，GL/解码/UI 都会调这里
            stamp = LINE_TS.format(new Date());
        }
        String line = stamp + " " + level + "/" + tag + ": " + msg;
        if (t != null) line += "\n    " + Log.getStackTraceString(t).replace("\n", "\n    ");

        switch (level) {
            case 'E': Log.e(tag, msg, t); break;
            case 'W': Log.w(tag, msg, t); break;
            default: Log.i(tag, msg); break;
        }

        synchronized (LOCK) {
            if (LINES.size() >= MAX_LINES) LINES.pollFirst();
            LINES.addLast(line);
            File f = logFile;
            if (f != null) {
                try (FileOutputStream out = new FileOutputStream(f, true)) {
                    out.write((line + "\n").getBytes("UTF-8"));
                } catch (IOException ignored) {
                }
            }
        }
    }

    public static String dump() {
        StringBuilder sb = new StringBuilder();
        synchronized (LOCK) {
            for (String l : LINES) sb.append(l).append('\n');
        }
        return sb.toString();
    }

    /** 把当前缓冲写成文本文件（外部私有目录，无需权限），返回文件。 */
    public static File export(Context ctx, String note) {
        init(ctx);
        StringBuilder sb = new StringBuilder();
        sb.append("OSV 播放器诊断日志\n");
        sb.append("时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date())).append('\n');
        sb.append("机型: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append("  Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("应用: ").append(appVersion(ctx)).append('\n');
        if (note != null && !note.isEmpty()) sb.append("场景: ").append(note).append('\n');
        sb.append("---- 日志 ----\n");
        if (!isEnabled()) {
            sb.append("（诊断日志未开启，本次没有记录。可在相册页右上角菜单里开启后重现问题。）\n");
        } else {
            sb.append(dump());
        }

        File dir = ctx.getExternalFilesDir("logs");
        if (dir == null) dir = new File(ctx.getFilesDir(), "logs");
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, "osv-viewer-log-" + FILE_TS.format(new Date()) + ".txt");
        try (FileOutputStream os = new FileOutputStream(out)) {
            os.write(sb.toString().getBytes("UTF-8"));
        } catch (IOException e) {
            Log.w(TAG, "写日志失败", e);
        }
        return out;
    }

    private static String appVersion(Context ctx) {
        try {
            android.content.pm.PackageInfo p = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            return p.versionName + " (" + p.versionCode + ")";
        } catch (Exception e) {
            return "?";
        }
    }

    /** 导出并弹窗：分享 / 存到下载 / 关闭。 */
    public static void exportAndShow(final Activity activity, String note) {
        final File f = export(activity, note);
        String path = f.getAbsolutePath();
        new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("日志已导出")
                .setMessage(path + "\n\n可以「分享」发出去，或「存到下载」后在文件管理器里找到。")
                .setPositiveButton("分享", (d, w) -> share(activity, f))
                .setNeutralButton("存到下载", (d, w) -> {
                    boolean ok = copyToDownloads(activity, f);
                    Toast.makeText(activity, ok ? "已保存到下载目录" : "保存失败，请用「分享」",
                            Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private static void share(Activity activity, File f) {
        try {
            Uri uri = LogFileProvider.uriFor(f.getName());
            Intent i = new Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(i, "导出日志"));
        } catch (Exception e) {
            Diag.w(TAG, "分享失败", e);
            Toast.makeText(activity, "分享失败，日志路径：" + f.getAbsolutePath(),
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Android 10+ 可以直接写进 Downloads，用户在「文件」里就能看到。 */
    static boolean copyToDownloads(Context ctx, File src) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false;
        Uri dst = null;
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, src.getName());
            cv.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            cv.put(MediaStore.Downloads.IS_PENDING, 1);
            dst = ctx.getContentResolver()
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (dst == null) return false;
            try (OutputStream os = ctx.getContentResolver().openOutputStream(dst)) {
                if (os == null) return false;
                byte[] buf = new byte[8192];
                try (java.io.FileInputStream in = new java.io.FileInputStream(src)) {
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
            }
            cv.clear();
            cv.put(MediaStore.Downloads.IS_PENDING, 0);
            ctx.getContentResolver().update(dst, cv, null, null);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "复制到下载失败", e);
            return false;
        }
    }
}
