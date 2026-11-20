package com.osmo.viewer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 缩略图：内存 LruCache + 磁盘 JPEG 缓存 + 后台解码，只保留最新解码结果。 */
public class ThumbLoader {

    public interface Callback {
        void onThumb(OsvItem item, Bitmap bmp);
    }

    private static final int THUMB_PX = 512;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    // 单线程：一张 3000×3000 帧解码峰值就几十 MB，并发两张容易撞上内存上限
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Set<String> inFlight = new HashSet<>();
    private final LruCache<String, Bitmap> mem;

    public ThumbLoader(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
        mem = new LruCache<String, Bitmap>(maxKb) {
            @Override
            protected int sizeOf(String k, Bitmap b) {
                return b.getByteCount() / 1024;
            }
        };
    }

    private static String key(Uri uri) {
        return Integer.toHexString(uri.toString().hashCode());
    }

    private File cacheFile(String key) {
        File dir = new File(ctx.getCacheDir(), "thumbs");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, key + ".jpg");
    }

    public void load(final OsvItem item, final Callback cb) {
        final String k = key(item.uri);
        Bitmap cached = mem.get(k);
        if (cached != null) {
            item.thumb = cached;
            cb.onThumb(item, cached);
            return;
        }
        synchronized (inFlight) {
            if (!inFlight.add(k)) return;
        }
        pool.execute(() -> {
            Bitmap bmp = decodeFromDisk(k);
            if (bmp == null) {
                bmp = loadThumb(item);
                if (bmp != null) saveToDisk(k, bmp);
            }
            synchronized (inFlight) {
                inFlight.remove(k);
            }
            if (bmp != null) mem.put(k, bmp);
            final Bitmap result = bmp;
            main.post(() -> {
                item.thumb = result;
                cb.onThumb(item, result);
            });
        });
    }

    public void close() {
        pool.shutdownNow();
        mem.evictAll();
    }

    /** 本地走 MediaMetadataRetriever(uri)，远程走 MediaDataSource（只读关键帧附近的数据）。 */
    private Bitmap loadThumb(OsvItem item) {
        String uriString = item.uri.toString();
        if (!RemoteConfigs.isRemote(uriString)) {
            return MediaProbe.thumbnail(ctx, item.uri, THUMB_PX);
        }
        RemoteStore store = null;
        RemoteFile file = null;
        try {
            RemoteConfig cfg = RemoteConfigs.find(ctx, RemoteConfigs.configIdOf(uriString));
            if (cfg == null) return null;
            store = RemoteStore.open(cfg);
            file = new RemoteFile(store, RemoteConfigs.pathOf(uriString));
            return MediaProbe.thumbnail(file, THUMB_PX);
        } catch (Throwable t) {
            Diag.w("ThumbLoader", "远程缩略图失败: " + item.name, t);
            return null;
        } finally {
            if (file != null) file.close();
            else if (store != null) {
                try { store.close(); } catch (Exception ignored) {}
            }
        }
    }

    private Bitmap decodeFromDisk(String k) {
        try {
            File f = cacheFile(k);
            if (!f.exists() || f.length() == 0) return null;
            return BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (Throwable t) {
            return null;
        }
    }

    private void saveToDisk(String k, Bitmap bmp) {
        try (FileOutputStream out = new FileOutputStream(cacheFile(k))) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 88, out);
        } catch (Throwable ignored) {
        }
    }
}
