package com.osmo.viewer;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

/** 用系统 demuxer 探测 OSV 的轨道结构，并生成相册缩略图。 */
public final class MediaProbe {

    private static final String TAG = "MediaProbe";

    public static class Result {
        public long durationMs;
        public int width;
        public int height;
        public int videoTracks;
        public int audioTracks;
        public boolean ok;
        /** 元数据里的拍摄时间（毫秒），没有则 0。 */
        public long dateMillis;
    }

    private MediaProbe() {}

    /**
     * OSV 是标准 ISOBMFF，MediaExtractor 靠内容嗅探即可解析，与扩展名无关。
     * 视频轨按出现顺序编号，0 = cam0(master)，1 = cam1(slave)。
     */
    /** 远程文件的探测：用 MediaDataSource，不需要先下载整个文件。 */
    public static Result probe(RemoteFile remote) {
        Result r = new Result();
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(remote.dataSource());
            fillFromExtractor(ex, r);
        } catch (Exception e) {
            Log.w(TAG, "remote probe failed", e);
        } finally {
            try { ex.release(); } catch (Exception ignored) {}
        }
        if (r.durationMs == 0 || r.width == 0) {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(remote.dataSource());
                fillFromRetriever(mmr, r);
            } catch (Exception e) {
                Log.w(TAG, "remote retriever probe failed", e);
            } finally {
                try { mmr.release(); } catch (Exception ignored) {}
            }
        }
        return r;
    }

    public static Result probe(Context ctx, Uri uri) {
        Result r = new Result();
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(ctx, uri, null);
            int n = ex.getTrackCount();
            for (int i = 0; i < n; i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (mime.startsWith("video/") && !"video/mjpeg".equals(mime)) {
                    if (r.videoTracks == 0) {
                        r.width = f.getInteger(MediaFormat.KEY_WIDTH);
                        r.height = f.getInteger(MediaFormat.KEY_HEIGHT);
                        if (f.containsKey(MediaFormat.KEY_DURATION)) {
                            r.durationMs = f.getLong(MediaFormat.KEY_DURATION) / 1000;
                        }
                    }
                    r.videoTracks++;
                } else if (mime.startsWith("audio/")) {
                    r.audioTracks++;
                }
            }
            r.ok = r.videoTracks > 0;
        } catch (Exception e) {
            Log.w(TAG, "extractor probe failed: " + uri, e);
        } finally {
            try { ex.release(); } catch (Exception ignored) {}
        }
        if (r.durationMs == 0) {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(ctx, uri);
                String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (d != null) r.durationMs = Long.parseLong(d);
                if (r.width == 0) {
                    r.width = parseInt(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                    r.height = parseInt(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                }
                r.dateMillis = ItemDate.parseMeta(
                        mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE));
                r.ok = true;
            } catch (Exception e) {
                Log.w(TAG, "retriever probe failed: " + uri, e);
            } finally {
                try { mmr.release(); } catch (Exception ignored) {}
            }
        }
        return r;
    }

    private static void fillFromExtractor(MediaExtractor ex, Result r) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime == null) continue;
            if (mime.startsWith("video/") && !"video/mjpeg".equals(mime)) {
                if (r.videoTracks == 0) {
                    r.width = f.getInteger(MediaFormat.KEY_WIDTH);
                    r.height = f.getInteger(MediaFormat.KEY_HEIGHT);
                    if (f.containsKey(MediaFormat.KEY_DURATION)) {
                        r.durationMs = f.getLong(MediaFormat.KEY_DURATION) / 1000;
                    }
                }
                r.videoTracks++;
            } else if (mime.startsWith("audio/")) {
                r.audioTracks++;
            }
        }
        r.ok = r.videoTracks > 0;
    }

    private static void fillFromRetriever(MediaMetadataRetriever mmr, Result r) {
        String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
        if (d != null) r.durationMs = Long.parseLong(d);
        if (r.width == 0) {
            r.width = parseInt(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            r.height = parseInt(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
        }
        r.dateMillis = ItemDate.parseMeta(
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE));
        r.ok = true;
    }

    /** 远程缩略图：只读文件头/关键帧附近的数据，不下载整段视频。 */
    public static Bitmap thumbnail(RemoteFile remote, int maxSize) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(remote.dataSource());
            Bitmap bmp = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                bmp = mmr.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        maxSize, maxSize);
            }
            if (bmp == null) bmp = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            return bmp;
        } catch (Throwable t) {
            Log.w(TAG, "remote thumbnail failed", t);
            return null;
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
        }
    }

    private static int parseInt(String s) {
        try { return s == null ? 0 : Integer.parseInt(s); } catch (Exception e) { return 0; }
    }

    /** 缩略图：取第一视频轨首个同步帧（cam0 的鱼眼圆），缩放到卡片尺寸。 */
    public static Bitmap thumbnail(Context ctx, Uri uri, int maxSize) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(ctx, uri);
            Bitmap bmp = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                bmp = mmr.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        maxSize, maxSize);
            }
            if (bmp == null) {
                bmp = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (bmp != null && Math.max(bmp.getWidth(), bmp.getHeight()) > maxSize) {
                    float s = (float) maxSize / Math.max(bmp.getWidth(), bmp.getHeight());
                    Bitmap scaled = Bitmap.createScaledBitmap(bmp,
                            Math.round(bmp.getWidth() * s), Math.round(bmp.getHeight() * s), true);
                    if (scaled != bmp) bmp.recycle();
                    bmp = scaled;
                }
            }
            return bmp;
        } catch (Throwable t) {
            Log.w(TAG, "thumbnail failed: " + uri, t);
            return null;
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
        }
    }
}
