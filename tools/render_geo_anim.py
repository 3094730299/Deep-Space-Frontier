# -*- coding: utf-8 -*-
"""
把「geo.json + animation.json」按指定时刻的姿态渲染出来，用来验证动画数据本身。

    python tools/render_geo_anim.py <geo.json> <animation.json> <动画名> <tick> <输出png> [贴图]

输出：该 tick 下模型的等距视角渲染 + 骨骼变换摘要。
"""
import io
import json
import math
import os
import sys

from PIL import Image, ImageDraw

FACES = ("north", "south", "east", "west", "up", "down")


def face_quad(o, s, face):
    x1, y1, z1 = o
    x2, y2, z2 = o[0] + s[0], o[1] + s[1], o[2] + s[2]
    return {
        "north": [(x1, y1, z1), (x2, y1, z1), (x2, y2, z1), (x1, y2, z1)],
        "south": [(x2, y1, z2), (x1, y1, z2), (x1, y2, z2), (x2, y2, z2)],
        "east": [(x2, y1, z1), (x2, y1, z2), (x2, y2, z2), (x2, y2, z1)],
        "west": [(x1, y1, z2), (x1, y1, z1), (x1, y2, z1), (x1, y2, z2)],
        "up": [(x1, y2, z2), (x2, y2, z2), (x2, y2, z1), (x1, y2, z1)],
        "down": [(x1, y1, z1), (x2, y1, z1), (x2, y1, z2), (x1, y1, z2)],
    }[face]


def rot_point(p, origin, rot):
    x, y, z = [p[i] - origin[i] for i in range(3)]
    for axis, angle in enumerate(rot):
        if abs(angle) < 1e-9:
            continue
        a = math.radians(angle)
        c, s = math.cos(a), math.sin(a)
        if axis == 0:
            y, z = y * c - z * s, y * s + z * c
        elif axis == 1:
            x, z = x * c + z * s, -x * s + z * c
        else:
            x, y = x * c - y * s, x * s + y * c
    return [x + origin[0], y + origin[1], z + origin[2]]


def keyframes(chan):
    out = []
    for k, v in chan.items():
        try:
            t = float(k)
        except (TypeError, ValueError):
            continue
        out.append((t, v))
    out.sort(key=lambda x: x[0])
    return out


def sample(chan, tick, length_seconds):
    """在给定 tick（20 tick = 1 秒）取该通道的值（线性插值）"""
    kfs = keyframes(chan)
    if not kfs:
        return None
    seconds = tick / 20.0
    if seconds <= kfs[0][0]:
        v = kfs[0][1]
        return v.get("vector") if isinstance(v, dict) else v
    if seconds >= kfs[-1][0]:
        v = kfs[-1][1]
        return v.get("vector") if isinstance(v, dict) else v
    for i in range(len(kfs) - 1):
        t0, v0 = kfs[i]
        t1, v1 = kfs[i + 1]
        if t0 <= seconds <= t1:
            a = (seconds - t0) / max(1e-9, t1 - t0)
            vec0 = v0.get("vector") if isinstance(v0, dict) else v0
            vec1 = v1.get("vector") if isinstance(v1, dict) else v1
            return [vec0[j] + (vec1[j] - vec0[j]) * a for j in range(3)]
    return None


def main():
    geo_path = sys.argv[1]
    anim_path = sys.argv[2]
    anim_name = sys.argv[3]
    tick = float(sys.argv[4])
    out_path = sys.argv[5]
    tex_path = sys.argv[6] if len(sys.argv) > 6 else None

    geo = json.load(io.open(geo_path, encoding="utf-8"))["minecraft:geometry"][0]
    anims = json.load(io.open(anim_path, encoding="utf-8"))["animations"]
    anim = anims[anim_name]
    length = float(anim.get("animation_length") or 1.0)

    # 每个骨骼的变换
    transforms = {}
    for bone, chans in (anim.get("bones") or {}).items():
        pos = sample(chans["position"], tick, length) if "position" in chans else None
        rot = sample(chans["rotation"], tick, length) if "rotation" in chans else None
        transforms[bone] = (pos, rot)

    print("=== 动画「%s」在 tick=%g 的骨骼变换 ===" % (anim_name, tick))
    for name, (pos, rot) in transforms.items():
        print("   %-10s 位移=%-22s 旋转=%s" % (name, pos, rot))

    # 收集所有立方体（带骨骼变换）
    quads = []

    def walk(bones, parent_pos=(0, 0, 0)):
        for b in bones:
            name = b.get("name")
            pivot = b.get("pivot") or [0, 0, 0]
            pos, rot = transforms.get(name, (None, None))
            # 骨骼位移（Bedrock 单位 = 像素）
            bp = list(parent_pos)
            if pos:
                bp = [bp[i] + pos[i] for i in range(3)]
            for c in (b.get("cubes") or []):
                o = list(c["origin"])
                s = c["size"]
                crot = c.get("rotation")
                cpiv = c.get("pivot") or [0, 0, 0]
                for face in FACES:
                    q = face_quad(o, s, face)
                    if crot:
                        q = [rot_point(p, cpiv, crot) for p in q]
                    if rot:
                        q = [rot_point(p, pivot, rot) for p in q]
                    q = [[p[i] + bp[i] for i in range(3)] for p in q]
                    quads.append((q, face))
            walk(b.get("children") or [], bp)

    walk(geo["bones"])

    if not quads:
        print("没有立方体")
        return

    xs = [p[0] for q, _ in quads for p in q]
    ys = [p[1] for q, _ in quads for p in q]
    zs = [p[2] for q, _ in quads for p in q]
    print("渲染包围盒 X %.1f..%.1f  Y %.1f..%.1f  Z %.1f..%.1f"
          % (min(xs), max(xs), min(ys), max(ys), min(zs), max(zs)))

    # 等距渲染（只画轮廓，便于看姿态）
    S = 420
    yaw, pitch = math.radians(225), math.radians(30)

    def project(p):
        x = p[0] * math.cos(yaw) - p[2] * math.sin(yaw)
        z = p[0] * math.sin(yaw) + p[2] * math.cos(yaw)
        y = p[1]
        sy = y * math.cos(pitch) - z * math.sin(pitch)
        depth = y * math.sin(pitch) + z * math.cos(pitch)
        return x, sy, depth

    allp = [project(p) for q, _ in quads for p in q]
    minx, maxx = min(p[0] for p in allp), max(p[0] for p in allp)
    miny, maxy = min(p[1] for p in allp), max(p[1] for p in allp)
    span = max(maxx - minx, maxy - miny, 1e-6)
    scale = (S - 40) / span
    cx = S / 2 - (minx + maxx) / 2 * scale
    cy = S / 2 + (miny + maxy) / 2 * scale

    img = Image.new("RGBA", (S, S), (30, 32, 40, 255))
    dr = ImageDraw.Draw(img)
    quads.sort(key=lambda it: sum(project(p)[2] for p in it[0]) / 4.0)
    for q, face in quads:
        pts = [(cx + project(p)[0] * scale, cy - project(p)[1] * scale) for p in q]
        shade = {"up": 210, "down": 90, "north": 150, "south": 150, "east": 170, "west": 130}[face]
        dr.polygon(pts, fill=(shade, shade, shade + 10, 255), outline=(20, 20, 25, 255))

    dr.text((8, 8), "tick=%g  (%s)" % (tick, anim_name), fill=(255, 220, 120))
    img.save(out_path)
    print("→ %s" % out_path)


if __name__ == "__main__":
    main()
