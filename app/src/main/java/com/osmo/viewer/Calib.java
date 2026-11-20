package com.osmo.viewer;

/**
 * 双鱼眼标定（DJI Studio DualStitcher custom_0.json，已换算到 3000×3000 画幅）。
 *
 * 这里用 UCM（Unified Camera Model）而不是内嵌的 OpenCV 鱼眼多项式：后者
 * r(θ) 在 θ≈92.5° 到顶后回落，取不到接缝所在的 90°~100° 区间。
 */
public class Calib {

    public static final float BASE = 3000f;

    /** 每颗镜头一套：fx, fy, cx, cy, k1, k2, p1, p2, xi, rmax。 */
    public final float[] cam0 = {1388.696f, 1388.755f, 1483.969f, 1510.087f,
            -0.182932f, 0.018311f, -5.93245e-05f, 0.0004022920f, 0.717254f, 1673f};
    public final float[] cam1 = {1388.169f, 1388.309f, 1494.305f, 1505.195f,
            -0.183040f, 0.018367f, 0.000107692f, 0.000155120f, 0.712920f, 1676f};

    /** cam0 -> cam1 的相对旋转（≈180° 绕 Y，两镜头背靠背），行主序。 */
    public final float[] rot = {
            -0.9999521276053416f, -9.011589936091375e-05f, -0.009784394548200492f,
            -0.00023804281786328152f, 0.9998856799076945f, 0.015118546594973989f,
            0.009781913573889153f, 0.015120151938794074f, -0.9998378344372554f};

    /** cam1 相对 cam0 的逐通道曝光增益（重叠区中调）。 */
    public float[] gain = {0.965f, 0.998f, 0.977f};

    /** 接缝羽化带宽（度）。 */
    public float featherDeg = 4.0f;
    /** 接缝偏移（度）：把混合中心从 θ=90° 推开一点。 */
    public float seamDeg = 0f;
    /** >0 时，极点帽区改用硬边（0 = 关闭）。 */
    public float poleCut = 0f;

    /** 视频码流尺寸与标定基准尺寸之比。 */
    public static float scaleFor(int videoWidth) {
        return videoWidth / BASE;
    }
}
