# -*- coding: utf-8 -*-
"""
把原版方块模型渲染成 PNG —— 用来"亲眼"检查转换结果。

    python tools/render_vanilla_model.py

和之前那个坏掉的预览的区别（关键修复）：
  * UV 采样**内缩**到面内部 1/8 .. 7/8 的范围。原来的版本直接 int(u) 取整，
    会采到面边缘以外的透明像素，导致整个面画不出来（我就是被这个骗了一轮）。
  * 单位统一：贴图坐标 = uv(0..16) * 4 → 0..64 像素。
  * 画家算法按"面中心到相机的深度"排序，等距视角下对箱子模型足够准。

输出：tools/_vanilla_render.png（三个视角并排）
"""
import json
import math
import os

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
ASSETS = os.path.join(PROJECT, "src", "main", "resources", "assets", "deepspacedeployer")

MODEL = os.path.join(ASSETS, "models", "block", "universal_terminal.json")
TEX = os.path.join(ASSETS, "textures", "block", "universal_terminal.png")
OUT = os.path.join(HERE, "_vanilla_render.png")

TILE = 560
INSET = 0.12          # UV 内缩比例
FACES = ("north", "south", "east", "west", "up", "down")
FACE_NORMAL = {"north": (0, 0, -1), "south": (0, 0, 1), "east": (1, 0, 0),
               "west": (-1, 0, 0), "up": (0, 1, 0), "down": (0, -1, 0)}


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


def make_camera(yaw_deg, pitch_deg, scale, cx, cy):
    yr, pr = math.radians(yaw_deg), math.radians(pitch_deg)

    def project(p):
        x = p[0] * math.cos(yr) - p[2] * math.sin(yr)
        z = p[0] * math.sin(yr) + p[2] * math.cos(yr)
        y = p[1]
        sy = y * math.cos(pr) - z * math.sin(pr)
        depth = y * math.sin(pr) + z * math.cos(pr)
        return (cx + x * scale, cy - sy * scale, depth)

    return project


def collect_faces():
    with open(MODEL, encoding="utf-8") as f:
        model = json.load(f)
    out = []
    for e in model["elements"]:
        low, high = e["from"], e["to"]
        rot = e.get("rotation")
        for face, data in e["faces"].items():
            quad = face_quad(low, high, face)
            if rot:
                quad = [rotate_point(p, rot["origin"], rot["axis"], rot["angle"]) for p in quad]
            out.append((quad, data["uv"], face))
    return out


def sample(tex, u, v):
    tw, th = tex.size
    x = min(max(int(u), 0), tw - 1)
    y = min(max(int(round(th - v)), 0), th - 1)
    return tex.getpixel((x, y))


def tri(buf, p0, p1, p2, uv0, uv1, uv2, tex):
    (x0, y0), (x1, y1), (x2, y2) = p0, p1, p2
    minx, maxx = max(0, int(min(x0, x1, x2))), min(buf.width - 1, int(max(x0, x1, x2)) + 1)
    miny, maxy = max(0, int(min(y0, y1, y2))), min(buf.height - 1, int(max(y0, y1, y2)) + 1)
    den = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
    if abs(den) < 1e-9:
        return
    for py in range(miny, maxy + 1):
        for px in range(minx, maxx + 1):
            gx, gy = px + 0.5, py + 0.5
            w0 = ((y1 - y2) * (gx - x2) + (x2 - x1) * (gy - y2)) / den
            w1 = ((y2 - y0) * (gx - x2) + (x0 - x2) * (gy - y2)) / den
            w2 = 1 - w0 - w1
            if w0 < -0.004 or w1 < -0.004 or w2 < -0.004:
                continue
            # UV 内缩：避免采到面边缘以外的透明像素
            uu = uv0[0] * w0 + uv1[0] * w1 + uv2[0] * w2
            vv = uv0[1] * w0 + uv1[1] * w1 + uv2[1] * w2
            r, g, b, a = sample(tex, uu, vv)
            if a > 8:
                buf.putpixel((px, py), (r, g, b, 255))


def render_view(faces, tex, yaw, pitch, scale=None):
    img = Image.new("RGBA", (TILE, TILE), (30, 32, 38, 255))
    scale = scale or 22.0
    proj = make_camera(yaw, pitch, scale, TILE / 2, TILE * 0.60)

    prepared = []
    for quad, uv, face in faces:
        pts = [proj(p) for p in quad]
        depth = sum(p[2] for p in pts) / 4.0
        prepared.append((depth, pts, uv))
    prepared.sort(key=lambda q: q[0])

    for _, pts, uv in prepared:
        u1, v1, u2, v2 = uv
        u1, u2 = u1 + (u2 - u1) * INSET, u2 - (u2 - u1) * INSET
        v1, v2 = v1 + (v2 - v1) * INSET, v2 - (v2 - v1) * INSET
        # uv(0..16) → 像素(0..64)
        P = [(u1 * 4, v1 * 4), (u2 * 4, v1 * 4), (u2 * 4, v2 * 4), (u1 * 4, v2 * 4)]
        # 注意：(u,v) 的 v 是"从贴图顶部往下"，sample() 里已经翻成图片坐标
        pts2 = [(p[0], p[1]) for p in pts]
        tri(img, pts2[0], pts2[1], pts2[2], P[0], P[1], P[2], tex)
        tri(img, pts2[0], pts2[2], pts2[3], P[0], P[2], P[3], tex)

    # 方块边界参考
    d = ImageDraw.Draw(img)
    box = [proj((0, 0, 0))[:2], proj((16, 0, 0))[:2], proj((16, 0, 16))[:2], proj((0, 0, 16))[:2]]
    d.line([*box, box[0]], fill=(255, 90, 90, 220), width=1)
    return img


def main():
    tex = Image.open(TEX).convert("RGBA")
    faces = collect_faces()
    views = [(35, 30), (125, 30), (215, 30), (35, 65)]
    tiles = [render_view(faces, tex, y, p) for y, p in views]
    canvas = Image.new("RGBA", (TILE * len(tiles) + 8 * (len(tiles) - 1) + 1, TILE + 26), (18, 19, 24, 255))
    for i, t in enumerate(tiles):
        canvas.paste(t, (i * (TILE + 8), 26))
    d = ImageDraw.Draw(canvas)
    for i, (y, p) in enumerate(views):
        d.text((i * (TILE + 8) + 8, 8), "yaw %d  pitch %d" % (y, p), fill=(230, 230, 230, 255))
    canvas.save(OUT)
    print("已输出：%s" % OUT)
    print("  面数 %d" % len(faces))


if __name__ == "__main__":
    main()
