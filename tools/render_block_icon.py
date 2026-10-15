# -*- coding: utf-8 -*-
"""
把「原版方块模型」渲染成物品栏图标 PNG（等距视角，和游戏里物品栏看到的一样）。

    python tools/render_block_icon.py <模型json> <贴图png> <输出png> [尺寸]

原理：
  · 正交投影（等距视角 yaw=225° pitch=30°，就是原版 item 模型的 gui 显示角）
  · 按"面中心到相机的深度"排序（画家算法，箱子模型足够准）
  · UV 采样**内缩**到面内部，避免采到缝里的透明像素
  · 自动按模型包围盒缩放，正中放好
"""
import json
import math
import os
import sys

from PIL import Image

FACES = ("north", "south", "east", "west", "up", "down")


def face_quad(low, high, face):
    x1, y1, z1 = low
    x2, y2, z2 = high
    if face == "north":
        return [(x1, y1, z1), (x2, y1, z1), (x2, y2, z1), (x1, y2, z1)]
    if face == "south":
        return [(x2, y1, z2), (x1, y1, z2), (x1, y2, z2), (x2, y2, z2)]
    if face == "east":
        return [(x2, y1, z1), (x2, y1, z2), (x2, y2, z2), (x2, y2, z1)]
    if face == "west":
        return [(x1, y1, z2), (x1, y1, z1), (x1, y2, z1), (x1, y2, z2)]
    if face == "up":
        return [(x1, y2, z2), (x2, y2, z2), (x2, y2, z1), (x1, y2, z1)]
    return [(x1, y1, z1), (x2, y1, z1), (x2, y1, z2), (x1, y1, z2)]


def rotate_point(p, origin, axis, deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    x, y, z = [p[i] - origin[i] for i in range(3)]
    if axis == "x":
        y, z = y * c - z * s, y * s + z * c
    elif axis == "y":
        x, z = x * c + z * s, -x * s + z * c
    else:
        x, y = x * c - y * s, x * s + y * c
    return [x + origin[0], y + origin[1], z + origin[2]]


def collect(model_path):
    with open(model_path, encoding="utf-8") as f:
        model = json.load(f)
    textures = model.get("textures", {})
    out = []
    depth = 0

    def walk(m, inherited):
        nonlocal depth
        tex = dict(inherited)
        tex.update(m.get("textures", {}))
        parent = m.get("parent")
        if parent and not parent.startswith("minecraft:block/"):
            pass  # 只处理自带 elements 的模型
        for e in m.get("elements", []) or []:
            low, high = e["from"], e["to"]
            rot = e.get("rotation")
            for face, data in (e.get("faces") or {}).items():
                if face not in FACES:
                    continue
                ref = data.get("texture")
                quad = face_quad(low, high, face)
                if rot:
                    quad = [rotate_point(p, rot["origin"], rot["axis"], rot["angle"]) for p in quad]
                uv = data.get("uv")
                if not uv:
                    continue
                out.append((quad, uv, face, tex.get(ref.lstrip("#")) if isinstance(ref, str) else None))
        for child in m.get("children", []) or []:
            walk(child, tex)

    walk(model, textures)
    return out, textures


def main():
    if len(sys.argv) < 4:
        print(__doc__)
        return
    model_path, tex_path, out_path = sys.argv[1], sys.argv[2], sys.argv[3]
    size = int(sys.argv[4]) if len(sys.argv) > 4 else 16

    faces, textures = collect(model_path)
    print("模型 %s：%d 个面" % (os.path.basename(model_path), len(faces)))
    tex = Image.open(tex_path).convert("RGBA")
    print("贴图 %s：%s" % (os.path.basename(tex_path), tex.size))
    tw, th = tex.size

    # 模型包围盒
    xs = [p[0] for q, _, _, _ in faces for p in q]
    ys = [p[1] for q, _, _, _ in faces for p in q]
    zs = [p[2] for q, _, _, _ in faces for p in q]
    lo = (min(xs), min(ys), min(zs))
    hi = (max(xs), max(ys), max(zs))
    print("包围盒 X %.2f..%.2f  Y %.2f..%.2f  Z %.2f..%.2f" % (lo[0], hi[0], lo[1], hi[1], lo[2], hi[2]))

    yaw, pitch = math.radians(225.0), math.radians(30.0)

    def project(p):
        x = p[0] * math.cos(yaw) - p[2] * math.sin(yaw)
        z = p[0] * math.sin(yaw) + p[2] * math.cos(yaw)
        y = p[1]
        sy = y * math.cos(pitch) - z * math.sin(pitch)
        depth = y * math.sin(pitch) + z * math.cos(pitch)
        return x, sy, depth

    # 先算投影范围，再定缩放
    proj = [project(p) for q, _, _, _ in faces for p in q]
    min_x = min(p[0] for p in proj)
    max_x = max(p[0] for p in proj)
    min_y = min(p[1] for p in proj)
    max_y = max(p[1] for p in proj)
    span_x = max(1e-6, max_x - min_x)
    span_y = max(1e-6, max_y - min_y)
    margin = 1.0
    scale = (size - 2 * margin) / max(span_x, span_y * (span_x / span_y if False else 1.0), span_y, span_x)

    # 用物品栏的自定义缩放（原版 gui display 常见 0.625）
    gui_scale = 0.625
    scale = min((size - 2 * margin) / span_x, (size - 2 * margin) / span_y) * gui_scale * 1.15

    cx = size / 2.0 - (min_x + max_x) / 2.0 * scale
    cy = size / 2.0 + (min_y + max_y) / 2.0 * scale

    # 画家算法：远的先画
    def face_depth(item):
        q = item[0]
        return sum(project(p)[2] for p in q) / 4.0

    faces.sort(key=face_depth)

    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    inset = 0.10

    for quad, uv, face, _ref in faces:
        pts = [(cx + project(p)[0] * scale, cy - project(p)[1] * scale) for p in quad]
        # UV 四点顺序要和 quad 顺序一致（原版 uv = [u1,v1,u2,v2]，对应 quad 的 左上/右上…）
        u1, v1, u2, v2 = uv
        # 采样：把四边形光栅化，按重心插值取贴图颜色
        min_px = int(math.floor(min(p[0] for p in pts)))
        max_px = int(math.ceil(max(p[0] for p in pts)))
        min_py = int(math.floor(min(p[1] for p in pts)))
        max_py = int(math.ceil(max(p[1] for p in pts)))

        def bary(px, py, a, b, c):
            d = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
            if abs(d) < 1e-9:
                return None
            w1 = ((b[1] - c[1]) * (px - c[0]) + (c[0] - b[0]) * (py - c[1])) / d
            w2 = ((c[1] - a[1]) * (px - c[0]) + (a[0] - c[0]) * (py - c[1])) / d
            return w1, w2, 1 - w1 - w2

        for py in range(max(0, min_py), min(size, max_py + 1)):
            for px in range(max(0, min_px), min(size, max_px + 1)):
                fx, fy = px + 0.5, py + 0.5
                hit = None
                for tri in ((0, 1, 2), (0, 2, 3)):
                    r = bary(fx, fy, pts[tri[0]], pts[tri[1]], pts[tri[2]])
                    if r and r[0] >= -1e-6 and r[1] >= -1e-6 and r[2] >= -1e-6:
                        hit = (tri, r)
                        break
                if not hit:
                    continue
                (i0, i1, i2), (w0, w1, w2) = hit
                # 四边形上的局部坐标 → UV
                uvq = [(u1, v1), (u2, v1), (u2, v2), (u1, v2)]
                su = uvq[i0][0] * w0 + uvq[i1][0] * w1 + uvq[i2][0] * w2
                sv = uvq[i0][1] * w0 + uvq[i1][1] * w1 + uvq[i2][1] * w2
                su = u1 + (u2 - u1) * inset + (su - u1) * (1 - 2 * inset)
                sv = v1 + (v2 - v1) * inset + (sv - v1) * (1 - 2 * inset)
                tx = min(tw - 1, max(0, int(su * tw / 16.0)))
                ty = min(th - 1, max(0, int(sv * th / 16.0)))
                r_, g_, b_, a_ = tex.getpixel((tx, ty))
                if a_ > 8:
                    img.putpixel((px, py, ), (r_, g_, b_, a_))

    img.save(out_path)
    print("已输出 %s（%d×%d）" % (out_path, size, size))


if __name__ == "__main__":
    main()
