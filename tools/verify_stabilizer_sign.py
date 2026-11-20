#!/usr/bin/env python3
"""用真实鱼眼帧判定稳像修正的方向（离线，不需要真机）。

先把两个时刻的画面按 shader 的投影各自渲染出来，再比较「补偿前」与「补偿后」的
画面差：如果修正方向对，两个时刻渲染出的世界视角几乎重合（差最小）；方向写反或
坐标系没转，差会比不补偿还大。真机上「抖动更明显」正好是后者。

    python3 tools/verify_stabilizer_sign.py CAM_xxx.OSV [--pairs 8] [--out /tmp/sign]

候选（q_user 与 ViewState 默认一致，即绕光轴 180°）。v0.9.1 采用 D_xm90（把姿态
换算到光学系再求相对旋转），它是下面这组里在 7 个基准朝向上都最稳的一个：

    none        q_user                              不补偿（基线）
    A_current   conj(q_b)·q_a·q_user                v0.9.0 的实现（没做换系）
    B_flip      q_b·conj(q_a)·q_user                反号
    C_x90       p·(conj q_b·q_a)·p⁻¹·q_user         换系，方向取反
    D_xm90      p⁻¹·(conj q_b·q_a)·p·q_user         换系（v0.9.1 采用）
    E_right     q_user·conj(q_b)·q_a                右乘
    （另有两个 flip 变体）

p 是绕 x 转 +90° 的四元数，对应用 OsvMeta.rollOf 里「ox = gx, oy = -gz」同一套
IMU(x 右、y 光轴、z 上) ↔ 光学(x 右、y 下、z 前) 关系。到底取 p 还是 p⁻¹，推理会
绕晕，直接看实测：D_xm90 在 7 个基准朝向上都把残留运动压到 2° 上下，C_x90 反而
比不补偿还差。判定跑法见 tools/fit_stabilizer_rotation.py 同思路的 sweep。
"""
import argparse
import math
import os
import subprocess
import sys
import tempfile

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import parse_djmd as pj  # noqa: E402

VID = (3000.0, 3000.0)
CAM0 = dict(fx=1388.696, fy=1388.755, cx=1483.969, cy=1510.087,
            k1=-0.182932, k2=0.018311, p1=-5.93245e-05, p2=0.0004022920,
            xi=0.717254, rmax=1673.0)
CAM1 = dict(fx=1388.169, fy=1388.309, cx=1494.305, cy=1505.195,
            k1=-0.183040, k2=0.018367, p1=0.000107692, p2=0.000155120,
            xi=0.712920, rmax=1676.0)
ROT = np.array([[-0.9999521276053416, -9.011589936091375e-05, -0.009784394548200492],
                [-0.00023804281786328152, 0.9998856799076945, 0.015118546594973989],
                [0.009781913573889153, 0.015120151938794074, -0.9998378344372554]])
GAIN = np.array([0.965, 0.998, 0.977])
FPS = 30.0

# 与 ViewState 默认姿态一致：绕光轴 180°（相机倒装）
Q_USER = np.array([0.0, 0.0, 0.0, 1.0])


# ----------------------------------------------------------------- 四元数


def q_mul(a, b):
    aw, ax, ay, az = a
    bw, bx, by, bz = b
    return np.array([aw * bw - ax * bx - ay * by - az * bz,
                     aw * bx + ax * bw + ay * bz - az * by,
                     aw * by - ax * bz + ay * bw + az * bx,
                     aw * bz + ax * by - ay * bx + az * bw])


def q_conj(q):
    return np.array([q[0], -q[1], -q[2], -q[3]])


def q_norm(q):
    n = np.linalg.norm(q) or 1.0
    return q / n


def q_axis(ax, ay, az, angle):
    n = math.sqrt(ax * ax + ay * ay + az * az) or 1.0
    s = math.sin(angle / 2) / n
    return np.array([math.cos(angle / 2), ax * s, ay * s, az * s])


def q_mat(q):
    """与 Quat.toMatrix 一致：R(q) 的 3x3（numpy 里直接是行主序矩阵）。"""
    w, x, y, z = q
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y)],
                     [2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x)],
                     [2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)]])


def q_angle(a, b):
    d = float(np.clip(abs(np.dot(a, b)) / ((np.linalg.norm(a) or 1.0)
                                           * (np.linalg.norm(b) or 1.0)), -1, 1))
    return math.degrees(2 * math.acos(d))


# ----------------------------------------------------------------- 渲染（照抄 shader）


def project(d, cam):
    den = d[2] + cam["xi"]
    ok = den > 1e-4
    den = np.where(ok, den, 1.0)
    a, b = d[0] / den, d[1] / den
    r2 = a * a + b * b
    dr = 1.0 + cam["k1"] * r2 + cam["k2"] * r2 * r2
    xd = a * dr + 2.0 * cam["p1"] * a * b + cam["p2"] * (r2 + 2.0 * a * a)
    yd = b * dr + cam["p1"] * (r2 + 2.0 * b * b) + 2.0 * cam["p2"] * a * b
    px = cam["fx"] * xd + cam["cx"]
    py = cam["fy"] * yd + cam["cy"]
    ok &= np.hypot(px - cam["cx"], py - cam["cy"]) <= cam["rmax"]
    ok &= (px >= 0) & (px < VID[0] - 1) & (py >= 0) & (py < VID[1] - 1)
    return px, py, ok


def bilinear(img, px, py):
    h, w = img.shape[:2]
    x0 = np.clip(np.floor(px).astype(np.int32), 0, w - 2)
    y0 = np.clip(np.floor(py).astype(np.int32), 0, h - 2)
    fx = np.clip(px - x0, 0, 1)[..., None]
    fy = np.clip(py - y0, 0, 1)[..., None]
    c00 = img[y0, x0]
    c01 = img[y0, x0 + 1]
    c10 = img[y0 + 1, x0]
    c11 = img[y0 + 1, x0 + 1]
    return (c00 * (1 - fx) * (1 - fy) + c01 * fx * (1 - fy)
            + c10 * (1 - fx) * fy + c11 * fx * fy)


def render(img0, img1, M, w, h, fov=90.0):
    """按 StitchRenderer 的透视模式渲染一张图，返回 (rgb float, valid mask)。"""
    tan_half = math.tan(math.radians(fov) / 2)
    ar = w / h
    tan_h = tan_half if ar >= 1 else tan_half * ar
    tan_v = tan_half / ar if ar >= 1 else tan_half
    xs = ((np.arange(w) + 0.5) / w * 2 - 1) * tan_h
    ys = -((np.arange(h) + 0.5) / h * 2 - 1) * tan_v
    gx, gy = np.meshgrid(xs, ys)
    v = np.stack([gx, gy, np.ones_like(gx)], axis=0)
    v /= np.linalg.norm(v, axis=0, keepdims=True)

    d0 = np.tensordot(M, v, axes=1)
    d1 = np.tensordot(ROT, d0, axes=1)

    px0, py0, ok0 = project(d0, CAM0)
    px1, py1, ok1 = project(d1, CAM1)
    both = ok0 | ok1

    th0 = np.arccos(np.clip(d0[2], -1, 1))
    th1 = np.arccos(np.clip(d1[2], -1, 1))
    feather = math.radians(4.0)
    w0 = np.clip(0.5 + (th1 - th0) / feather, 0, 1)
    w0 = w0 * w0 * (3 - 2 * w0)
    w0 = np.where(ok0, w0, 0.0)
    w1 = np.where(ok1, 1.0 - w0, 0.0)
    tot = np.maximum(w0 + w1, 1e-5)

    c0 = bilinear(img0, np.where(ok0, px0, 0), np.where(ok0, py0, 0)) * ok0[..., None]
    c1 = bilinear(img1, np.where(ok1, px1, 0), np.where(ok1, py1, 0)) * ok1[..., None] * GAIN
    out = (c0 * w0[..., None] + c1 * w1[..., None]) / tot[..., None]
    return out, both


def phase_shift(ref, test, mask):
    """相位相关估计两图的整体平移（像素），用来量「世界有没有跟着动」。"""
    g0 = ref.mean(axis=2)
    g1 = test.mean(axis=2)
    m = mask.astype(np.float32)
    g0 = (g0 - g0[mask].mean()) * m
    g1 = (g1 - g1[mask].mean()) * m
    win = np.outer(np.hanning(g0.shape[0]), np.hanning(g0.shape[1]))
    F0 = np.fft.rfft2(g0 * win)
    F1 = np.fft.rfft2(g1 * win)
    R = F0 * np.conj(F1)
    R /= np.maximum(np.abs(R), 1e-9)
    corr = np.fft.irfft2(R, s=g0.shape)
    peak = np.unravel_index(np.argmax(corr), corr.shape)
    py, px = peak
    h, w = corr.shape
    dy = py - (h if py > h // 2 else 0)
    dx = px - (w if px > w // 2 else 0)
    return abs(dx), abs(dy)


# ----------------------------------------------------------------- 素材


def load_attitudes(osv, max_seconds=None):
    with tempfile.TemporaryDirectory() as tmp:
        binp = os.path.join(tmp, "djmd.bin")
        subprocess.run(["ffmpeg", "-v", "error", "-y"] + (["-t", str(max_seconds)] if max_seconds else [])
                       + ["-i", osv, "-map", "0:3", "-c", "copy", "-f", "data", binp], check=True)
        sizes = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "3",
                                "-show_entries", "packet=size", "-of", "csv=p=0", osv],
                               capture_output=True, text=True, check=True).stdout.split()
        blob = open(binp, "rb").read()
    sizes = [int(s) for s in sizes if s.strip()]
    quats, offset, i = [], 0, 0
    for size in sizes:
        if offset + size > len(blob):
            break
        pkt = blob[offset:offset + size]
        offset += size
        fm = pj.parse_msg(pkt)
        frame_meta = pj.first(fm, 3, 2)
        if not frame_meta:
            i += 1
            continue
        inner = pj.parse_msg(frame_meta)
        cam = pj.first(inner, 2, 2)
        if cam:
            att = pj.first(pj.parse_msg(cam), 9, 2)
            if att:
                q = pj.parse_quat(att)
                if all(q[c] is not None for c in range(4)):
                    quats.append((i, np.array(q, dtype=float)))
        i += 1
    return quats


def grab_frames(osv, t_sec, out_prefix):
    paths = []
    for idx in (0, 1):
        p = f"{out_prefix}-{idx}.png"
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", f"{t_sec:.3f}", "-i", osv,
                        "-map", f"0:v:{idx}", "-frames:v", "1", p], check=True)
        if not os.path.exists(p):
            raise SystemExit(f"抽帧失败 {p}")
        paths.append(p)
    return paths


# ----------------------------------------------------------------- 候选


def candidates(q_a, q_b):
    """返回 {名字: q_render(b)}；同时假定 q_render(a) = q_user。"""
    rel = q_mul(q_conj(q_b), q_a)             # 世界系相对旋转（IMU 坐标系下）
    rel_inv = q_mul(q_b, q_conj(q_a))         # 反号（= rel 的逆）
    p = q_axis(1, 0, 0, math.pi / 2)          # IMU(x右,y光轴,z上) -> 光学(x右,y下,z前)
    p_inv = q_conj(p)

    def move(q, r):
        return q_mul(q_mul(q_mul(q, r), q_conj(q)), Q_USER)

    return {
        "none": Q_USER,
        "A_current": q_mul(rel, Q_USER),
        "B_flip": q_mul(rel_inv, Q_USER),
        "C_x90": move(p, rel),
        "C_x90_flip": move(p, rel_inv),
        "D_xm90": move(p_inv, rel),
        "D_xm90_flip": move(p_inv, rel_inv),
        "E_right": q_mul(Q_USER, rel),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("osv")
    ap.add_argument("--pairs", type=int, default=8, help="取多少对时刻参与统计")
    ap.add_argument("--gap", type=int, default=6, help="每对相隔多少个样本（默认 6 帧≈0.2s）")
    ap.add_argument("--w", type=int, default=240)
    ap.add_argument("--h", type=int, default=135)
    ap.add_argument("--fov", type=float, default=90.0)
    ap.add_argument("--out", default=tempfile.gettempdir() + "/stab-sign")
    args = ap.parse_args()

    quats = load_attitudes(args.osv)
    if len(quats) < args.gap + 2:
        raise SystemExit(f"姿态样本太少：{len(quats)}")
    print(f"姿态样本 {len(quats)} 帧（≈{len(quats)/FPS:.1f}s）")

    # 选「有实际转动」的时刻对：转角在 2°~20° 之间，太小的对分不出方向
    pairs, i = [], 0
    while i + args.gap < len(quats) and len(pairs) < args.pairs:
        a, qa = quats[i]
        b, qb = quats[i + args.gap]
        ang = q_angle(qa, qb)
        if 2.0 <= ang <= 20.0:
            pairs.append((i, i + args.gap, a, b, ang))
            i += max(1, len(quats) // (args.pairs * 2))
        else:
            i += 1
    if not pairs:
        raise SystemExit("没有找到合适的时刻对（转动太小？）")
    print("采用 %d 对时刻：%s" % (len(pairs), ", ".join(
        f"{a}->{b}({ang:.1f}°)" for _i, _j, a, b, ang in pairs)))

    names = list(candidates(quats[0][1], quats[0][1]).keys())
    scores = {n: [] for n in names}
    wins = {n: 0 for n in names}
    shifts_all = {n: [] for n in names}

    for k, (i, j, a, b, ang) in enumerate(pairs):
        t_a, t_b = a / FPS, b / FPS
        pa = grab_frames(args.osv, t_a, f"{args.out}-{k}-a")
        pb = grab_frames(args.osv, t_b, f"{args.out}-{k}-b")
        imga = [np.asarray(Image.open(p).convert("RGB"), dtype=np.float32) for p in pa]
        imgb = [np.asarray(Image.open(p).convert("RGB"), dtype=np.float32) for p in pb]

        ref, mask_ref = render(imga[0], imga[1], q_mat(Q_USER), args.w, args.h, args.fov)
        row = []
        for name, qb_render in candidates(quats[i][1], quats[j][1]).items():
            img, mask = render(imgb[0], imgb[1], q_mat(qb_render), args.w, args.h, args.fov)
            m = mask_ref & mask
            if not m.any():
                row.append((name, float("nan")))
                continue
            diff = float(np.abs(ref[m] - img[m]).mean())
            scores[name].append(diff)
            sx, sy = phase_shift(ref, img, m)
            shifts_all[name].append(math.hypot(sx, sy))
            row.append((name, math.hypot(sx, sy)))
        good = [(v, n) for n, v in row if not math.isnan(v)]
        if good:
            wins[min(good)[1]] += 1
        print(f"  对 {k}: {a}->{b} 转 {ang:.2f}°  " + "  ".join(f"{n}={v:.1f}px" for n, v in row))

    print(f"\n平均残留位移（像素；{args.fov:.0f}° 视野 {args.w}px → 1px≈"
          f"{args.fov / args.w:.2f}°）／最优次数：")
    ranked = sorted(((np.nanmean(v) if v else float("nan"), n)
                     for n, v in shifts_all.items()))
    for val, name in ranked:
        print(f"  {name:<16} {val:6.2f}px ≈ {val * args.fov / args.w:5.2f}°"
              f"   画面差 {np.nanmean(scores[name]):6.2f}   最优 {wins[name]}/{len(pairs)}")
    best = ranked[0][1]
    base = dict((n, np.nanmean(v)) for n, v in shifts_all.items())
    print(f"\n最优候选：{best}"
          f"（相对不补偿 {base.get('none', float('nan')):.2f}px 变化 "
          f"{100 * (base[best] - base['none']) / max(base['none'], 1e-6):+.0f}%）")
    wins = {}
    for k in range(len(pairs)):
        pass
    if best == "A_current":
        print("结论：v0.9.0 的修正方向是对的，抖动变明显另有原因。")
    elif best in ("D_xm90", "C_x90_flip"):
        print(f"结论：应采用 {best} —— 姿态要先从 IMU 系换算到光学系（v0.9.1 的做法）。")
    else:
        print(f"结论：A_current（v0.9.0 的实现）不是最优，最优是 {best}。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
