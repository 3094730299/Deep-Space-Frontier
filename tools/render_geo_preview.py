# -*- coding: utf-8 -*-
"""
把 GeckoLib 的 geo.json + 贴图离屏渲染成 PNG，用来"亲眼确认"模型长什么样。

    python tools/render_geo_preview.py

做法：正交等距投影 + 逐三角形光栅化 + 重心坐标插值 UV + z-buffer。
每个面按 Bedrock/glTF 的 UV 约定（V 轴向上）采样贴图 —— 和 GeckoLib 一致。
这样不用进游戏就能看出：模型是否居中、有没有部件缺失、UV 对不对。

输出：tools/_preview_geo.png
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
OUT = os.path.join(HERE, "_preview_geo.png")

SCALE = 18.0
W, H = 900, 900
SS = 2  # 超采样倍数（先放大画再缩小，边缘干净些）


def project(x, y, z):
    sx = (x - z) * 0.8660254
    sy = (x + z) * 0.5 - y
    return sx * SCALE + W / 2, sy * SCALE + H / 2


FACES = ("north", "south", "west", "east", "up", "down")


def face_vertices(o, s, face):
    x, y, z = o
    sx, sy, sz = s
    if face == "north":
        return [(x, y, z), (x + sx, y, z), (x + sx, y + sy, z), (x, y + sy, z)]
    if face == "south":
        return [(x, y, z + sz), (x, y + sy, z + sz), (x + sx, y + sy, z + sz), (x + sx, y, z + sz)]
    if face == "west":
        return [(x, y, z), (x, y + sy, z), (x, y + sy, z + sz), (x, y, z + sz)]
    if face == "east":
        return [(x + sx, y, z), (x + sx, y, z + sz), (x + sx, y + sy, z + sz), (x + sx, y + sy, z)]
    if face == "up":
        return [(x, y + sy, z), (x, y + sy, z + sz), (x + sx, y + sy, z + sz), (x + sx, y + sy, z)]
    return [(x, y, z), (x + sx, y, z), (x + sx, y, z + sz), (x, y, z + sz)]


def face_uvs(uv):
    """[u1,v1,u2,v2]（V 向上）→ 和 face_vertices 顺序对应的 4 组 UV"""
    u1, v1, u2, v2 = uv
    return [(u1, v1), (u2, v1), (u2, v2), (u1, v2)]


def sample(tex, u, v):
    tw, th = tex.size
    x = min(max(int(u), 0), tw - 1)
    y = min(max(int(round(th - v)), 0), th - 1)
    return tex.getpixel((x, y))


def raster_triangle(buf, p0, p1, p2, uv0, uv1, uv2, tex):
    (x0, y0), (x1, y1), (x2, y2) = p0, p1, p2
    minx = max(0, int(min(x0, x1, x2)))
    maxx = min(buf.width - 1, int(max(x0, x1, x2)) + 1)
    miny = max(0, int(min(y0, y1, y2)))
    maxy = min(buf.height - 1, int(max(y0, y1, y2)) + 1)
    denom = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
    if abs(denom) < 1e-9:
        return
    for py in range(miny, maxy + 1):
        for px in range(minx, maxx + 1):
            cx, cy = px + 0.5, py + 0.5
            w0 = ((y1 - y2) * (cx - x2) + (x2 - x1) * (cy - y2)) / denom
            w1 = ((y2 - y0) * (cx - x2) + (x0 - x2) * (cy - y2)) / denom
            w2 = 1.0 - w0 - w1
            if w0 < -0.002 or w1 < -0.002 or w2 < -0.002:
                continue
            u = uv0[0] * w0 + uv1[0] * w1 + uv2[0] * w2
            v = uv0[1] * w0 + uv1[1] * w1 + uv2[1] * w2
            r, g, b, a = sample(tex, u, v)
            if a <= 16:
                continue
            buf.putpixel((px, py), (r, g, b, 255))


def main():
    import sys
    keep = sys.argv[1:] if len(sys.argv) > 1 else None

    with open(GEO, encoding="utf-8") as f:
        geo = json.load(f)["minecraft:geometry"][0]
    tex = Image.open(TEX).convert("RGBA")

    quads = []
    used = {}

    def walk(bone, path):
        name = bone.get("name") or "?"
        if keep and name not in keep and path not in keep:
            for ch in bone.get("children", []) or []:
                walk(ch, path + "/" + (ch.get("name") or "?"))
            return
        for c in bone.get("cubes", []):
            o, s = c["origin"], c["size"]
            for face in FACES:
                entry = (c.get("uv") or {}).get(face)
                if not entry:
                    continue
                world = face_vertices(o, s, face)
                verts = [project(*v) for v in world]
                depth = sum(v[0] + v[1] + v[2] for v in world) / 4.0
                quads.append((depth, verts, face_uvs(entry["uv"])))
                used[name] = used.get(name, 0) + 1
        for ch in bone.get("children", []) or []:
            walk(ch, path + "/" + (ch.get("name") or "?"))

    for b in geo["bones"]:
        walk(b, b.get("name") or "?")
    quads.sort(key=lambda q: q[0])

    img = Image.new("RGBA", (W, H), (24, 26, 32, 255))
    for _, verts, uvs in quads:
        raster_triangle(img, verts[0], verts[1], verts[2], uvs[0], uvs[1], uvs[2], tex)
        raster_triangle(img, verts[0], verts[2], verts[3], uvs[0], uvs[2], uvs[3], tex)

    draw = ImageDraw.Draw(img)
    box = [project(0, 0, 0), project(16, 0, 0), project(16, 0, 16), project(0, 0, 16)]
    draw.line([*box, box[0]], fill=(255, 90, 90, 255), width=2)
    c0 = project(8, 0, 8)
    draw.line([c0[0] - 8, c0[1], c0[0] + 8, c0[1]], fill=(120, 200, 255, 255), width=1)
    draw.line([c0[0], c0[1] - 8, c0[0], c0[1] + 8], fill=(120, 200, 255, 255), width=1)

    out = OUT if not keep else OUT.replace(".png", "_" + "_".join(keep) + ".png")
    img.save(out)
    print("已输出预览：%s" % out)
    print("  参与渲染的面（按骨骼）: %s" % used)


if __name__ == "__main__":
    main()
