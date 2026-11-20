package com.osmo.viewer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 复制任务的前台服务：切到后台也继续传，通知里显示进度。
 *
 * 任务按加入顺序逐个执行（OSV 动辄几个 GB，并发写同一个 NAS 只会互相拖慢）。
 * 任务表是进程内静态的：进程被杀后队列丢失，这是 v1 的已知限制。
 */
public class TransferService extends Service {

    public static final String EXTRA_JOB = "job";

    private static final String CHANNEL_ID = "transfer";
    private static final int NOTIFICATION_ID = 0x0510;

    /** 任务表：Activity 直接读它渲染列表。 */
    private static final Map<String, TransferJob> JOBS = new LinkedHashMap<>();
    private static final ArrayDeque<TransferJob> QUEUE = new ArrayDeque<>();
    private static Thread worker;
    private static boolean workerRunning;

    public static List<TransferJob> jobs() {
        synchronized (JOBS) {
            return new ArrayList<>(JOBS.values());
        }
    }

    public static void clearFinished() {
        synchronized (JOBS) {
            List<String> drop = new ArrayList<>();
            for (TransferJob job : JOBS.values()) {
                if (job.finished()) drop.add(job.id);
            }
            for (String id : drop) JOBS.remove(id);
        }
    }

    public static void cancel(String id) {
        synchronized (JOBS) {
            TransferJob job = JOBS.get(id);
            if (job != null && !job.finished()) job.cancelRequested = true;
        }
    }

    public static boolean anyRunning() {
        synchronized (JOBS) {
            for (TransferJob job : JOBS.values()) {
                if (!job.finished()) return true;
            }
        }
        return false;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureChannel();
        startForeground(NOTIFICATION_ID, buildNotification("准备复制…", null));
        if (intent != null && intent.getSerializableExtra(EXTRA_JOB) instanceof TransferJob) {
            TransferJob job = (TransferJob) intent.getSerializableExtra(EXTRA_JOB);
            synchronized (JOBS) {
                JOBS.put(job.id, job);
                QUEUE.addLast(job);
            }
            startWorker();
        }
        // 注意：不能在这里判断"没任务就 stopSelf"——worker 是普通线程，
        // 服务一停系统可能把进程回收，正在跑的复制就断了。收尾在 loop()。
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Throwable ignored) {
        }
        // 服务退出不代表任务该被砍：worker 是普通线程，会继续把队列跑完
    }

    // ------------------------------------------------------------------ 执行

    private void startWorker() {
        synchronized (TransferService.class) {
            if (workerRunning) return;
            workerRunning = true;
            worker = new Thread(this::loop, "osv-transfer");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void loop() {
        try {
            while (true) {
                TransferJob job;
                synchronized (JOBS) {
                    job = QUEUE.pollFirst();
                }
                if (job == null) break;
                runOne(job);
            }
        } finally {
            synchronized (TransferService.class) {
                workerRunning = false;
            }
            stopSelf();
        }
    }

    private void runOne(TransferJob job) {
        job.state = TransferJob.RUNNING;
        job.startedMs = System.currentTimeMillis();
        notifyProgress("正在复制 " + job.name, job);
        try {
            RemoteConfig srcCfg = job.srcType == TransferJob.REMOTE
                    ? RemoteConfigs.find(this, job.srcOwner) : null;
            RemoteConfig dstCfg = job.dstType == TransferJob.REMOTE
                    ? RemoteConfigs.find(this, job.dstOwner) : null;
            if (job.srcType == TransferJob.REMOTE && srcCfg == null) {
                throw new java.io.IOException("源服务器配置已删除");
            }
            if (job.dstType == TransferJob.REMOTE && dstCfg == null) {
                throw new java.io.IOException("目标服务器配置已删除");
            }

            Transfer.Src src = job.srcType == TransferJob.LOCAL
                    ? Transfer.localSource(this, Uri.parse(job.srcOwner), job.name, job.size)
                    : Transfer.remoteSource(srcCfg, job.srcPath, job.name, job.size);
            Transfer.Dst dst = job.dstType == TransferJob.LOCAL
                    ? Transfer.localDest(this, Uri.parse(job.dstOwner), job.dstPath,
                    conflictOf(job.conflict))
                    : Transfer.remoteDest(dstCfg, job.dstPath, conflictOf(job.conflict));

            final long[] lastNotify = {0};
            String saved = Transfer.copy(src, dst, (done, total) -> {
                job.done = done;
                long now = System.currentTimeMillis();
                // 每 256KB 回调一次太密，通知栏扛不住，压到 800ms 一次
                if (now - lastNotify[0] > 800) {
                    lastNotify[0] = now;
                    notifyProgress("正在复制 " + job.name + " · " + job.percent() + "%", job);
                }
                return !job.cancelRequested;
            });
            if (job.cancelRequested) {
                job.state = TransferJob.CANCELLED;
            } else if (saved == null) {
                job.state = TransferJob.OK;
                job.message = "目标已存在，已跳过";
            } else {
                job.state = TransferJob.OK;
                job.message = "已保存为 " + saved;
            }
        } catch (Throwable t) {
            if (job.cancelRequested) {
                job.state = TransferJob.CANCELLED;
            } else {
                job.state = TransferJob.FAILED;
                job.message = t.getMessage() == null ? t.toString() : t.getMessage();
                Diag.e("Transfer", "复制失败 " + job.name, t);
            }
        } finally {
            job.endedMs = System.currentTimeMillis();
            notifyProgress(job.stateText() + " · " + job.name, job);
        }
    }

    private static Transfer.Conflict conflictOf(int v) {
        switch (v) {
            case 0: return Transfer.Conflict.SKIP;
            case 1: return Transfer.Conflict.OVERWRITE;
            default: return Transfer.Conflict.RENAME;
        }
    }

    // ------------------------------------------------------------------ 通知

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "文件复制",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("把素材复制到本机目录或 NAS");
        nm.createNotificationChannel(ch);
    }

    private void notifyProgress(String text, TransferJob job) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text, job));
    }

    private Notification buildNotification(String text, TransferJob job) {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle("OSV 复制")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(job != null && !job.finished());
        if (job != null && job.size > 0) {
            b.setProgress(100, job.percent(), false);
        }
        return b.build();
    }

    // ------------------------------------------------------------------ 启动

    /** 把任务交给服务；调用方负责先确认目录权限还在。 */
    public static void enqueue(Context c, TransferJob job) {
        Intent i = new Intent(c, TransferService.class);
        i.putExtra(EXTRA_JOB, job);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }
}
