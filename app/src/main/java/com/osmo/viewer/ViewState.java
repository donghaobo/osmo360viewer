package com.osmo.viewer;

/**
 * 播放页共享状态：UI 线程写，GL 线程读。
 *
 * 视角用四元数而不是 yaw/pitch：欧拉角在天顶处会把水平拖动放大成极快转动，
 * 而且 pitch 必须夹在 ±90°，过不去极点。四元数按屏幕轴做局部旋转，
 * 任意方向下角速度都与手指位移成正比，也能自然越过天顶。
 */
public class ViewState {

    public static final float FOV_MIN = 30f;
    public static final float FOV_MAX = 150f;

    /**
     * 画面的基础滚转：摄像机把 cam0 存成 y 朝上的方向，与相机系差半圈。
     * 这是默认姿态的一部分（构造时就是它），不是运行时"某一刻"才加上的补丁，
     * 否则第一帧到姿态数据到达之间，画面会是倒的。
     */
    public static final float BASE_ROLL = (float) Math.PI;

    private final float[] q = {1f, 0f, 0f, 0f};
    private final float[] tmp = new float[4];
    /**
     * 稳像修正（世界系旋转），由播放进度驱动逐帧更新。
     * 用左乘叠加在用户视角上，这样用户拖动/体感转动视角不会被它覆盖。
     */
    private final float[] stab = {1f, 0f, 0f, 0f};
    private final float[] total = new float[4];

    public ViewState() {
        setRoll(BASE_ROLL);
    }

    public volatile float fov = 90f;
    public volatile boolean equirect;
    public volatile float featherDeg = 4f;
    public volatile float seamDeg = 0f;
    public volatile float poleCut = 0f;
    public volatile int vidW = 3000, vidH = 3000;

    /** 绕相机视线轴旋转，把地平线摆平（roll 为弧度）。符号与 tools/render_view.py 的 --roll 一致。 */
    public synchronized void setRoll(float rollRad) {
        float half = rollRad * 0.5f;
        q[0] = (float) Math.cos(half);
        q[1] = 0f;
        q[2] = 0f;
        q[3] = (float) Math.sin(half);
    }

    /** 叠加一次「屏幕坐标系」的局部旋转（轴在相机系里给）。 */
    public synchronized void rotateLocal(float ax, float ay, float az, float angle) {
        if (angle == 0f) return;
        Quat.fromAxisAngle(tmp, ax, ay, az, angle);
        // 右乘：绕相机自身的轴转，屏幕上的角速度才与手指位移成正比；
        // 左乘是绕固定世界轴转，视角朝天顶时会再次出现「越转越快」。
        Quat.mul(q, q, tmp);
        Quat.normalize(q);
    }

    /** 设置稳像修正；传 null 或单位四元数表示不修正。 */
    public synchronized void setStabilizer(float[] value) {
        if (value == null) {
            Quat.identity(stab);
            return;
        }
        Quat.copy(stab, value);
        Quat.normalize(stab);
    }

    public synchronized void clearStabilizer() {
        Quat.identity(stab);
    }

    public synchronized void setQuat(float[] value) {
        Quat.copy(q, value);
        Quat.normalize(q);
    }

    public synchronized void getQuat(float[] out) {
        Quat.copy(out, q);
    }

    public synchronized void reset() {
        setRoll(BASE_ROLL);
        fov = 90f;
    }

    /** 列主序 3×3，直接喂 glUniformMatrix3fv。稳像修正左乘在用户视角上。 */
    public synchronized void getMatrix(float[] out9) {
        Quat.mul(total, stab, q);
        Quat.normalize(total);
        Quat.toMatrix(total, out9);
    }

    /** 最终视角（含稳像修正），诊断/显示用。 */
    public synchronized void getTotalQuat(float[] out) {
        Quat.mul(total, stab, q);
        Quat.normalize(total);
        Quat.copy(out, total);
    }

    public synchronized float yaw() {
        Quat.mul(total, stab, q);
        Quat.normalize(total);
        return Quat.yawOf(total);
    }

    public synchronized float pitch() {
        return Quat.pitchOf(q);
    }

    public void clampFov() {
        fov = Math.max(FOV_MIN, Math.min(FOV_MAX, fov));
    }
}
