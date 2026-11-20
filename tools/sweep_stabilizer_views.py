#!/usr/bin/env python3
"""在多个基准朝向上比较稳像候选写法：哪个真能让世界不动。

判定用「残留位移」（相位相关），单位像素，能换算成角度（1px ≈ fov/宽）。
真机上「开了稳像更抖」的问题就是靠这个定出来的：v0.9.0 的实现与不补偿打平，
把姿态从 IMU 系换算到光学系后残留运动降到 1/4。

    python3 tools/sweep_stabilizer_views.py CAM_xxx.OSV [--pairs 4] [--views "0,0 40,20"]

素材要求：标定参数对得上（CAM0/CAM1/ROT 来自这台设备），场景最好有远景。
车内近景的素材会被视差污染，只能定方向不能量精度。
"""
import argparse
import math
import sys

import numpy as np
from PIL import Image

import verify_stabilizer_sign as V

W, H = 240, 135
BASE = np.array([0.0, 0.0, 0.0, 1.0])   # 默认 180° 滚转（相机倒装）
P = V.q_axis(1, 0, 0, math.pi / 2)      # IMU 系 -> 光学系 的绕 x 90°
PM = V.q_conj(P)


def view_quat(yaw_deg, pitch_deg):
    return V.q_mul(V.q_mul(V.q_axis(0, 1, 0, math.radians(yaw_deg)),
                           V.q_axis(1, 0, 0, math.radians(pitch_deg))), BASE)


def candidates(qa, qb, qu):
    """当前实现 / 另一种等价写法 / 不补偿。"""
    rel = V.q_mul(V.q_conj(qb), qa)
    return {
        "L_pm(当前)": V.q_mul(V.q_mul(V.q_mul(PM, rel), P), qu),
        "R_p(另一写法)": V.q_mul(qu, V.q_mul(V.q_mul(P, rel), PM)),
        "换系前(v0.9.0)": V.q_mul(rel, qu),
        "none": qu,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("osv")
    ap.add_argument("--pairs", type=int, default=4)
    ap.add_argument("--gap", type=int, default=6, help="每对相隔多少帧（默认 6 帧≈0.2s）")
    ap.add_argument("--views", default="0,0 30,10 60,20 120,-10 180,15",
                    help="基准朝向列表：偏航,俯仰")
    ap.add_argument("--out", default="/tmp/sweep-stab")
    args = ap.parse_args()

    quats = V.load_attitudes(args.osv)
    pairs, i = [], 0
    while i + args.gap < len(quats) and len(pairs) < args.pairs:
        if 3.0 <= V.q_angle(quats[i][1], quats[i + args.gap][1]) <= 20.0:
            pairs.append((i, i + args.gap))
            i += max(1, len(quats) // (args.pairs * 2))
        else:
            i += 1
    if not pairs:
        raise SystemExit("没有足够转动的时刻对")

    frames = []
    for ai, bi in pairs:
        ia = [np.asarray(Image.open(p).convert("RGB"), dtype=np.float32)
              for p in V.grab_frames(args.osv, quats[ai][0] / V.FPS, args.out + "-a")]
        ib = [np.asarray(Image.open(p).convert("RGB"), dtype=np.float32)
              for p in V.grab_frames(args.osv, quats[bi][0] / V.FPS, args.out + "-b")]
        frames.append((quats[ai][1], quats[bi][1], ia, ib))
    print("时刻对 %d 组，基准朝向 %d 个" % (len(frames), len(args.views.split())))

    totals = {}
    for spec in args.views.split():
        yaw, pitch = (float(x) for x in spec.split(","))
        qu = view_quat(yaw, pitch)
        per_view = {}
        for qa, qb, ia, ib in frames:
            ref, mref = V.render(ia[0], ia[1], V.q_mat(qu), W, H)
            for name, q in candidates(qa, qb, qu).items():
                img, m = V.render(ib[0], ib[1], V.q_mat(q), W, H)
                sx, sy = V.phase_shift(ref, img, mref & m)
                per_view.setdefault(name, []).append(math.hypot(sx, sy))
        line = "  偏航%4.0f° 俯仰%3.0f°：" % (yaw, pitch)
        for name in candidates(*[frames[0][0]] * 2, qu).keys():
            val = float(np.mean(per_view[name]))
            totals.setdefault(name, []).append(val)
            line += " %s=%5.2fpx" % (name, val)
        print(line)

    print("\n平均残留位移（越小越稳；1px≈%.2f°）：" % (90.0 / W))
    for name in sorted(totals, key=lambda n: np.mean(totals[n])):
        arr = np.array(totals[name])
        print("  %-14s 平均 %5.2fpx  最差 %5.2fpx" % (name, arr.mean(), arr.max()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
