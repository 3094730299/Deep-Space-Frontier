# -*- coding: utf-8 -*-
"""
导入「载具打印机」（替换工厂核心的外观，静止模型）。

    python tools/import_printer_model.py

要点（都是以前踩过的坑）：
  1. geo.json 的 format_version 必须 1.12.0；只改 identifier
  2. 贴图尺寸必须和 geo 声明的 UV 空间一致（256×256）——
     内嵌贴图是 512×512，要缩到 256，**不能**改声明
  3. 六家公司各生成一张变体（把强调色换成公司主题色）
"""
import io
import json
import os
import shutil

from PIL import Image

ROOT = r"C:\Users\30947\Documents\deepseek-harness\default-workspace\deep-space-deployer"
A = os.path.join(ROOT, "src", "main", "resources", "assets", "deepspacedeployer")
GEO_SRC = r"C:\Users\30947\Desktop\建模计划\半成品\静止建模\载具打印机.geo.json"
TEX_SRC = r"C:\Users\30947\Desktop\建模计划\建模贴图\载具打印机.png"
NAME = "factory_core"
UV_SPACE = 256

COMPANIES = [
    ("threshold", (232, 244, 255)),
    ("whisperbelt", (180, 124, 255)),
    ("brokenedge", (255, 90, 90)),
    ("skimmer", (255, 179, 71)),
    ("bedrock", (154, 140, 120)),
    ("astralink", (84, 200, 255)),
]


def dominant_accent(img):
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
            if (mx - mn) / mx > 0.45 and mx > 90:
                counter[(r, g, b)] += 1
    if not counter:
        return None
    top = counter.most_common(40)
    total = sum(c for _c, c in top)
    return (int(sum(c[0] * n for c, n in top) / total),
            int(sum(c[1] * n for c, n in top) / total),
            int(sum(c[2] * n for c, n in top) / total))


def recolor(img, accent, target):
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
            if mx == 0 or (mx - mn) / mx < 0.35:
                continue
            d = abs(r - ar) + abs(g - ag) + abs(b - ab)
            if d > 240:
                continue
            k = max(0.0, 1.0 - d / 240.0)
            lum = (r * 0.30 + g * 0.59 + b * 0.11) / 255.0
            px[x, y] = (int(r + (tr * lum - r) * k),
                        int(g + (tg * lum - g) * k),
                        int(b + (tb * lum - b) * k), a)
    return out


def main():
    # ---------- 1) geo ----------
    geo = json.load(io.open(GEO_SRC, encoding="utf-8"))
    g = geo["minecraft:geometry"][0]
    fv = geo.get("format_version")
    if fv != "1.12.0":
        geo["format_version"] = "1.12.0"
        print("  format_version: %s → 1.12.0" % fv)
    g["description"]["identifier"] = "geometry." + NAME
    tw = g["description"].get("texture_width")
    out = os.path.join(A, "geo", "block", NAME + ".geo.json")
    io.open(out, "w", encoding="utf-8", newline="").write(json.dumps(geo, ensure_ascii=False, indent=2))
    print("geo   → %s" % os.path.relpath(out, ROOT))
    print("      骨骼 %d，立方体 %d，贴图声明 %sx%s（未改动）"
          % (len(g["bones"]), sum(len(b.get("cubes", [])) for b in g["bones"]), tw,
             g["description"].get("texture_height")))

    # ---------- 2) 贴图（对齐 UV 空间） ----------
    base = Image.open(TEX_SRC).convert("RGBA")
    if base.width != UV_SPACE or base.height != UV_SPACE:
        print("贴图  %s → 缩放到 %dx%d（对齐 geo 的 UV 空间）" % (base.size, UV_SPACE, UV_SPACE))
        base = base.resize((UV_SPACE, UV_SPACE), Image.LANCZOS)
    accent = dominant_accent(base)
    print("      强调色（模型里的亮色）= %s" % (accent,))

    out_dir = os.path.join(A, "textures", "block")
    for cid, color in COMPANIES:
        img = base if accent is None else recolor(base, accent, color)
        p = os.path.join(out_dir, "%s_%s.png" % (NAME, cid))
        img.save(p)
    print("贴图  → 生成六家公司变体 %s_<公司>.png（%dx%d）" % (NAME, UV_SPACE, UV_SPACE))

    # ---------- 3) 物品图标 ----------
    icon_src = os.path.join(out_dir, "%s_%s.png" % (NAME, COMPANIES[0][0]))
    icon = Image.open(icon_src).convert("RGBA")
    bbox = icon.getbbox() or (0, 0, 16, 16)
    crop = icon.crop(bbox)
    w, h = crop.size
    sc = 14.0 / max(w, h)
    crop = crop.resize((max(1, round(w * sc)), max(1, round(h * sc))), Image.LANCZOS)
    canvas = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    canvas.paste(crop, ((16 - crop.width) // 2, (16 - crop.height) // 2))
    icon_path = os.path.join(A, "textures", "item", NAME + ".png")
    canvas.save(icon_path)
    print("图标  → %s（%d 字节）" % (os.path.relpath(icon_path, ROOT), os.path.getsize(icon_path)))

    # ---------- 4) 预览 ----------
    imgs = [("原图", Image.open(TEX_SRC).convert("RGBA").resize((160, 160), Image.LANCZOS)),
            ("256", base.resize((160, 160), Image.LANCZOS))]
    for cid, _ in COMPANIES:
        imgs.append((cid, Image.open(os.path.join(out_dir, "%s_%s.png" % (NAME, cid)))
                     .convert("RGBA").resize((160, 160), Image.LANCZOS)))
    W = len(imgs) * 168 + 8
    canvas2 = Image.new("RGBA", (W, 190), (40, 40, 48, 255))
    from PIL import ImageDraw
    dr = ImageDraw.Draw(canvas2)
    x = 8
    for label, im in imgs:
        canvas2.paste(im, (x, 8))
        dr.text((x, 172), label, fill=(230, 230, 230))
        x += 168
    preview = r"C:\Users\30947\Desktop\载具打印机_贴图对比.png"
    canvas2.save(preview)
    print()
    print("对比图 → %s" % preview)


if __name__ == "__main__":
    main()
