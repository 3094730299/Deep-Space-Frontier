# -*- coding: utf-8 -*-
"""
把 .obj 渲染成图片（用 obj 里的 v / vt / vn 原样画），用来确认转换是否忠实。

    python tools/render_obj.py <obj路径> [输出png路径]
"""
import os
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
TEX = os.path.join(PROJECT, "src", "main", "resources", "assets", "deepspacedeployer",
                   "textures", "block", "universal_terminal.png")
OUT = os.path.join(HERE, "_obj_render.png")

TILE = 560
INSET = 0.10
import math


def load_obj(path):
    verts, uvs, faces = [], [], []
    for line in open(path, encoding="utf-8", errors="replace"):
        parts = line.split()
        if not parts:
            continue
        if parts[0] == "v":
            verts.append(tuple(float(x) for x in parts[1:4]))
        elif parts[0] == "vt":
            uvs.append(tuple(float(x) for x in parts[1:3]))
        elif parts[0] == "f":
            idx = []
            for token in parts[1:]:
                bits = token.split("/")
                vi = int(bits[0]) - 1
                ti = int(bits[1]) - 1 if len(bits) > 1 and bits[1] else vi
                idx.append((vi, ti))
            faces.append(idx)
    return verts, uvs, faces


def make_camera(yaw, pitch, scale, cx, cy):
    yr, pr = math.radians(yaw), math.radians(pitch)

    def project(p):
        x = p[0] * 16.0 * math.cos(yr) - p[2] * 16.0 * math.sin(yr)
        z = p[0] * 16.0 * math.sin(yr) + p[2] * 16.0 * math.cos(yr)
        y = p[1] * 16.0
        sy = y * math.cos(pr) - z * math.sin(pr)
        depth = y * math.sin(pr) + z * math.cos(pr)
        return (cx + x * scale, cy - sy * scale, depth)

    return project


def sample(tex, u, v):
    tw, th = tex.size
    x = min(max(int(u * tw), 0), tw - 1)
    # 原版 UV 约定：v=0 在贴图顶部
    y = min(max(int(v * th), 0), th - 1)
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
            uu = uv0[0] * w0 + uv1[0] * w1 + uv2[0] * w2
            vv = uv0[1] * w0 + uv1[1] * w1 + uv2[1] * w2
            r, g, b, a = sample(tex, uu, vv)
            if a > 8:
                buf.putpixel((px, py), (r, g, b, 255))


def render(verts, uvs, faces, tex, yaw, pitch, scale=22.0):
    img = Image.new("RGBA", (TILE, TILE), (30, 32, 38, 255))
    project = make_camera(yaw, pitch, scale, TILE / 2, TILE * 0.60)
    prepared = []
    for quad in faces:
        pts = [project(verts[vi]) for vi, _ in quad]
        depth = sum(p[2] for p in pts) / len(pts)
        prepared.append((depth, pts, [uvs[ti] for _, ti in quad]))
    prepared.sort(key=lambda q: q[0])
    for _, pts, quv in prepared:
        us = [t[0] for t in quv]
        vs = [t[1] for t in quv]
        du, dv = (max(us) - min(us)) * INSET, (max(vs) - min(vs)) * INSET
        P = [(t[0] + (du if t[0] == min(us) else -du),
              t[1] + (dv if t[1] == min(vs) else -dv)) for t in quv]
        pts2 = [(p[0], p[1]) for p in pts]
        for i in range(2, len(pts2)):
            tri(img, pts2[0], pts2[i - 1], pts2[i], P[0], P[i - 1], P[i], tex)
    d = ImageDraw.Draw(img)
    box = [project((0.3125, 0, 0.3125))[:2], project((0.6875, 0, 0.3125))[:2],
           project((0.6875, 0, 0.6875))[:2], project((0.3125, 0, 0.6875))[:2]]
    d.line([*box, box[0]], fill=(255, 90, 90, 220), width=1)
    return img


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        PROJECT, "src", "main", "resources", "assets", "deepspacedeployer", "models", "obj",
        "universal_terminal.obj")
    out = sys.argv[2] if len(sys.argv) > 2 else OUT
    tex = Image.open(TEX).convert("RGBA")
    verts, uvs, faces = load_obj(path)
    views = [(35, 30), (125, 30), (215, 30), (35, 65)]
    tiles = [render(verts, uvs, faces, tex, y, p) for y, p in views]
    canvas = Image.new("RGBA", (TILE * len(tiles) + 8 * (len(tiles) - 1) + 1, TILE + 26), (18, 19, 24, 255))
    for i, t in enumerate(tiles):
        canvas.paste(t, (i * (TILE + 8), 26))
    d = ImageDraw.Draw(canvas)
    for i, (y, p) in enumerate(views):
        d.text((i * (TILE + 8) + 8, 8), "yaw %d pitch %d" % (y, p), fill=(230, 230, 230, 255))
    canvas.save(out)
    print("已输出 %s（v=%d vt=%d f=%d）" % (out, len(verts), len(uvs), len(faces)))


if __name__ == "__main__":
    main()
