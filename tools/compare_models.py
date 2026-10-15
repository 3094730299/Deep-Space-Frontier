# -*- coding: utf-8 -*-
"""
并排渲染「源 .bbmodel」与「转换后的原版模型」，用来核对转换是否忠实。

    python tools/compare_models.py

源模型：直接用 .bbmodel 的元素 + 各自 rotation（自己算旋转矩阵）
转换后：读 models/block/universal_terminal.json（角度已按原版规则取反/烘焙）
两者都用**同一套**正投影 + 画家算法渲染，所以差异只可能来自转换本身。

输出：tools/_compare.png（左：源模型，右：转换后）
"""
import base64
import json
import math
import os

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
ASSETS = os.path.join(PROJECT, "src", "main", "resources", "assets", "deepspacedeployer")

SOURCE_BB = r"C:\Users\30947\Desktop\星舰计划\通用终端.bbmodel"
VANILLA = os.path.join(ASSETS, "models", "block", "universal_terminal.json")
TEX = os.path.join(ASSETS, "textures", "block", "universal_terminal.png")
OUT = os.path.join(HERE, "_compare.png")

TILE = 620
SCALE = 26.0
FACES = ("north", "south", "east", "west", "up", "down")


# ---------------- 数学 ----------------

def rot_mat(axis, deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    if axis == 0:
        return [[1, 0, 0], [0, c, -s], [0, s, c]]
    if axis == 1:
        return [[c, 0, s], [0, 1, 0], [-s, 0, c]]
    return [[c, -s, 0], [s, c, 0], [0, 0, 1]]


def mat_mul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def apply(m, v):
    return [sum(m[i][k] * v[k] for k in range(3)) for i in range(3)]


def project(p, cx, cy, yaw=35.264, pitch=30.0):
    """等距-ish 投影：先绕 y 转 yaw，再绕 x 转 pitch"""
    yr, pr = math.radians(yaw), math.radians(pitch)
    x = p[0] * math.cos(yr) - p[2] * math.sin(yr)
    z = p[0] * math.sin(yr) + p[2] * math.cos(yr)
    y = p[1]
    sy = y * math.cos(pr) - z * math.sin(pr)
    depth = y * math.sin(pr) + z * math.cos(pr)
    return cx + x * SCALE, cy - sy * SCALE, depth


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
            cx, cy = px + 0.5, py + 0.5
            w0 = ((y1 - y2) * (cx - x2) + (x2 - x1) * (cy - y2)) / den
            w1 = ((y2 - y0) * (cx - x2) + (x0 - x2) * (cy - y2)) / den
            w2 = 1 - w0 - w1
            if w0 < -0.003 or w1 < -0.003 or w2 < -0.003:
                continue
            u = uv0[0] * w0 + uv1[0] * w1 + uv2[0] * w2
            v = uv0[1] * w0 + uv1[1] * w1 + uv2[1] * w2
            r, g, b, a = sample(tex, u, v)
            if a > 16:
                buf.putpixel((px, py), (r, g, b, 255))


def draw_quads(quads, tex):
    img = Image.new("RGBA", (TILE, TILE), (28, 30, 36, 255))
    cx, cy = TILE / 2, TILE * 0.62
    prepared = []
    for low, high, face, uv in quads:
        pts3 = face_quad(low, high, face)
        proj = [project(p, cx, cy) for p in pts3]
        depth = sum(p[2] for p in proj) / 4.0
        prepared.append((depth, proj, uv))
    prepared.sort(key=lambda q: q[0])
    for _, proj, uv in prepared:
        (u1, v1, u2, v2) = uv
        uvs = [(u1 * 4, v1 * 4), (u2 * 4, v1 * 4), (u2 * 4, v2 * 4), (u1 * 4, v2 * 4)]
        pts2 = [(p[0], p[1]) for p in proj]
        tri(img, pts2[0], pts2[1], pts2[2], uvs[0], uvs[1], uvs[2], tex)
        tri(img, pts2[0], pts2[2], pts2[3], uvs[0], uvs[2], uvs[3], tex)
    return img


# ---------------- 两个来源 ----------------

def quads_from_bbmodel():
    with open(SOURCE_BB, encoding="utf-8") as f:
        bb = json.load(f)
    out = []
    for e in bb["elements"]:
        frm = [float(v) for v in e["from"]]
        to = [float(v) for v in e["to"]]
        low = [min(frm[i], to[i]) for i in range(3)]
        high = [max(frm[i], to[i]) for i in range(3)]
        rot = [float(v) for v in (e.get("rotation") or [0, 0, 0])]
        axes = [i for i, v in enumerate(rot) if abs(v) > 1e-6]
        origin = [float(v) for v in (e.get("origin") or e["from"])]
        for face, data in (e.get("faces") or {}).items():
            if not data.get("uv"):
                continue
            uv = [v / 4.0 for v in data["uv"]]   # 像素 → 0..16
            # 旋转顶点（源模型坐标系）
            pts = []
            for p in face_quad(low, high, face):
                v = [p[i] - origin[i] for i in range(3)]
                m = None
                for ax in axes:
                    mm = rot_mat(ax, rot[ax])
                    m = mm if m is None else mat_mul(mm, m)
                if m:
                    v = apply(m, v)
                pts.append([v[i] + origin[i] for i in range(3)])
            xs = [p[0] for p in pts]
            ys = [p[1] for p in pts]
            zs = [p[2] for p in pts]
            out.append(([min(xs), min(ys), min(zs)], [max(xs), max(ys), max(zs)], face, uv))
    return out


def quads_from_vanilla():
    with open(VANILLA, encoding="utf-8") as f:
        m = json.load(f)
    out = []
    for e in m["elements"]:
        low = list(e["from"])
        high = list(e["to"])
        for face, data in e["faces"].items():
            uv = [v / 4.0 for v in data["uv"]]   # 像素 → 0..16
            out.append((low, high, face, uv))
    return out


def main():
    tex = Image.open(TEX).convert("RGBA")
    left = draw_quads(quads_from_bbmodel(), tex)
    right = draw_quads(quads_from_vanilla(), tex)

    canvas = Image.new("RGBA", (TILE * 2 + 12, TILE + 34), (18, 19, 24, 255))
    canvas.paste(left, (0, 34))
    canvas.paste(right, (TILE + 12, 34))
    d = ImageDraw.Draw(canvas)
    d.text((10, 10), "SOURCE .bbmodel (raw)", fill=(255, 220, 120, 255))
    d.text((TILE + 22, 10), "CONVERTED vanilla json", fill=(140, 230, 160, 255))
    canvas.save(OUT)
    print("已输出对比图：%s" % OUT)
    print("  源模型面数 %d，转换后 element %d 个" % (len(quads_from_bbmodel()), len(json.load(
        open(VANILLA, encoding="utf-8"))["elements"])))


if __name__ == "__main__":
    main()
