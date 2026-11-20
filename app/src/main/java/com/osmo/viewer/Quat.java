package com.osmo.viewer;

/**
 * 极简四元数工具。约定：
 *   - 相机坐标系 x 右、y 下、z 前（与着色器里的 d0 一致）
 *   - 视图旋转 q 表示「世界方向 = R(q) * 相机方向」
 *   - 四元数按 (w, x, y, z) 存放
 */
public final class Quat {

    private Quat() {}

    public static void identity(float[] q) {
        q[0] = 1f;
        q[1] = q[2] = q[3] = 0f;
    }

    public static void copy(float[] dst, float[] src) {
        System.arraycopy(src, 0, dst, 0, 4);
    }

    public static void normalize(float[] q) {
        float n = (float) Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        if (n < 1e-8f) {
            identity(q);
            return;
        }
        q[0] /= n;
        q[1] /= n;
        q[2] /= n;
        q[3] /= n;
    }

    /** out = a * b（先应用 b，再应用 a 的旋转）。允许 out 与 a/b 相同。 */
    public static void mul(float[] out, float[] a, float[] b) {
        float w = a[0] * b[0] - a[1] * b[1] - a[2] * b[2] - a[3] * b[3];
        float x = a[0] * b[1] + a[1] * b[0] + a[2] * b[3] - a[3] * b[2];
        float y = a[0] * b[2] - a[1] * b[3] + a[2] * b[0] + a[3] * b[1];
        float z = a[0] * b[3] + a[1] * b[2] - a[2] * b[1] + a[3] * b[0];
        out[0] = w;
        out[1] = x;
        out[2] = y;
        out[3] = z;
    }

    public static void conj(float[] out, float[] q) {
        out[0] = q[0];
        out[1] = -q[1];
        out[2] = -q[2];
        out[3] = -q[3];
    }

    /** 绕任意轴（不必单位化）转 angle 弧度。 */
    public static void fromAxisAngle(float[] out, float ax, float ay, float az, float angle) {
        float len = (float) Math.sqrt(ax * ax + ay * ay + az * az);
        if (len < 1e-6f || angle == 0f) {
            identity(out);
            return;
        }
        float half = angle * 0.5f;
        float s = (float) Math.sin(half) / len;
        out[0] = (float) Math.cos(half);
        out[1] = ax * s;
        out[2] = ay * s;
        out[3] = az * s;
    }

    /**
     * 一阶球面插值：out = a → b 走 t（t=0 得 a，t=1 得 b）。
     *
     * 相邻帧的 q 与 -q 是同一个姿态，先按点积对齐半球再插值，否则会在 ±180° 处翻面。
     */
    public static void nlerp(float[] out, float[] a, float[] b, float t) {
        float dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
        float sign = dot < 0 ? -1f : 1f;
        for (int i = 0; i < 4; i++) {
            out[i] = a[i] + (sign * b[i] - a[i]) * t;
        }
        normalize(out);
    }

    /** Ry(yaw) * Rx(pitch)：无滚转的姿态，即地平线始终水平。 */
    public static void fromYawPitch(float[] out, float yaw, float pitch) {
        float qy[] = new float[4];
        float qx[] = new float[4];
        float cy = (float) Math.cos(yaw * 0.5f), sy = (float) Math.sin(yaw * 0.5f);
        float cp = (float) Math.cos(pitch * 0.5f), sp = (float) Math.sin(pitch * 0.5f);
        qy[0] = cy; qy[1] = 0f; qy[2] = sy; qy[3] = 0f;
        qx[0] = cp; qx[1] = sp; qx[2] = 0f; qx[3] = 0f;
        mul(out, qy, qx);
    }

    /** 转 3×3 旋转矩阵（GL 需要的列主序）。 */
    public static void toMatrix(float[] q, float[] out9) {
        float w = q[0], x = q[1], y = q[2], z = q[3];
        float xx = x * x, yy = y * y, zz = z * z;
        float xy = x * y, xz = x * z, yz = y * z;
        float wx = w * x, wy = w * y, wz = w * z;

        // 行主序 R，再按列写入 out9
        float r00 = 1f - 2f * (yy + zz), r01 = 2f * (xy - wz), r02 = 2f * (xz + wy);
        float r10 = 2f * (xy + wz), r11 = 1f - 2f * (xx + zz), r12 = 2f * (yz - wx);
        float r20 = 2f * (xz - wy), r21 = 2f * (yz + wx), r22 = 1f - 2f * (xx + yy);

        out9[0] = r00; out9[1] = r10; out9[2] = r20;
        out9[3] = r01; out9[4] = r11; out9[5] = r21;
        out9[6] = r02; out9[7] = r12; out9[8] = r22;
    }

    /** 前向（相机 +z）在世界中的方位角。 */
    public static float yawOf(float[] q) {
        float w = q[0], x = q[1], y = q[2], z = q[3];
        float fwdX = 2f * (x * z + w * y);
        float fwdY = 2f * (y * z - w * x);
        float fwdZ = 1f - 2f * (x * x + y * y);
        float len = (float) Math.sqrt(fwdX * fwdX + fwdZ * fwdZ);
        if (len < 1e-6f) return 0f;
        return (float) Math.atan2(fwdX, fwdZ);
    }

    /** 前向的俯仰角（>0 抬头）。 */
    public static float pitchOf(float[] q) {
        float w = q[0], x = q[1], y = q[2], z = q[3];
        float fwdY = 2f * (y * z - w * x);
        return (float) Math.asin(Math.max(-1f, Math.min(1f, -fwdY)));
    }
}
