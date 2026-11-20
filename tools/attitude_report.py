#!/usr/bin/env python3
"""统计 OSV 里内嵌相机姿态给出的「初始垂直方向」。

直接对 .OSV 跑（内部用 ffmpeg 抽 djmd 主轨前 2 秒），不需要先手工导轨。

    python3 tools/attitude_report.py CAM_xxx.OSV [more.OSV ...]

输出每个文件的 roll 中位数 / 取值范围：roll 是让地平线摆平需要转的角度，
±180° 附近就是「画面颠倒」的素材。
"""
import math
import os
import statistics
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import parse_djmd as pj  # noqa: E402


def quat_to_mat(w, x, y, z):
    return [[1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y)],
            [2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x)],
            [2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)]]


def gravity_optics(q):
    """世界 Z-up、重力 (0,0,-1) -> IMU 系 -> 光学系 (x 右, y 下, z 前)。"""
    R = quat_to_mat(*q)
    gi = (-R[0][2], -R[1][2], -R[2][2])
    go = (gi[0], -gi[2], gi[1])
    n = math.sqrt(sum(c * c for c in go)) or 1.0
    return tuple(c / n for c in go)


def read_track(path, seconds=2.0):
    with tempfile.TemporaryDirectory() as tmp:
        binp = os.path.join(tmp, "djmd.bin")
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-t", str(seconds), "-i", path,
                        "-map", "0:3", "-c", "copy", "-f", "data", binp], check=True)
        out = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "3",
                              "-read_intervals", f"0%+{seconds}",
                              "-show_entries", "packet=size", "-of", "csv=p=0", path],
                             check=True, capture_output=True, text=True).stdout
        sizes = [int(v) for v in out.split()]
        blob = open(binp, "rb").read()

    packets, off = [], 0
    for size in sizes:
        packets.append(blob[off:off + size])
        off += size

    rolls, tilts = [], []
    for pkt in packets:
        fm = pj.parse_msg(pkt)
        frame_meta = pj.first(fm, 3, 2)
        if not frame_meta:
            continue
        inner = pj.parse_msg(frame_meta)
        cam = pj.first(inner, 2, 2)
        if not cam:
            continue
        cf = pj.parse_msg(cam)
        att = pj.first(cf, 9, 2)
        if not att:
            continue
        g = gravity_optics(pj.parse_quat(att))
        if math.hypot(g[0], g[1]) < 0.05:
            continue   # 光轴接近天顶/地面，roll 无意义
        rolls.append(math.degrees(math.atan2(-g[0], g[1])))
        tilts.append(math.degrees(math.acos(max(-1, min(1, g[1])))))
    return rolls, tilts


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    for path in sys.argv[1:]:
        try:
            rolls, tilts = read_track(path)
        except Exception as e:
            print(f"{os.path.basename(path)}: 解析失败 ({e})")
            continue
        if not rolls:
            print(f"{os.path.basename(path)}: 没有可用的相机姿态数据")
            continue
        roll = statistics.median(rolls)
        tilt = statistics.median(tilts)
        note = "（画面颠倒，应校正 180°）" if abs(abs(roll) - 180) < 45 else ""
        print(f"{os.path.basename(path)}\n"
              f"  帧数={len(rolls)}  roll 中位数={roll:+7.1f}°  "
              f"范围 {min(rolls):+.1f}°~{max(rolls):+.1f}°{note}\n"
              f"  光轴相对水平方向={tilt:5.1f}°（>60° 时 roll 校正意义不大）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
