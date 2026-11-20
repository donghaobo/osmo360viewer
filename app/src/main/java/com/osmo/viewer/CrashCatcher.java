package com.osmo.viewer;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 闪退捕获：接管未捕获异常，把栈写到文件里，下次启动可以查看/导出。
 *
 * 崩溃发生在任何线程、任何时机（包括 onCreate 里）都能记下来，
 * 因为处理器在 Application 创建时就装上了。
 */
public final class CrashCatcher implements Thread.UncaughtExceptionHandler {

    private static final String DIR = "crash";
    private static final String FILE = "last-crash.txt";

    private final Thread.UncaughtExceptionHandler previous;
    private final Context app;

    private CrashCatcher(Context app, Thread.UncaughtExceptionHandler previous) {
        this.app = app;
        this.previous = previous;
    }

    public static void install(Context ctx) {
        try {
            Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
            if (current instanceof CrashCatcher) return;   // 已装过
            Thread.setDefaultUncaughtExceptionHandler(
                    new CrashCatcher(ctx.getApplicationContext(), current));
        } catch (Throwable ignored) {
        }
    }

    public static File dir(Context ctx) {
        File d = ctx.getExternalFilesDir(DIR);
        if (d == null) d = new File(ctx.getFilesDir(), DIR);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File file(Context ctx) {
        return new File(dir(ctx), FILE);
    }

    /** 上次崩溃的内容；没有则返回 null。 */
    public static String read(Context ctx) {
        File f = file(ctx);
        if (!f.exists() || f.length() == 0) return null;
        try {
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    public static void clear(Context ctx) {
        File f = file(ctx);
        if (f.exists()) f.delete();
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        try {
            write(app, thread, throwable);
        } catch (Throwable ignored) {
        }
        if (previous != null) previous.uncaughtException(thread, throwable);
    }

    /** 记录一次被 catch 住的致命错误（例如布局加载失败），下次启动会提示。 */
    public static void record(Context ctx, Throwable t, String where) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .format(new Date())).append('\n');
            sb.append("机型: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    .append("  Android ").append(Build.VERSION.RELEASE).append('\n');
            sb.append("版本: ").append(version(ctx)).append('\n');
            sb.append("位置: ").append(where).append('\n');
            sb.append("---- 异常 ----\n");
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            sb.append(sw.toString());
            String content = sb.toString();
            try (FileOutputStream out = new FileOutputStream(file(ctx))) {
                out.write(content.getBytes("UTF-8"));
            }
            android.util.Log.e("OsvCrash", content);
        } catch (Throwable ignored) {
        }
    }

    private static void write(Context ctx, Thread thread, Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append("时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date())).append('\n');
        sb.append("机型: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append("  Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("版本: ").append(version(ctx)).append('\n');
        sb.append("线程: ").append(thread == null ? "?" : thread.getName()).append('\n');
        sb.append("---- 异常 ----\n");
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        sb.append(sw.toString());
        sb.append("---- Activity 栈 ----\n");

        String content = sb.toString();
        try (FileOutputStream out = new FileOutputStream(file(ctx))) {
            out.write(content.getBytes("UTF-8"));
        } catch (IOException ignored) {
        }
        android.util.Log.e("OsvCrash", content);
        Diag.e("OsvCrash", "闪退已记录: " + t, t);
    }

    private static String version(Context ctx) {
        try {
            android.content.pm.PackageInfo p = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            return p.versionName + " (" + p.versionCode + ")";
        } catch (Exception e) {
            return "?";
        }
    }
}
