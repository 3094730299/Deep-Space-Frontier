# -*- coding: utf-8 -*-
"""
导入「收购投送机」模型（替换收购站的外观）。

    python tools/import_buyer_model.py

做什么：
  1. geo.json → assets/.../geo/block/buyer.geo.json（只改 identifier，贴图尺寸不动）
  2. animation.json → assets/.../animations/block/buyer.animation.json（原样复制）
  3. 生成**六家公司**的贴图变体：把模型里的"强调色"（橙色）换成各公司主题色
  4. 物品图标跟着重做
"""
import io
import json
import os
import shutil

from PIL import Image

ROOT = r"C:\Users\30947\Documents\deepseek-harness\default-workspace\deep-space-deployer"
A = os.path.join(ROOT, "src", "main", "resources", "assets", "deepspacedeployer")
SRC = r"C:\Users\30947\Desktop\建模计划\半成品\动态建模"
TEX_SRC = r"C:\Users\30947\Desktop\建模计划\建模贴图\收购投送机.png"
NAME = "buyer"

COMPANIES = [
    ("threshold", (232, 244, 255)),
    ("whisperbelt", (180, 124, 255)),
    ("brokenedge", (255, 90, 90)),
    ("skimmer", (255, 179, 71)),
    ("bedrock", (154, 140, 120)),
    ("astralink", (84, 200, 255)),
]


def dominant_accent(img):
    """找出贴图里的"强调色"：饱和度最高、且出现次数够多的那种颜色（模型的橙色）"""
    from collections import Counter
    px = img.load()
    counter = Counter()
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px[x, y]
            if a < 200:
                continue
            mx, mn = max(r, g, b), min(r, g, b)
            if mx == 0:
                continue
            sat = (mx - mn) / mx
            if sat > 0.45 and mx > 90:          # 够艳
                counter[(r, g, b)] += 1
    if not counter:
        return None
    # 取出现次数最多的前若干个，求平均
    top = counter.most_common(40)
    total = sum(count for _color, count in top)
    if total == 0:
        return None
    r = sum(color[0] * count for color, count in top) / total
    g = sum(color[1] * count for color, count in top) / total
    b = sum(color[2] * count for color, count in top) / total
    return (int(r), int(g), int(b))


def recolor(img, accent, target):
    """把 accent 附近的颜色按比例映射到 target（保留明暗关系）"""
    out = img.copy()
    px = out.load()
    ar, ag, ab = accent
    tr, tg, tb = target
    for y in range(out.height):
        for x in range(out.width):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            mx, mn = max(r, g, b), min(r, g, b)
            if mx == 0:
                continue
            sat = (mx - mn) / mx
            if sat < 0.35:
                continue                      # 灰阶部分不动
            # 距离强调色越近，替换越彻底
            d = abs(r - ar) + abs(g - ag) + abs(b - ab)
            if d > 240:
                continue
            k = max(0.0, 1.0 - d / 240.0)
            # 用原像素的亮度，套上目标色的色相
            lum = (r * 0.30 + g * 0.59 + b * 0.11) / 255.0
            nr = int(tr * lum)
            ng = int(tg * lum)
            nb = int(tb * lum)
            px[x, y] = (
                int(r + (nr - r) * k),
                int(g + (ng - g) * k),
                int(b + (nb - b) * k),
                a,
            )
    return out


def main():
    os.makedirs(os.path.join(A, "geo", "block"), exist_ok=True)
    os.makedirs(os.path.join(A, "animations", "block"), exist_ok=True)

    # ---------- 1) geo ----------
    geo = json.load(io.open(os.path.join(SRC, "收购投送机.geo.json"), encoding="utf-8"))
    g = geo["minecraft:geometry"][0]
    ident = g["description"].get("identifier")
    g["description"]["identifier"] = "geometry." + NAME
    tw, th = g["description"].get("texture_width"), g["description"].get("texture_height")
    fv = geo.get("format_version")
    if fv != "1.12.0":
        geo["format_version"] = "1.12.0"
    geo_out = os.path.join(A, "geo", "block", NAME + ".geo.json")
    io.open(geo_out, "w", encoding="utf-8", newline="").write(
        json.dumps(geo, ensure_ascii=False, indent=2))

    def cubes(bones):
        n = 0
        for b in bones:
            n += len(b.get("cubes", []))
            n += cubes(b.get("children", []) or [])
        return n

    print("geo   → %s" % os.path.relpath(geo_out, ROOT))
    print("      identifier %r → %r ｜ format_version %s → 1.12.0" % (ident, g["description"]["identifier"], fv))
    print("      骨骼 %d 个 / 立方体 %d 个 ｜ 贴图声明 %sx%s（未改动）"
          % (len(g["bones"]), cubes(g["bones"]), tw, th))

    # ---------- 2) animation ----------
    anim_src = os.path.join(SRC, "收购投送机.animation.json")
    anim_out = os.path.join(A, "animations", "block", NAME + ".animation.json")
    a = json.load(io.open(anim_src, encoding="utf-8"))
    io.open(anim_out, "w", encoding="utf-8", newline="").write(
        json.dumps(a, ensure_ascii=False, indent=2))
    print("anim  → %s" % os.path.relpath(anim_out, ROOT))
    for n, d in (a.get("animations") or {}).items():
        bones = list((d.get("bones") or {}).keys())
        print("      动画「%s」%s 秒 loop=%s ｜ %d 个骨骼（%s）"
              % (n, d.get("animation_length"), d.get("loop"), len(bones), "、".join(bones)))

    # ---------- 3) 六家公司贴图 ----------
    base_img = Image.open(TEX_SRC).convert("RGBA")
    accent = dominant_accent(base_img)
    print()
    print("贴图  → 原图 %s，强调色（模型里的橙色）= %s" % (base_img.size, accent))
    out_dir = os.path.join(A, "textures", "block")
    os.makedirs(out_dir, exist_ok=True)
    for cid, color in COMPANIES:
        img = base_img if (accent is None or cid == "_base") else recolor(base_img, accent, color)
        p = os.path.join(out_dir, "%s_%s.png" % (NAME, cid))
        img.save(p)
        print("      %-34s %s → %s" % (os.path.basename(p), accent, color))

    # ---------- 4) 物品图标（用第一家公司的配色即可） ----------
    icon_src = os.path.join(out_dir, "%s_%s.png" % (NAME, COMPANIES[0][0]))
    icon = Image.open(icon_src).convert("RGBA")
    bbox = icon.getbbox() or (0, 0, 16, 16)
    crop = icon.crop(bbox)
    w, h = crop.size
    sc = 14.0 / max(w, h)
    crop = crop.resize((max(1, round(w * sc)), max(1, round(h * sc))), Image.LANCZOS)
    canvas = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    canvas.paste(crop, ((16 - crop.width) // 2, (16 - crop.height) // 2))
    os.makedirs(os.path.join(A, "textures", "item"), exist_ok=True)
    icon_path = os.path.join(A, "textures", "item", NAME + ".png")
    canvas.save(icon_path)
    print()
    print("物品图标 → %s（%d 字节）" % (os.path.relpath(icon_path, ROOT), os.path.getsize(icon_path)))


if __name__ == "__main__":
    main()
