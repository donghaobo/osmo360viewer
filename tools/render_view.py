#!/usr/bin/env python3
"""用真实鱼眼帧复现 StitchRenderer 的投影，渲染一张任意视角的透视图。

用来肉眼判断「初始视角到底该转多少度」——相机的姿态数据只给数值，
最终是不是正的还得看画面。

    python3 tools/render_view.py <cam0.jpg> <cam1.jpg> <out.png> \
        [--yaw 0] [--pitch 0] [--roll 0] [--fov 100] [--w 640] [--h 360]
"""
import argparse
import math
import sys

from PIL import Image

VID = (3000.0, 3000.0)

CAM0 = dict(fx=1388.696, fy=1388.755, cx=1483.969, cy=1510.087,
            k1=-0.182932, k2=0.018311, p1=-5.93245e-05, p2=0.0004022920,
            xi=0.717254, rmax=1673.0)
CAM1 = dict(fx=1388.169, fy=1388.309, cx=1494.305, cy=1505.195,
            k1=-0.183040, k2=0.018367, p1=0.000107692, p2=0.000155120,
            xi=0.712920, rmax=1676.0)

ROT = [[-0.9999521276053416, -9.011589936091375e-05, -0.009784394548200492],
       [-0.00023804281786328152, 0.9998856799076945, 0.015118546594973989],
       [0.009781913573889153, 0.015120151938794074, -0.9998378344372554]]
GAIN = (0.965, 0.998, 0.977)


def project(d, cam):
    den = d[2] + cam["xi"]
    if den <= 1e-4:
        return None
    a, b = d[0] / den, d[1] / den
    r2 = a * a + b * b
    dr = 1.0 + cam["k1"] * r2 + cam["k2"] * r2 * r2
    xd = a * dr + 2.0 * cam["p1"] * a * b + cam["p2"] * (r2 + 2.0 * a * a)
    yd = b * dr + cam["p1"] * (r2 + 2.0 * b * b) + 2.0 * cam["p2"] * a * b
    px = cam["fx"] * xd + cam["cx"]
    py = cam["fy"] * yd + cam["cy"]
    if math.hypot(px - cam["cx"], py - cam["cy"]) > cam["rmax"]:
        return None
    if not (0 <= px < VID[0] and 0 <= py < VID[1]):
        return None
    return px, py


def rot_apply(d):
    return tuple(sum(ROT[i][j] * d[j] for j in range(3)) for i in range(3))


def view_matrix(yaw, pitch, roll):
    """与 shader 的 Ry(yaw)·Rx(pitch) 一致，再叠一个绕光轴的 roll。"""
    cy, sy = math.cos(yaw), math.sin(yaw)
    cp, sp = math.cos(pitch), math.sin(pitch)
    cr, sr = math.cos(roll), math.sin(roll)

    def mul(A, B):
        return [[sum(A[i][k] * B[k][j] for k in range(3)) for j in range(3)] for i in range(3)]

    ry = [[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]]
    rx = [[1, 0, 0], [0, cp, -sp], [0, sp, cp]]
    rz = [[cr, -sr, 0], [sr, cr, 0], [0, 0, 1]]
    return mul(rz, mul(ry, rx))


def render(cam0_path, cam1_path, out_path, yaw=0.0, pitch=0.0, roll=0.0,
           fov=100.0, w=640, h=360, feather=4.0, seam=0.0):
    im0 = Image.open(cam0_path).convert("RGB")
    im1 = Image.open(cam1_path).convert("RGB")
    b0, b1 = im0.tobytes(), im1.tobytes()
    w0 = im0.width

    M = view_matrix(math.radians(yaw), math.radians(pitch), math.radians(roll))
    tan_half = math.tan(math.radians(fov) / 2)
    ar = w / h
    tan_h = tan_half if ar >= 1 else tan_half * ar
    tan_v = tan_half / ar if ar >= 1 else tan_half
    feather_r = math.radians(max(0.3, feather))
    seam_r = math.radians(seam)

    out = Image.new("RGB", (w, h))
    px = out.load()

    def sample(buf, bw, bh, u, v, gain):
        x = min(bw - 1, max(0, int(u)))
        y = min(bh - 1, max(0, int(v)))
        i = (y * bw + x) * 3
        return (buf[i] * gain[0], buf[i + 1] * gain[1], buf[i + 2] * gain[2])

    for y in range(h):
        ndc_y = (y + 0.5) / h * 2 - 1
        for x in range(w):
            ndc_x = (x + 0.5) / w * 2 - 1
            vx, vy, vz = ndc_x * tan_h, -ndc_y * tan_v, 1.0
            n = math.sqrt(vx * vx + vy * vy + vz * vz)
            vx, vy, vz = vx / n, vy / n, vz / n
            d0 = tuple(sum(M[i][j] * (vx, vy, vz)[j] for j in range(3)) for i in range(3))
            d1 = rot_apply(d0)

            p0 = project(d0, CAM0)
            p1 = project(d1, CAM1)
            if p0 is None and p1 is None:
                px[x, y] = (0, 0, 0)
                continue

            th0 = math.acos(max(-1, min(1, d0[2])))
            th1 = math.acos(max(-1, min(1, d1[2]))) + seam_r
            wgt0 = max(0.0, min(1.0, 0.5 + (th1 - th0) / feather_r))
            wgt0 = wgt0 * wgt0 * (3 - 2 * wgt0)
            if p0 is None:
                wgt0 = 0.0
            wgt1 = (1 - wgt0) if p1 is not None else 0.0

            c0 = sample(b0, im0.width, im0.height, *p0, (1, 1, 1)) if p0 else (0, 0, 0)
            c1 = sample(b1, im1.width, im1.height, *p1, GAIN) if p1 else (0, 0, 0)
            tot = max(wgt0 + wgt1, 1e-5)
            px[x, y] = tuple(int(min(255, (c0[i] * wgt0 + c1[i] * wgt1) / tot)) for i in range(3))

    out.save(out_path)
    print(f"wrote {out_path}  yaw={yaw} pitch={pitch} roll={roll} fov={fov}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cam0")
    ap.add_argument("cam1")
    ap.add_argument("out")
    ap.add_argument("--yaw", type=float, default=0)
    ap.add_argument("--pitch", type=float, default=0)
    ap.add_argument("--roll", type=float, default=0)
    ap.add_argument("--fov", type=float, default=100)
    ap.add_argument("--w", type=int, default=640)
    ap.add_argument("--h", type=int, default=360)
    a = ap.parse_args()
    render(a.cam0, a.cam1, a.out, a.yaw, a.pitch, a.roll, a.fov, a.w, a.h)
    return 0


if __name__ == "__main__":
    sys.exit(main())
