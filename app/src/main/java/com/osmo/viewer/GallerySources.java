package com.osmo.viewer;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 相册的合并数据源：本机多个目录 + 勾选了「首页展示」的 NAS。
 *
 * 分两段回调，是为了让首页先亮起来：
 *   1. 本地扫完就回调一次（毫秒级，不含任何网络）；
 *   2. 每台 NAS 扫完再回调一次，界面增量刷新；
 *   3. 全部结束回调 done=true。
 *
 * 去重按文件名（大小写不敏感），**本机优先**：同名时本机那一份当主副本，
 * NAS 上的挂进 places，长按删除时能选到。
 */
public final class GallerySources {

    public interface Listener {
        /**
         * @param items 目前已知的全部素材（已去重）
         * @param done  所有来源都扫完了
         * @param note  这次变化来自哪里，给状态栏用
         */
        void onItems(List<OsvItem> items, boolean done, String note);
    }

    private GallerySources() {}

    /**
     * 在后台线程跑完整流程；回调切回主线程。
     *
     * @return 用于取消的句柄（切页面时置为 cancelled，别再往界面回调）
     */
    public static Cancelable load(Context ctx, Listener listener) {
        final Context app = ctx.getApplicationContext();
        final Handler ui = new Handler(Looper.getMainLooper());
        final Cancelable handle = new Cancelable();
        final Map<String, OsvItem> merged = new LinkedHashMap<>();

        new Thread(() -> {
            // ---- 第 1 段：本机
            List<OsvItem> local = new ArrayList<>();
            try {
                local = OsvLibrary.scan(app);
            } catch (Throwable t) {
                Diag.w("Gallery", "本地扫描失败", t);
            }
            if (handle.cancelled) return;
            synchronized (merged) {
                for (OsvItem it : local) put(merged, it);
            }
            List<OsvItem> snapshot = snapshot(merged);
            ui.post(() -> {
                if (!handle.cancelled) listener.onItems(snapshot, false, "本机");
            });

            // ---- 第 2 段：勾选了首页展示的 NAS，一台一个线程并行扫
            List<RemoteConfig> remotes = new ArrayList<>();
            for (RemoteConfig cfg : RemoteConfigs.load(app)) {
                if (cfg.showInGallery) remotes.add(cfg);
            }
            if (remotes.isEmpty()) {
                ui.post(() -> {
                    if (!handle.cancelled) listener.onItems(snapshot(merged), true, "本机");
                });
                return;
            }

            final AtomicInteger left = new AtomicInteger(remotes.size());
            final ExecutorService pool = Executors.newFixedThreadPool(
                    Math.min(3, remotes.size()));
            for (final RemoteConfig cfg : remotes) {
                pool.execute(() -> {
                    List<OsvItem> found = new ArrayList<>();
                    RemoteStore store = null;
                    try {
                        store = RemoteStore.open(cfg);
                        found = RemoteScanner.scan(cfg, store, () -> handle.cancelled);
                    } catch (Throwable t) {
                        Diag.w("Gallery", "扫描 NAS 失败：" + RemoteScanner.labelOf(cfg), t);
                    } finally {
                        if (store != null) {
                            try { store.close(); } catch (Exception ignored) {}
                        }
                    }
                    if (handle.cancelled) return;
                    synchronized (merged) {
                        for (OsvItem it : found) put(merged, it);
                    }
                    List<OsvItem> snap = snapshot(merged);
                    boolean last = left.decrementAndGet() == 0;
                    String note = RemoteScanner.labelOf(cfg) + " " + found.size() + " 个";
                    ui.post(() -> {
                        if (!handle.cancelled) listener.onItems(snap, last, note);
                    });
                });
            }
            pool.shutdown();
        }, "osv-gallery").start();

        return handle;
    }

    /**
     * 同名合并：只进主副本，其余位置追加到 places。
     * 调用方持有 merged 的锁。
     */
    private static void put(Map<String, OsvItem> merged, OsvItem item) {
        String key = item.dedupeKey();
        OsvItem exist = merged.get(key);
        if (exist == null) {
            merged.put(key, item);
            return;
        }
        for (OsvItem.Place p : item.places) {
            boolean dup = false;
            for (OsvItem.Place q : exist.places) {
                if (q.type == p.type && q.path.equals(p.path)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) exist.places.add(p);
        }
    }

    private static List<OsvItem> snapshot(Map<String, OsvItem> merged) {
        synchronized (merged) {
            return new ArrayList<>(merged.values());
        }
    }

    /** 简单的取消句柄：页面销毁时置位，避免回调打到已经结束的界面。 */
    public static final class Cancelable {
        volatile boolean cancelled;

        public void cancel() {
            cancelled = true;
        }
    }
}
