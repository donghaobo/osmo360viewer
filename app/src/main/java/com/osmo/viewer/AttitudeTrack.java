package com.osmo.viewer;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * 逐帧相机姿态轨道（来自 OSV 的 djmd 轨）。
 *
 * 每帧一条 protobuf，里面 frame_meta.camera_frame_meta.camera_attitude 是
 * IMU 系（x 右、y 光轴、z 上）到世界系（Z 向上）的四元数。这里把它读成
 * 一条「时间戳 → 姿态」的序列，播放时按位置插值，用于抵消拍摄时相机的转动。
 *
 * 采样上限：长视频全量下来内存和时间都吃不消，按估算的总帧数等间隔抽稀到
 * 两万帧（10Hz 左右仍足够平滑），时间戳用样本自己的，不假设固定帧率。
 */
public final class AttitudeTrack {

    private static final int MAX_SAMPLES = 20000;

    /**
     * IMU 系 → 光学系 的固定旋转（绕 x 转 90°）。
     *
     * IMU 系是 x 右、y 光轴、z 上；视频的光学系是 x 右、y 下、z 前，两者差一个绕 x 的
     * 90°，与 OsvMeta 里 `ox = gx, oy = -gz` 的换算同源。djmd 里的姿态是 IMU 系的，
     * 不换算直接当光学系的旋转用：俯仰（绕两者共有的 x 轴）碰巧还对，偏航会被当成滚转、
     * 滚转被当成偏航，补偿推到错误方向——真机上就是「开了稳像画面反而更抖」。
     *
     * 方向（+90° 而非 -90°）是拿真实素材比出来的：tools/verify_stabilizer_sign.py 把
     * 每个候选的补偿真渲染出来量残留位移，这一步把抖动残差从 4.13°（不补偿）降到 1.26°，
     * 方向取反则涨到 6.46°。
     */
    private static final float[] P_IMU2OPT = {0.70710678f, 0.70710678f, 0f, 0f};

    /** 把 IMU 系的姿态搬到光学系：q' = conj(p) ⊗ q ⊗ p，就地修改。 */
    static void toOptical(float[] q) {
        float[] inv = new float[4];
        Quat.conj(inv, P_IMU2OPT);
        Quat.mul(q, inv, q);
        Quat.mul(q, q, P_IMU2OPT);
        Quat.normalize(q);
    }
    /** 估算抽稀步长用的帧率；真实帧率不同只影响采样密度，不影响正确性。 */
    private static final float ASSUMED_FPS = 30f;

    private final long[] timesUs;
    private final float[] quats;   // 每帧 4 个分量：w, x, y, z
    private final int count;

    private AttitudeTrack(long[] timesUs, float[] quats, int count) {
        this.timesUs = timesUs;
        this.quats = quats;
        this.count = count;
    }

    public boolean usable() {
        return count >= 2;
    }

    public int size() {
        return count;
    }

    /** 第一帧的时间与姿态（作为稳定基准）。 */
    public long firstTimeUs() {
        return count == 0 ? 0 : timesUs[0];
    }

    public void firstQuat(float[] out) {
        if (count == 0) {
            Quat.identity(out);
            return;
        }
        System.arraycopy(quats, 0, out, 0, 4);
        Quat.normalize(out);
    }

    /**
     * 按时间取姿态（线性插值 + 归一化）。
     *
     * @return 是否取到；超出范围时用首/尾帧顶上，返回 false 只是为了提示。
     */
    public boolean quatAt(long timeUs, float[] out) {
        if (count == 0) {
            Quat.identity(out);
            return false;
        }
        if (timeUs <= timesUs[0]) {
            System.arraycopy(quats, 0, out, 0, 4);
            Quat.normalize(out);
            return timeUs == timesUs[0];
        }
        if (timeUs >= timesUs[count - 1]) {
            System.arraycopy(quats, (count - 1) * 4, out, 0, 4);
            Quat.normalize(out);
            return timeUs == timesUs[count - 1];
        }

        int lo = 0;
        int hi = count - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (timesUs[mid] <= timeUs) lo = mid;
            else hi = mid;
        }
        long t0 = timesUs[lo];
        long t1 = timesUs[hi];
        float span = (float) (t1 - t0);
        float f = span <= 0 ? 0f : (float) (timeUs - t0) / span;

        int a = lo * 4;
        int b = hi * 4;
        // 相邻帧的 q 与 -q 表示同一姿态；先对齐半球再插值，否则会在 ±180° 处翻过去
        float dot = quats[a] * quats[b] + quats[a + 1] * quats[b + 1]
                + quats[a + 2] * quats[b + 2] + quats[a + 3] * quats[b + 3];
        float sign = dot < 0 ? -1f : 1f;
        for (int i = 0; i < 4; i++) {
            out[i] = quats[a + i] + (sign * quats[b + i] - quats[a + i]) * f;
        }
        Quat.normalize(out);
        return true;
    }

    // ------------------------------------------------------------------ 加载

    /** 读 djmd 轨建立姿态序列；没有该轨或解析失败返回 null。 */
    public static AttitudeTrack load(Context ctx, Uri uri) {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(ctx, uri, null);
            int track = findDataTrack(ex);
            if (track < 0) return null;

            long durationUs = estimateDurationUs(ex);
            int step = 1;
            if (durationUs > 0) {
                long estFrames = (long) (durationUs / 1_000_000.0 * ASSUMED_FPS);
                step = (int) Math.max(1, estFrames / MAX_SAMPLES);
            }

            ex.selectTrack(track);
            ByteBuffer buf = ByteBuffer.allocateDirect(16 * 1024);
            ArrayList<long[]> times = new ArrayList<>();
            ArrayList<float[]> quats = new ArrayList<>();
            int index = 0;
            while (true) {
                long ts = ex.getSampleTime();
                if (ts < 0) break;
                if (index % step == 0) {
                    buf.clear();
                    int size = ex.readSampleData(buf, 0);
                    if (size > 0) {
                        byte[] data = new byte[size];
                        buf.position(0);
                        buf.get(data, 0, size);
                        float[] q = OsvMeta.frameAttitude(data);
                        if (q != null) {
                            toOptical(q);   // 统一换成光学系，之后直接当视角旋转用
                            times.add(new long[]{ts});
                            quats.add(q);
                        }
                    }
                }
                index++;
                if (!ex.advance()) break;
            }
            ex.unselectTrack(track);
            ex.release();

            int n = times.size();
            if (n < 2) return null;
            long[] t = new long[n];
            float[] q = new float[n * 4];
            for (int i = 0; i < n; i++) {
                t[i] = times.get(i)[0];
                System.arraycopy(quats.get(i), 0, q, i * 4, 4);
            }
            Diag.i("AttitudeTrack", "姿态轨道 " + n + " 帧 · 步长 " + step
                    + " · 时长 " + (durationUs / 1000) + "ms");
            return new AttitudeTrack(t, q, n);
        } catch (Throwable e) {
            Diag.w("AttitudeTrack", "读取姿态轨道失败", e);
            try { ex.release(); } catch (Exception ignored) {}
            return null;
        }
    }

    private static int findDataTrack(MediaExtractor ex) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat fmt = ex.getTrackFormat(i);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            if (mime == null || mime.startsWith("video/") || mime.startsWith("audio/")) {
                continue;
            }
            if (fmt.containsKey(MediaFormat.KEY_DURATION) && isLikelyMetaTrack(ex, i)) {
                return i;
            }
        }
        // 没有明显特征时退回第一条非音视频轨
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && !mime.startsWith("video/") && !mime.startsWith("audio/")) {
                return i;
            }
        }
        return -1;
    }

    /** OSV 里有多条 data 轨（djmd/dbgi/…），用首帧能否解析出姿态来区分。 */
    private static boolean isLikelyMetaTrack(MediaExtractor ex, int track) {
        ByteBuffer probe = ByteBuffer.allocateDirect(16 * 1024);
        try {
            ex.selectTrack(track);
            probe.clear();
            int size = ex.readSampleData(probe, 0);
            ex.unselectTrack(track);
            if (size <= 0) return false;
            byte[] data = new byte[size];
            probe.position(0);
            probe.get(data, 0, size);
            return OsvMeta.frameAttitude(data) != null;
        } catch (Throwable t) {
            try { ex.unselectTrack(track); } catch (Exception ignored) {}
            return false;
        }
    }

    private static long estimateDurationUs(MediaExtractor ex) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat fmt = ex.getTrackFormat(i);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")
                    && fmt.containsKey(MediaFormat.KEY_DURATION)) {
                return fmt.getLong(MediaFormat.KEY_DURATION);
            }
        }
        return 0;
    }
}
