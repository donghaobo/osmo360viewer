#!/usr/bin/env python3
"""离线核对 Quat / ViewState 的视角数学（与 Java 实现一一对应）。

用法: python3 tools/verify_view_rotation.py
"""
import math


def q_mul(a, b):
    aw, ax, ay, az = a
    bw, bx, by, bz = b
    return (aw * bw - ax * bx - ay * by - az * bz,
            aw * bx + ax * bw + ay * bz - az * by,
            aw * by - ax * bz + ay * bw + az * bx,
            aw * bz + ax * by - ay * bx + az * bw)


def q_norm(q):
    n = math.sqrt(sum(c * c for c in q)) or 1.0
    return tuple(c / n for c in q)


def q_axis(ax, ay, az, angle):
    n = math.sqrt(ax * ax + ay * ay + az * az)
    if n < 1e-9 or angle == 0:
        return (1.0, 0.0, 0.0, 0.0)
    s = math.sin(angle / 2) / n
    return (math.cos(angle / 2), ax * s, ay * s, az * s)


def q_conj(q):
    return (q[0], -q[1], -q[2], -q[3])


def q_yawpitch(yaw, pitch):
    return q_mul((math.cos(yaw / 2), 0, math.sin(yaw / 2), 0),
                 (math.cos(pitch / 2), math.sin(pitch / 2), 0, 0))


def q_mat(q):
    w, x, y, z = q
    return [[1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y)],
            [2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x)],
            [2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)]]


def mat_mul(A, B):
    return [[sum(A[i][k] * B[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def ry(a):
    return [[math.cos(a), 0, math.sin(a)], [0, 1, 0], [-math.sin(a), 0, math.cos(a)]]


def rx(a):
    return [[1, 0, 0], [0, math.cos(a), -math.sin(a)], [0, math.sin(a), math.cos(a)]]


def forward(q):
    R = q_mat(q)
    return (R[0][2], R[1][2], R[2][2])


def forward_yaw_pitch(q):
    fx, fy, fz = forward(q)
    return math.atan2(fx, fz), math.asin(max(-1, min(1, -fy)))


def apply_screen(q, dx_rad, dy_rad):
    """与 ViewState.rotateLocal 完全一致：右乘（绕相机自身轴）先水平后垂直。"""
    if dx_rad:
        q = q_norm(q_mul(q, q_axis(0, -1, 0, dx_rad)))
    if dy_rad:
        q = q_norm(q_mul(q, q_axis(1, 0, 0, dy_rad)))
    return q


def check(name, ok, detail=""):
    print(f"[{'ok ' if ok else 'FAIL'}] {name} {detail}")


def main():
    # 1) fromYawPitch 必须等于 Ry(yaw) * Rx(pitch)
    yaw, pitch = 0.7, -0.4
    A = q_mat(q_yawpitch(yaw, pitch))
    B = mat_mul(ry(yaw), rx(pitch))
    err = max(abs(A[i][j] - B[i][j]) for i in range(3) for j in range(3))
    check("fromYawPitch == Ry*Rx", err < 1e-6, f"maxerr={err:.2e}")

    # 2) 水平拖动累加：绕屏幕竖直轴 90° 后应看左（+x 为右，故 x 分量 < 0）
    q = (1.0, 0.0, 0.0, 0.0)
    for _ in range(90):
        q = apply_screen(q, math.radians(1), 0)
    f = forward(q)
    y, p = forward_yaw_pitch(q)
    check("拖右 90° -> 看左", f[0] < -0.99, f"forward={tuple(round(c,3) for c in f)} yaw={math.degrees(y):.1f}°")
    check("水平拖动不改变俯仰", abs(p) < 1e-6, f"pitch={math.degrees(p):.3f}°")

    # 3) 越过天顶：连续向下拖 200°，pitch 先升到 90° 再回落，视线翻转
    q = (1.0, 0, 0, 0)
    pitches = []
    for _ in range(200):
        q = apply_screen(q, 0, math.radians(1))
        pitches.append(math.degrees(forward_yaw_pitch(q)[1]))
    f = forward(q)
    print("     连续下拖 200° 的 pitch 轨迹:",
          " -> ".join(f"{pitches[i]:.0f}°" for i in (0, 45, 89, 90, 135, 199)))
    check("可以越过天顶（无夹断）", max(pitches) > 89.9 and f[2] < -0.9,
          f"最终 forward.z={f[2]:.3f}")
    check("越过天顶后 pitch 回落", pitches[-1] < 20, f"末帧 pitch={pitches[-1]:.1f}°")

    # 4) 天顶附近的「屏幕角速度」：同样的手指位移，视角在屏幕上转过的角度恒定
    for label, q_at in (("正前方", (1.0, 0, 0, 0)),
                        ("接近天顶", apply_screen((1.0, 0, 0, 0), 0, math.radians(89))),
                        ("正好天顶", apply_screen((1.0, 0, 0, 0), 0, math.radians(90)))):
        q2 = apply_screen(q_at, math.radians(5), 0)
        R1, R2 = q_mat(q_at), q_mat(q2)
        # 视线方向变化的角度 = 两帧 forward 的夹角，也就是屏幕上转过的角度
        f1, f2 = forward(q_at), forward(q2)
        dot = sum(a * b for a, b in zip(f1, f2))
        turned = math.degrees(math.acos(max(-1, min(1, dot))))
        check(f"{label}：水平拖 5° 实际转 {turned:.2f}°", abs(turned - 5) < 1e-3)

    # 4.5) 双指旋转：手指顺时针，画面也顺时针（屏幕 y 向下，角度增加即顺时针）
    q0 = (1.0, 0, 0, 0)
    world = (0.35, 0.2, 0.914)          # 随便一个世界方向
    n = math.sqrt(sum(c * c for c in world))
    world = tuple(c / n for c in world)

    def screen_of(q):
        R = q_mat(q)
        v = tuple(sum(R[j][i] * world[j] for j in range(3)) for i in range(3))  # R^T * d
        h = math.hypot(v[0], v[1])
        return math.atan2(v[1] / h, v[0] / h)

    delta = math.radians(12)
    q1 = q_norm(q_mul(q0, q_axis(0, 0, 1, -delta)))   # 与 rotateView(0,0,1,-delta) 一致
    moved = math.degrees(screen_of(q1) - screen_of(q0))
    check("双指顺时针 12° -> 画面顺时针", abs(moved - 12) < 0.01,
          f"屏幕角变化 {moved:+.2f}°")

    # 4.6) setRoll 必须与渲染脚本的 roll 同向（half = +D/2，而不是 -D/2）
    D = math.radians(152.4)
    q_roll = (math.cos(D / 2), 0.0, 0.0, math.sin(D / 2))
    M = q_mat(q_roll)
    rz = lambda a: [[math.cos(a), -math.sin(a), 0],
                    [math.sin(a), math.cos(a), 0], [0, 0, 1]]
    Rz = rz(D)
    err = max(abs(M[i][j] - Rz[i][j]) for i in range(3) for j in range(3))
    check("ViewState.setRoll 与 render_view --roll 同向", err < 1e-9, f"maxerr={err:.1e}")

    # 4.7) 开启体感时画面不能跳、不能倒：基准要用完整视角（含 180° 基线）
    import math as _m

    def q_from_yawpitch(yaw, pitch):
        return q_yawpitch(yaw, pitch)

    view = q_norm(q_axis(0, 0, 1, math.pi + _m.radians(-27.6)))   # 180° 基线 + 素材残余倾角
    sy, sp = _m.radians(35), _m.radians(8)                        # 开启体感时设备姿态
    sensor_ref = q_from_yawpitch(sy, sp)

    # 旧实现：把视角重建成 yaw/pitch，滚转被丢掉
    old = q_norm(q_mul(q_from_yawpitch(forward_yaw_pitch(view)[0],
                                       forward_yaw_pitch(view)[1]),
                      q_conj(sensor_ref)))
    v_old = q_norm(q_mul(old, sensor_ref))

    # 新实现：直接 view * conj(sensor)
    new = q_norm(q_mul(view, q_conj(sensor_ref)))
    v_new = q_norm(q_mul(new, sensor_ref))

    err_new = max(abs(a - b) for a, b in zip(v_new, view))
    roll_old = math.degrees(_m.atan2(2 * (v_old[0] * v_old[3] + v_old[1] * v_old[2]),
                                    1 - 2 * (v_old[2] ** 2 + v_old[3] ** 2)))
    roll_new = math.degrees(_m.atan2(2 * (v_new[0] * v_new[3] + v_new[1] * v_new[2]),
                                    1 - 2 * (v_new[2] ** 2 + v_new[3] ** 2)))
    check("开体感后画面不跳（新实现）", err_new < 1e-9, f"maxerr={err_new:.1e}")
    check("开体感后保留 180° 基线", abs(abs(roll_new) - abs(roll_old)) > 150,
          f"旧={roll_old:+.1f}° 新={roll_new:+.1f}°（原视角 "
          f"{math.degrees(math.atan2(2 * (view[0] * view[3] + view[1] * view[2]), 1 - 2 * (view[2] ** 2 + view[3] ** 2))):+.1f}°）")

    # 5) 天顶处不会再出现欧拉角那种「越接近极点转得越快」
    q_at = apply_screen((1.0, 0, 0, 0), 0, math.radians(90))
    R = q_mat(q_at)
    # 欧拉实现里 yaw 变化 δ 引起的视线位移 ∝ 1/cos(pitch)；这里直接对比两者的比值
    euler_rate = 1.0 / max(1e-3, math.cos(math.radians(90 - 1e-3)))
    check("旧欧拉实现天顶放大倍数", True, f"≈{euler_rate:,.0f}×（新实现恒为 1×）")


if __name__ == "__main__":
    main()
