#!/usr/bin/env python3
"""离线核对 StitchRenderer 的几何：完全照抄 GLSL 的 UCM 投影与接缝权重，
统计不同视角下两路镜头的覆盖情况。不需要 numpy / 不需要 Android 设备。

用法: python3 tools/verify_geometry.py
"""
import math

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
    if not (0 <= px <= VID[0] and 0 <= py <= VID[1]):
        return None
    return px, py


def rot_apply(d):
    # GLSL 里传入的是行主序矩阵的转置（列主序 uniform），等价于 ROT @ d
    return (ROT[0][0] * d[0] + ROT[0][1] * d[1] + ROT[0][2] * d[2],
            ROT[1][0] * d[0] + ROT[1][1] * d[1] + ROT[1][2] * d[2],
            ROT[2][0] * d[0] + ROT[2][1] * d[1] + ROT[2][2] * d[2])


def view_dir(px, py, w, h, fov_deg, yaw, pitch):
    tan_half = math.tan(math.radians(fov_deg) / 2)
    ar = w / h
    tan_h = tan_half if ar >= 1 else tan_half * ar
    tan_v = tan_half / ar if ar >= 1 else tan_half
    ndc_x = (px + 0.5) / w * 2 - 1
    ndc_y = (py + 0.5) / h * 2 - 1
    vx, vy, vz = ndc_x * tan_h, -ndc_y * tan_v, 1.0
    n = math.sqrt(vx * vx + vy * vy + vz * vz)
    vx, vy, vz = vx / n, vy / n, vz / n
    cp, sp = math.cos(pitch), math.sin(pitch)
    tx, ty, tz = vx, cp * vy - sp * vz, sp * vy + cp * vz
    cy, sy = math.cos(yaw), math.sin(yaw)
    return (cy * tx + sy * tz, ty, -sy * tx + cy * tz)


def seam_weight(d0, d1, feather_deg=4.0, seam_deg=0.0):
    th0 = math.acos(max(-1, min(1, d0[2])))
    th1 = math.acos(max(-1, min(1, d1[2]))) + math.radians(seam_deg)
    feather = math.radians(feather_deg)
    w0 = max(0.0, min(1.0, 0.5 + (th1 - th0) / feather))
    w0 = w0 * w0 * (3 - 2 * w0)
    return w0, 1 - w0


def scan(w, h, fov, yaw, pitch=0.0):
    only0 = only1 = both = none = 0
    for y in range(h):
        for x in range(w):
            d0 = view_dir(x, y, w, h, fov, yaw, pitch)
            d1 = rot_apply(d0)
            a = project(d0, CAM0) is not None
            b = project(d1, CAM1) is not None
            if a and b:
                both += 1
            elif a:
                only0 += 1
            elif b:
                only1 += 1
            else:
                none += 1
    total = w * h
    return dict(total=total, only0=only0 / total, only1=only1 / total,
                both=both / total, none=none / total)


def equirect(w, h):
    """整球等距矩形：看两路合起来是否完整覆盖，以及接缝落在哪。"""
    only0 = only1 = both = none = 0
    for y in range(h):
        lat = ((y + 0.5) / h - 0.5) * math.pi
        for x in range(w):
            lon = ((x + 0.5) / w - 0.5) * 2 * math.pi
            d0 = (math.sin(lon) * math.cos(lat), -math.sin(lat), math.cos(lon) * math.cos(lat))
            d1 = rot_apply(d0)
            a = project(d0, CAM0) is not None
            b = project(d1, CAM1) is not None
            if a and b:
                both += 1
            elif a:
                only0 += 1
            elif b:
                only1 += 1
            else:
                none += 1
    total = w * h
    return dict(total=total, only0=only0 / total, only1=only1 / total,
                both=both / total, none=none / total)


def main():
    print("== 透视窗口 90° FOV，扫一圈 yaw（128×72 采样）==")
    print(f"{'yaw':>5}  {'cam0 only':>9}  {'cam1 only':>9}  {'both':>6}  {'未覆盖':>6}")
    for yaw_deg in range(0, 360, 30):
        r = scan(128, 72, 90.0, math.radians(yaw_deg))
        print(f"{yaw_deg:>5}  {r['only0']*100:>8.1f}%  {r['only1']*100:>8.1f}%  "
              f"{r['both']*100:>5.1f}%  {r['none']*100:>5.1f}%")

    print("\n== 等距矩形 400×200（整球覆盖）==")
    r = equirect(400, 200)
    print(f"cam0 only {r['only0']*100:.1f}%   cam1 only {r['only1']*100:.1f}%   "
          f"重叠 {r['both']*100:.1f}%   未覆盖 {r['none']*100:.1f}%")

    print("\n== 接缝位置（等距矩形经度方向上的权重分布）==")
    lat = 0.0
    for lon_deg in range(0, 360, 45):
        lon = math.radians(lon_deg)
        d0 = (math.sin(lon), 0.0, math.cos(lon))
        w0, w1 = seam_weight(d0, rot_apply(d0))
        src = "cam0" if w0 > 0.95 else ("cam1" if w1 > 0.95 else "混合")
        print(f"经度 {lon_deg:>3}°: cam0 权重 {w0:.2f}  ({src})")


if __name__ == "__main__":
    main()
