package com.osmo.viewer;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;

import java.nio.ByteBuffer;

/**
 * 读 OSV 内嵌的 jaw 元数据，取相机的绝对姿态来定画面初始的「垂直方向」。
 *
 * djmd 轨里每帧一条 protobuf（dvtm_oq101.proto）：
 *   ProductMeta.frame_meta(3) -> FrameMeta.camera_frame_meta(2)
 *     -> FrameMetaOfCamera.camera_attitude(9) = Quaternion(w=1, x=2, y=3, z=4)
 *
 * 该四元数是 IMU 系（x 右、y 光轴、z 上）到世界系（Z 轴向上）的旋转。
 * 转换到视频的光学坐标系（x 右、y 下、z 前）后，重力方向的水平分量就给出
 * 画面需要转多少度才能摆正——相机倒装时正好是 180°。
 */
public final class OsvMeta {

    private static final String TAG = "OsvMeta";
    private static final int MAX_FRAMES = 45;
    private static final int SAMPLE_BYTES = 16 * 1024;

    private OsvMeta() {}

    /** 返回把地平线摆平所需的 roll（弧度，负值表示画面逆时针转）；无数据时 NaN。 */
    public static float readInitialRoll(Context ctx, Uri uri) {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(ctx, uri, null);
            int count = ex.getTrackCount();
            ByteBuffer buf = ByteBuffer.allocateDirect(SAMPLE_BYTES);

            for (int i = 0; i < count; i++) {
                MediaFormat fmt = ex.getTrackFormat(i);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                if (mime == null || mime.startsWith("video/") || mime.startsWith("audio/")) {
                    continue;
                }
                float roll = rollFromTrack(ex, i, buf);
                if (!Float.isNaN(roll)) return roll;
            }
        } catch (Throwable t) {
            Log.w(TAG, "读取姿态失败: " + uri, t);
        } finally {
            try { ex.release(); } catch (Exception ignored) {}
        }
        return Float.NaN;
    }

    private static float rollFromTrack(MediaExtractor ex, int track, ByteBuffer buf) {
        float[] rolls = new float[MAX_FRAMES];
        int found = 0;
        try {
            ex.selectTrack(track);
            for (int i = 0; i < MAX_FRAMES * 3 && found < MAX_FRAMES; i++) {
                buf.clear();
                int size = ex.readSampleData(buf, 0);
                if (size <= 0) break;
                byte[] data = new byte[size];
                buf.position(0);
                buf.get(data, 0, size);
                float[] q = frameAttitude(data);
                if (q != null) {
                    float roll = rollOf(q);
                    if (!Float.isNaN(roll)) rolls[found++] = roll;
                }
                if (!ex.advance()) break;
            }
            ex.unselectTrack(track);
        } catch (Throwable t) {
            Log.w(TAG, "轨道 " + track + " 解析失败", t);
            return Float.NaN;
        }
        if (found == 0) return Float.NaN;
        java.util.Arrays.sort(rolls, 0, found);
        return rolls[found / 2];   // 中位数，避开起手那几帧的抖动
    }

    // ------------------------------------------------------------------ protobuf

    /** 从一条 djmd 样本里取出 camera_attitude；不是帧样本时返回 null。 */
    static float[] frameAttitude(byte[] data) {
        Reader top = new Reader(data, 0, data.length);
        while (top.next()) {
            if (top.field() != 3 || top.wire() != 2) continue;
            Reader frame = top.sub();
            while (frame.next()) {
                if (frame.field() != 2 || frame.wire() != 2) continue;
                Reader cam = frame.sub();
                while (cam.next()) {
                    if (cam.field() != 9 || cam.wire() != 2) continue;
                    Reader quat = cam.sub();
                    float[] q = {1f, 0f, 0f, 0f};
                    boolean any = false;
                    while (quat.next()) {
                        int f = quat.field();
                        if (quat.wire() == 5 && f >= 1 && f <= 4) {
                            q[f - 1] = quat.float32();
                            any = true;
                        }
                    }
                    if (any) return q;
                }
            }
        }
        return null;
    }

    /**
     * 由姿态四元数求画面需要校正的 roll。
     *
     * 注意这里比「重力方向作 up」多转了 180°：摄像机把 cam0 的画面存成 y 朝上的方向，
     * 而着色器里的相机系是 y 朝下，两者差一个绕光轴的半圈。实测（把真实鱼眼帧按
     * 不同角度重投影后目视核对）不带这半圈时所有素材都是倒的。
     */
    static float rollOf(float[] q) {
        float w = q[0], x = q[1], y = q[2], z = q[3];
        // R 的第三列 = 相机 z 轴在世界中的方向；世界 Z 向上，重力 = (0,0,-1)
        float c0 = 2f * (x * z + w * y);
        float c1 = 2f * (y * z - w * x);
        float c2 = 1f - 2f * (x * x + y * y);
        float gx = -c0, gy = -c1, gz = -c2;

        // IMU(x右,y前,z上) -> 光学系(x右,y下,z前)
        float ox = gx, oy = -gz;

        float horizontal = (float) Math.sqrt(ox * ox + oy * oy);
        if (horizontal < 0.05f) return Float.NaN;   // 光轴接近天顶/地面，roll 没有意义
        return (float) Math.atan2(ox, -oy);
    }

    /** 极简 protobuf 读取器，只认 varint / length-delimited / fixed32。 */
    static final class Reader {
        private final byte[] b;
        private final int end;
        private int pos;
        private int field, wire, valueStart, valueLen;

        Reader(byte[] b, int start, int end) {
            this.b = b;
            this.pos = start;
            this.end = end;
        }

        int field() { return field; }

        int wire() { return wire; }

        Reader sub() {
            return new Reader(b, valueStart, valueStart + valueLen);
        }

        float float32() {
            int bits = (b[valueStart] & 0xff)
                    | ((b[valueStart + 1] & 0xff) << 8)
                    | ((b[valueStart + 2] & 0xff) << 16)
                    | ((b[valueStart + 3] & 0xff) << 24);
            return Float.intBitsToFloat(bits);
        }

        boolean next() {
            if (pos >= end) return false;
            long key;
            try {
                key = varint();
            } catch (RuntimeException e) {
                pos = end;
                return false;
            }
            field = (int) (key >>> 3);
            wire = (int) (key & 7);
            try {
                switch (wire) {
                    case 0:
                        varint();
                        return true;
                    case 1:
                        pos += 8;
                        return pos <= end;
                    case 2: {
                        long len = varint();
                        if (len < 0 || pos + len > end) {
                            pos = end;
                            return false;
                        }
                        valueStart = pos;
                        valueLen = (int) len;
                        pos += (int) len;
                        return true;
                    }
                    case 5:
                        valueStart = pos;
                        valueLen = 4;
                        pos += 4;
                        return pos <= end;
                    default:
                        pos = end;
                        return false;
                }
            } catch (RuntimeException e) {
                pos = end;
                return false;
            }
        }

        private long varint() {
            long value = 0;
            int shift = 0;
            while (true) {
                if (pos >= end) throw new RuntimeException("truncated");
                int c = b[pos++] & 0xff;
                value |= (long) (c & 0x7f) << shift;
                if ((c & 0x80) == 0) return value;
                shift += 7;
                if (shift > 63) throw new RuntimeException("varint too long");
            }
        }
    }
}
