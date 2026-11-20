package com.osmo.viewer;

import java.util.function.LongSupplier;

/**
 * 稳像：把「拍摄时相机怎么转的」从画面里抵消掉。
 *
 * 由渲染线程在取视图矩阵之前调用 {@link #update(long)}，时刻就是**正在画的这一帧**的
 * PTS——不经过 UI 定时器，没有一帧以上的相位误差。（v0.9.1 之前用 16ms 定时器 + 墙钟
 * 位置，补偿总是比画面晚一拍，真机上看是「先抖过去、再抖回来」。）
 *
 * 修正量 = conj(q(t)) · q(0)：相机往哪边转，画面就往反方向转同样的角度。
 * 用户拖动/体感改的是 ViewState 里的用户视角，这个修正左乘在上面，不会把它们吃掉。
 */
final class Stabilizer {

    private final ViewState view;
    private StabSettings settings;
    /** 播放器时钟（墙钟插值），当显示帧 PTS 拿不到、或用户选了播放器时钟时用。 */
    private LongSupplier clock;

    private AttitudeTrack track;
    private boolean enabled = true;

    /** 基准姿态：第一帧（相对量的锚点）。 */
    private final float[] base = {1f, 0f, 0f, 0f};
    private final float[] now = new float[4];
    private final float[] tmp = new float[4];
    private final float[] target = new float[4];
    private final float[] smooth = {1f, 0f, 0f, 0f};
    private final float[] ident = {1f, 0f, 0f, 0f};

    private boolean ready;
    private long lastPosUs = -1;

    Stabilizer(ViewState view) {
        this.view = view;
    }

    void setSettings(StabSettings s) {
        settings = s;
    }

    void setClock(LongSupplier c) {
        clock = c;
    }

    void setEnabled(boolean on) {
        enabled = on;
        if (!on) view.clearStabilizer();
        reset();
    }

    boolean hasTrack() {
        return track != null && track.usable();
    }

    void setTrack(AttitudeTrack t) {
        track = t;
        if (t != null && t.usable()) t.firstQuat(base);
        else Quat.identity(base);
        reset();
    }

    /** 参数变了、跳转了、重新加载素材时调用：下一帧直接对齐，不要平滑着追过去。 */
    void reset() {
        ready = false;
        lastPosUs = -1;
    }

    /**
     * 按当前显示帧更新修正。渲染线程每帧调用，不分配内存。
     *
     * @param ptsUs 正在画的那一帧的媒体时间（微秒）；&lt;= 0 表示未知
     */
    void update(long ptsUs) {
        if (!enabled || track == null || !track.usable()) return;
        StabSettings s = settings;
        long pos = ptsUs;
        if (pos <= 0 || (s != null && !s.useDisplayPts)) {
            pos = clock == null ? 0 : clock.getAsLong();
        }
        if (pos <= 0) return;
        // 姿态数据可能比画面晚一点出来：把查表时间往回拨（面板上的「姿态延时」）
        if (s != null) pos -= s.delayMs * 1000L;
        if (pos < 0) pos = 0;
        if (pos == lastPosUs) return;   // 60Hz 刷新画 30fps 素材，同一帧不必重算

        boolean jump = lastPosUs < 0 || Math.abs(pos - lastPosUs) > 400_000;
        lastPosUs = pos;

        track.quatAt(pos, now);
        Quat.conj(tmp, now);
        Quat.mul(target, tmp, base);

        // 强度不满 100% 时，把修正缩到「单位 -> 目标」之间
        float gain = s == null ? 1f : s.gain();
        if (gain < 0.999f) Quat.nlerp(target, ident, target, gain);

        float alpha = s == null ? 1f : s.alpha();
        if (jump || !ready || alpha >= 0.999f) Quat.copy(smooth, target);
        else Quat.nlerp(smooth, smooth, target, alpha);
        ready = true;

        view.setStabilizer(smooth);
    }
}
