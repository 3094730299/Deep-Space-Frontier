# -*- coding: utf-8 -*-
"""
渲染 GeckoLib 的 geo.json（正交 + 画家算法），用来检查 geo 本身对不对。

    python tools/render_geo.py

和渲染原版模型那个脚本同一套代码，区别只是：
  * 读 geo.json 的 bones/cubes（可以有父子嵌套）
  * 立方体的 rotation 绕它自己的 pivot 转
  * 贴图坐标就是 UV 的像素值（GeckoLib 与 Blockbench 同一套约定，不翻转、不缩放）
"""
import json
import math
import os

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
ASSETS = os.path.join(PROJECT, "src", "main", "resources", "assets", "deepspacedeployer")

GEO = os.path.join(ASSETS, "geo", "block", "universal_terminal.geo.json")
TEX = os.path.join(ASSETS, "textures", "block", "universal_terminal.png")
OUT = os.path.join(HERE, "_geo_render.png")

TILE = 560
INSET = 0.12
FACES = ("north", "south", "east", "west", "up", "down")


def face_quad(o, s, face):
    x1, y1, z1 = o
    x2, y2, z2 = o[0] + s[0], o[1] + s[1], o[2] + s[2]
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


def rotate_point(p, origin, rot):
    """绕 origin 依次转 x、y、z（角度制）"""
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
    with open(GEO, encoding="utf-8") as f:
        geo = json.load(f)["minecraft:geometry"][0]
    faces = []

    def walk(bone):
        for cube in bone.get("cubes", []):
            rot = cube.get("rotation") or [0, 0, 0]
            pivot = cube.get("pivot") or [0, 0, 0]
            for face, data in (cube.get("uv") or {}).items():
                quad = face_quad(cube["origin"], cube["size"], face)
                if any(abs(v) > 1e-9 for v in rot):
                    quad = [rotate_point(p, pivot, rot) for p in quad]
                faces.append((quad, data["uv"], face))
        for child in bone.get("children", []) or []:
            walk(child)

    for bone in geo["bones"]:
        walk(bone)
    return faces


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
            uu = uv0[0] * w0 + uv1[0] * w1 + uv2[0] * w2
            vv = uv0[1] * w0 + uv1[1] * w1 + uv2[1] * w2
            r, g, b, a = sample(tex, uu, vv)
            if a > 8:
                buf.putpixel((px, py), (r, g, b, 255))


def render_view(faces, tex, yaw, pitch, scale=22.0):
    img = Image.new("RGBA", (TILE, TILE), (30, 32, 38, 255))
    project = make_camera(yaw, pitch, scale, TILE / 2, TILE * 0.60)
    prepared = []
    for quad, uv, face in faces:
        pts = [project(p) for p in quad]
        prepared.append((sum(p[2] for p in pts) / 4.0, pts, uv))
    prepared.sort(key=lambda q: q[0])
    for _, pts, uv in prepared:
        u1, v1, u2, v2 = uv
        u1, u2 = u1 + (u2 - u1) * INSET, u2 - (u2 - u1) * INSET
        v1, v2 = v1 + (v2 - v1) * INSET, v2 - (v2 - v1) * INSET
        P = [(u1, v1), (u2, v1), (u2, v2), (u1, v2)]
        pts2 = [(p[0], p[1]) for p in pts]
        tri(img, pts2[0], pts2[1], pts2[2], P[0], P[1], P[2], tex)
        tri(img, pts2[0], pts2[2], pts2[3], P[0], P[2], P[3], tex)
    d = ImageDraw.Draw(img)
    box = [project((0, 0, 0))[:2], project((16, 0, 0))[:2],
           project((16, 0, 16))[:2], project((0, 0, 16))[:2]]
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
        d.text((i * (TILE + 8) + 8, 8), "yaw %d pitch %d" % (y, p), fill=(230, 230, 230, 255))
    canvas.save(OUT)
    print("已输出：%s（面数 %d）" % (OUT, len(faces)))


if __name__ == "__main__":
    main()
