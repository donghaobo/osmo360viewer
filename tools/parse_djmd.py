#!/usr/bin/env python3
"""解析 OSV 的 djmd 元数据轨，导出逐帧相机姿态（以及首包的 clip_meta 摘要）。

用法:
    python3 tools/parse_djmd.py <djmd.bin> <sizes.txt> [--json out.json]

djmd.bin 用 ffmpeg 抽:
    ffmpeg -i CAM_xxx.OSV -map 0:3 -c copy -f data djmd.bin
sizes.txt 是每个 packet 的字节数:
    ffprobe -select_streams 3 -show_entries packet=size -of csv=p=0 CAM_xxx.OSV
"""
import struct
import sys


def read_varint(buf, i):
    value, shift = 0, 0
    while True:
        if i >= len(buf):
            raise ValueError("truncated varint")
        c = buf[i]
        i += 1
        value |= (c & 0x7F) << shift
        if not (c & 0x80):
            return value, i
        shift += 7


def parse_msg(buf):
    """返回 [(field_number, wire_type, value)]，value 是 int / bytes。"""
    fields, i = [], 0
    while i < len(buf):
        key, i = read_varint(buf, i)
        field, wire = key >> 3, key & 7
        if wire == 0:
            value, i = read_varint(buf, i)
        elif wire == 1:
            value, i = buf[i:i + 8], i + 8
        elif wire == 2:
            size, i = read_varint(buf, i)
            value, i = buf[i:i + size], i + size
        elif wire == 5:
            value, i = buf[i:i + 4], i + 4
        else:
            break  # 未知 wire type，停止
        fields.append((field, wire, value))
    return fields


def first(fields, num, wire=None):
    for f, w, v in fields:
        if f == num and (wire is None or w == wire):
            return v
    return None


def all_of(fields, num):
    return [v for f, _w, v in fields if f == num]


def as_float(value):
    return struct.unpack("<f", value)[0]


def parse_quat(buf):
    fields = parse_msg(buf)
    return (as_float(first(fields, 1)), as_float(first(fields, 2)),
            as_float(first(fields, 3)), as_float(first(fields, 4)))


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2

    blob = open(sys.argv[1], "rb").read()
    sizes = [int(x) for x in open(sys.argv[2]).read().split()]

    offset = 0
    packets = []
    for size in sizes:
        packets.append(blob[offset:offset + size])
        offset += size
    print(f"packets={len(packets)} bytes={len(blob)} parsed={offset}")

    head = parse_msg(packets[0])
    print("首包字段:", [f for f, _w, _v in head])
    proto_name = first(head, 1) if False else None
    clip = first(head, 1, 2)
    if clip:
        cf = parse_msg(clip)
        # ClipMetaHeader.proto_file_name = 1
        hdr = first(cf, 1, 2)
        if hdr:
            name = first(parse_msg(hdr), 1, 2)
            if name:
                print("schema:", name.decode(errors="replace"))

    rows = []
    for idx, pkt in enumerate(packets[:400]):
        fm = parse_msg(pkt)
        frame_meta = first(fm, 3, 2)
        if not frame_meta:
            continue
        inner = parse_msg(frame_meta)
        header = first(inner, 1, 2)
        seq = ts = None
        if header:
            hf = parse_msg(header)
            seq = first(hf, 1)
            ts = first(hf, 2)
        cam = first(inner, 2, 2)
        if not cam:
            continue
        cf = parse_msg(cam)
        att = first(cf, 9, 2)
        acc = first(cf, 10, 2)
        q = parse_quat(att) if att else None
        accv = None
        if acc:
            try:
                af = parse_msg(acc)
                accv = tuple(as_float(first(af, n, 5)) for n in (1, 2, 3))
            except Exception:
                accv = None
        rows.append((idx, seq, ts, q, accv))

    for r in rows[:6]:
        print("pkt=%d seq=%s ts=%s q=%s acc=%s" % r)

    if "--json" in sys.argv:
        import json
        out = [{"packet": r[0], "seq": r[1], "ts": r[2], "q": r[3], "acc": r[4]} for r in rows]
        json.dump(out, open(sys.argv[sys.argv.index("--json") + 1], "w"), indent=1)
        print("wrote", sys.argv[sys.argv.index("--json") + 1])
    return 0


if __name__ == "__main__":
    sys.exit(main())
