# -*- coding: utf-8 -*-
"""
导入老板的「星联动力联盟商店」模型（工厂终端 · 星联动力联盟专用 · 三个等级共用）。

    python tools/import_astralink_shop_model.py

源文件（老板导出）
    半成品\\动态建模\\星联动力联盟商店\\星联动力联盟商店.geo.json
    半成品\\动态建模\\星联动力联盟商店\\星联动力联盟商店.animation.json
    模板\\星联动力联盟商店.bbmodel        ← 贴图内嵌在里面（base64）

装进工程（新的模型名 = terminal_astralink）
    assets/deepspacedeployer/geo/block/terminal_astralink.geo.json
    assets/deepspacedeployer/animations/block/terminal_astralink.animation.json
    assets/deepspacedeployer/textures/block/terminal_astralink.png

## 三个容易踩的点（脚本里都处理了）

1. **`format_version` 必须 1.12.0** —— 更高版本 GeckoLib 会静默拒绝，模型能显示但没动画；
2. **`texture_width/height` 一个字都不能改** —— 它是 UV 坐标系的除数，
   改了会让采样区域整体错位、整片花屏（见 docs/设计决策.md §2.57）；
   贴图可以比它大（整数倍即可）：声明 256 + 贴图 256(1×) 或 512(2×) 都对。
3. **动画里 `"0.0"` 必须是每个通道的第一项** —— 否则 GeckoLib 抛
   `Invalid keyframe data - multiple starting keyframes?` 并且**整份文件作废**。
"""
import base64
import io
import json
import os
import shutil
import sys

from PIL import Image

ROOT = r"C:\Users\30947\Documents\deepseek-harness\default-workspace\deep-space-deployer"
A = os.path.join(ROOT, "src", "main", "resources", "assets", "deepspacedeployer")
SRC = r"C:\Users\30947\Desktop\建模计划"

SRC_DIR = os.path.join(SRC, r"半成品\动态建模\星联动力联盟商店")
GEO_SRC = os.path.join(SRC_DIR, "星联动力联盟商店.geo.json")
ANIM_SRC = os.path.join(SRC_DIR, "星联动力联盟商店.animation.json")
BB_SRC = os.path.join(SRC, r"模板\星联动力联盟商店.bbmodel")

NAME = "terminal_astralink"


def ratio_ok(png, decl):
    rx, ry = png[0] / float(decl[0]), png[1] / float(decl[1])
    return (abs(rx - round(rx)) < 1e-6 and abs(ry - round(ry)) < 1e-6
            and round(rx) >= 1 and round(ry) >= 1), (int(round(rx)), int(round(ry)))


def is_exact_upscale(im, factor):
    """这张图是不是某张小图的【整数倍最近邻放大】？是的话可以安全缩回去（无损）"""
    w, h = im.size
    if w % factor or h % factor:
        return None
    small = im.resize((w // factor, h // factor), Image.NEAREST)
    back = small.resize((w, h), Image.NEAREST)
    return small if list(back.getdata()) == list(im.getdata()) else None


def check_animations(anim):
    """按项目规矩检查关键帧顺序：0.0 必须在每个通道的第一项"""
    bad = []
    for aname, adata in anim.get("animations", {}).items():
        for bone, bdata in adata.get("bones", {}).items():
            for chan, cdata in bdata.items():
                if not isinstance(cdata, dict):
                    continue
                keys = list(cdata.keys())
                ts = [float(k) for k in keys]
                if 0.0 in ts and ts.index(0.0) != 0:
                    bad.append((aname, bone, chan, keys))
                elif ts != sorted(ts):
                    bad.append((aname, bone, chan, keys))
    return bad


def main():
    for p in (GEO_SRC, ANIM_SRC, BB_SRC):
        if not os.path.exists(p):
            print("× 找不到源文件：%s" % p)
            return 1

    # ---------------- 1) geo ----------------
    geo = json.load(io.open(GEO_SRC, encoding="utf-8"))
    g = geo["minecraft:geometry"][0]
    desc = g["description"]
    decl = (desc.get("texture_width"), desc.get("texture_height"))
    old_fv = geo.get("format_version")
    geo["format_version"] = "1.12.0"
    desc["identifier"] = "geometry." + NAME

    # UV 上限（提醒用：超过声明就是模型自己的 UV 越界）
    uv_max = 0.0

    def walk(bones):
        nonlocal uv_max
        for b in bones:
            for c in b.get("cubes", []) or []:
                uv = c.get("uv")
                size = c.get("size", [0, 0, 0])
                if isinstance(uv, list) and len(uv) == 2:
                    uv_max = max(uv_max, float(uv[0]) + float(size[0]),
                                 float(uv[1]) + float(size[2]))
                elif isinstance(uv, dict):
                    for face in uv.values():
                        if isinstance(face, dict):
                            u = face.get("uv", [0, 0])
                            s = face.get("uv_size", [0, 0])
                            uv_max = max(uv_max, float(u[0]) + abs(float(s[0])),
                                         float(u[1]) + abs(float(s[1])))
            walk(b.get("children", []) or [])

    walk(g.get("bones", []))

    geo_out = os.path.join(A, "geo", "block", NAME + ".geo.json")
    os.makedirs(os.path.dirname(geo_out), exist_ok=True)
    with io.open(geo_out, "w", encoding="utf-8") as f:
        json.dump(geo, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print("① 模型   → %s" % os.path.relpath(geo_out, ROOT))
    print("   format_version %s → 1.12.0" % old_fv)
    print("   贴图声明 %sx%s（**未改动**）；UV 上限 %.0f %s"
          % (decl[0], decl[1], uv_max,
             "✓" if uv_max <= max(decl) else "⚠️ 越界！"))

    # ---------------- 2) animation ----------------
    anim = json.load(io.open(ANIM_SRC, encoding="utf-8"))
    bad = check_animations(anim)
    anim_out = os.path.join(A, "animations", "block", NAME + ".animation.json")
    os.makedirs(os.path.dirname(anim_out), exist_ok=True)
    with io.open(anim_out, "w", encoding="utf-8") as f:
        json.dump(anim, f, indent=2, ensure_ascii=False)
        f.write("\n")
    names = list(anim.get("animations", {}).keys())
    print("② 动画   → %s" % os.path.relpath(anim_out, ROOT))
    print("   动画：%s" % "、".join("%s(%ss, %s)" % (
        n, anim["animations"][n].get("animation_length"),
        anim["animations"][n].get("loop")) for n in names))
    if bad:
        print("   × 关键帧顺序有问题（GeckoLib 会整份拒收）：")
        for aname, bone, chan, keys in bad:
            print("      %s / %s / %s → %s" % (aname, bone, chan, keys))
        print("      修法：跑 python tools\\check_gecko_animations.py --fix")
    else:
        print("   关键帧顺序 ✓（每个通道 0.0 都在第一项）")

    # ---------------- 3) 贴图（从 bbmodel 里取内嵌的那张）----------------
    bb = json.load(io.open(BB_SRC, encoding="utf-8"))
    src_png = None
    for t in bb.get("textures", []):
        src = t.get("source", "")
        if isinstance(src, str) and src.startswith("data:"):
            src_png = base64.b64decode(src.split(",", 1)[1])
            break
    if src_png is None:
        print("× bbmodel 里没有内嵌贴图（导出时是不是没勾选「嵌入贴图」？）")
        return 1

    im = Image.open(io.BytesIO(src_png)).convert("RGBA")
    ok, mult = ratio_ok(im.size, decl)
    if not ok:
        print("× 贴图 %sx%s 不是声明 %sx%s 的整数倍 —— 会花屏，先处理贴图"
              % (im.size[0], im.size[1], decl[0], decl[1]))
        return 1

    # 如果这张图只是"某张小图的整数倍放大"，就缩回去（无损，且更贴近声明）
    note = ""
    if mult[0] > 1 and mult[0] == mult[1]:
        small = is_exact_upscale(im, mult[0])
        if small is not None:
            note = "（检测到它是 %d× 最近邻放大的，已无损缩回 %sx%s）" % (
                mult[0], small.size[0], small.size[1])
            im = small

    tex_out = os.path.join(A, "textures", "block", NAME + ".png")
    os.makedirs(os.path.dirname(tex_out), exist_ok=True)
    if os.path.exists(tex_out):                     # 换图前留个备份
        bdir = os.path.join(os.path.dirname(tex_out), "_orig")
        os.makedirs(bdir, exist_ok=True)
        shutil.copyfile(tex_out, os.path.join(bdir, NAME + ".png"))
    im.save(tex_out)
    ok2, mult2 = ratio_ok(im.size, decl)
    print("③ 贴图   → %s" % os.path.relpath(tex_out, ROOT))
    print("   %dx%d，颜色 %d 种，对声明 %sx%s 是 %d× ✓ %s"
          % (im.size[0], im.size[1], len(set(im.getdata())),
             decl[0], decl[1], mult2[0], note))

    print()
    print("导入完成。接下来必跑：")
    print("   python tools\\check_gecko_textures.py      # 贴图尺寸 vs 声明")
    print("   python tools\\check_gecko_animations.py    # 动画关键帧规则")
    print("   python tools\\validate.py                  # 工程自检")
    return 0


if __name__ == "__main__":
    sys.exit(main())
