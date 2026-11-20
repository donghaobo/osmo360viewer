#!/usr/bin/env python3
"""扫「姿态延时」：姿态数据要按 t+τ 查才最稳？给面板上的延时滑块一个数据起点。

做法：取若干对时刻（第二帧相对第一帧有明显转动），按 t_b+τ 查姿态、按同样公式算出
修正、把补偿渲染出来，量两张图的残留位移。τ 越接近真实相位误差，残留越小。

    python3 tools/sweep_stabilizer_delay.py CAM_xxx.OSV

样本素材（30fps 车内）实测：τ=0 最优（4.7px），τ=±33ms 就翻倍——相位误差一帧就够毁掉
稳像，所以应用里绝不能再用「UI 定时器 + 墙钟位置」这种晚一拍的写法。
"""
import argparse
import math
import sys

import numpy as np
from PIL import Image

import verify_stabilizer_sign as V

W, H = 240, 135
P = V.q_axis(1, 0, 0, math.pi / 2)      # IMU 系 -> 光学系
PM = V.q_conj(P)


def make_interp(qs, fps):
    def at(us):
        x = max(0.0, us) / 1e6 * fps
        i = int(math.floor(x))
        if i >= len(qs) - 1:
            return qs[-1]
        f = x - i
        a, b = qs[i], qs[i + 1]
        if np.dot(a, b) < 0:
            b = -b
        return V.q_norm(a * (1 - f) + b * f)
    return at


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("osv")
    ap.add_argument("--pairs", type=int, default=5)
    ap.add_argument("--gap", type=int, default=6)
    ap.add_argument("--taus", default="-100,-66,-33,0,33,66,100")
    ap.add_argument("--out", default="/tmp/sweep-delay")
    args = ap.parse_args()

    quats = V.load_attitudes(args.osv)
    qs = [q[1] for q in quats]
    at = make_interp(qs, V.FPS)

    pairs, i = [], 0
    while i + args.gap < len(quats) and len(pairs) < args.pairs:
        if 3.0 <= V.q_angle(qs[i], qs[i + args.gap]) <= 20.0:
            pairs.append((i, i + args.gap))
            i += max(1, len(qs) // (args.pairs * 2))
        else:
            i += 1
    if not pairs:
        raise SystemExit("没有足够转动的时刻对")

    frames = []
    for ai, bi in pairs:
        ia = [np.asarray(Image.open(p).convert("RGB"), dtype=np.float32)
              for p in V.grab_frames(args.osv, ai / V.FPS, args.out + "-a")]
        ib = [np.asarray(Image.open(p).convert("RGB"), dtype=np.float32)
              for p in V.grab_frames(args.osv, bi / V.FPS, args.out + "-b")]
        frames.append((ai, bi, ia, ib))
    print("时刻对 %d 组" % len(frames))

    result = {}
    for tau_ms in (int(x) for x in args.taus.split(",")):
        vals = []
        for ai, bi, ia, ib in frames:
            qa = at(ai / V.FPS * 1e6)
            qb = at(bi / V.FPS * 1e6 + tau_ms * 1000)
            rel = V.q_mul(V.q_conj(qb), qa)
            q_render = V.q_mul(V.q_mul(V.q_mul(PM, rel), P), V.Q_USER)
            ref, mref = V.render(ia[0], ia[1], V.q_mat(V.Q_USER), W, H)
            img, m = V.render(ib[0], ib[1], V.q_mat(q_render), W, H)
            sx, sy = V.phase_shift(ref, img, mref & m)
            vals.append(math.hypot(sx, sy))
        result[tau_ms] = float(np.mean(vals))
        print("  τ=%+4dms 残留 %5.2fpx ≈ %.2f°" % (tau_ms, result[tau_ms], result[tau_ms] * 90.0 / W))

    best = min(result, key=result.get)
    print("\n最优 τ = %+d ms：%s" % (best,
          "姿态数据与画面对齐，面板上保持 0" if best == 0 else
          ("姿态滞后，面板上把「姿态延时」调到 %+d ms" % best)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
