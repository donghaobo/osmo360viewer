package com.osmo.viewer;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * 设备朝向（绝对姿态，非陀螺积分，不会漂移）。
 *
 * 用设备背面的方向（后置摄像头朝向）作为视线：把 device->world 矩阵的第三列取反。
 * 只输出相对"北"的方位角与俯仰角，由播放页做相对量映射，所以不需要磁力计校准，
 * TYPE_GAME_ROTATION_VECTOR（重力 + 陀螺融合）即可。
 */
public class SensorOrientation implements SensorEventListener {

    public interface Listener {
        /** @param yawRad 方位角（绕重力轴），@param pitchRad 俯仰角（>0 抬头） */
        void onDirection(float yawRad, float pitchRad);
    }

    private final SensorManager sm;
    private final Sensor sensor;
    private final Listener listener;
    private final float[] R = new float[9];
    private final float[] smoothed = {0f, 0f, -1f};
    private boolean primed;
    private boolean registered;

    public SensorOrientation(Context ctx, Listener listener) {
        this.sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        Sensor s = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (s == null && sm != null) s = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        this.sensor = s;
        this.listener = listener;
    }

    public boolean available() {
        return sensor != null;
    }

    public void start() {
        if (sensor == null || registered) return;
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
        registered = true;
        primed = false;
    }

    public void stop() {
        if (sm != null && registered) {
            sm.unregisterListener(this);
            registered = false;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float[] q = event.values;
        if (q.length < 4) return;
        SensorManager.getRotationMatrixFromVector(R, q);

        // 设备背面方向 = -z 轴；世界系 x 东、y 北、z 上
        float lx = -R[2], ly = -R[5], lz = -R[8];
        float len = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
        if (len < 1e-5f) return;
        lx /= len;
        ly /= len;
        lz /= len;

        if (!primed) {
            smoothed[0] = lx;
            smoothed[1] = ly;
            smoothed[2] = lz;
            primed = true;
        } else {
            final float a = 0.25f;  // 低通，抑制手持抖动
            smoothed[0] += (lx - smoothed[0]) * a;
            smoothed[1] += (ly - smoothed[1]) * a;
            smoothed[2] += (lz - smoothed[2]) * a;
            float l2 = (float) Math.sqrt(smoothed[0] * smoothed[0]
                    + smoothed[1] * smoothed[1] + smoothed[2] * smoothed[2]);
            if (l2 > 1e-5f) {
                smoothed[0] /= l2;
                smoothed[1] /= l2;
                smoothed[2] /= l2;
            }
        }

        float yaw = (float) Math.atan2(smoothed[0], smoothed[1]);
        float pitch = (float) Math.asin(Math.max(-1f, Math.min(1f, smoothed[2])));
        listener.onDirection(yaw, pitch);
    }

    @Override
    public void onAccuracyChanged(Sensor s, int accuracy) {}
}
