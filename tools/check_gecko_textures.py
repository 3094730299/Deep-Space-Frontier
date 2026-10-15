# -*- coding: utf-8 -*-
"""
GeckoLib 模型自检 + 自动修复：**贴图尺寸必须和 geo 声明的 UV 空间一致**。

    python tools/check_gecko_textures.py            # 只检查
    python tools/check_gecko_textures.py --fix      # 检查并修复（缩放 PNG）

为什么必须一致：
  geo.json 里的 `texture_width` / `texture_height` 是 **UV 坐标空间**，
  不是 PNG 的像素尺寸。两者不一致时 GeckoLib 贴图会错乱 ——
  **表现就是黑紫格**（磨制黑石 + 紫？那是"贴图缺失/UV 越界"的典型样子）。

⚠️ 修法是【缩放 PNG 去匹配声明】，**绝不能改声明** ——
   改声明会让所有 UV 坐标错位，模型会变成一堆乱色块。
   （收购投送机就是这么踩过一次：声明 256、PNG 512。）
"""
import io
import glob
import json
import os
import sys

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
A = os.path.join(ROOT, "src", "main", "resources", "assets", "deepspacedeployer")

# 原版方块模型的贴图（按公司 × 等级 × 亮/灭 分），不归 GeckoLib 管，排除掉
SKIP_SUFFIX_TUPLE = ("_lit.png", "_high.png", "_mid.png", "_low.png")


def main():
    do_fix = "--fix" in sys.argv
    problems = []
    print("%-24s %-10s %-10s %-10s %s" % ("geo", "声明UV", "PNG实际", "UV上限", "状态"))
    print("-" * 74)

    geo_dir = os.path.join(A, "geo", "block")
    if not os.path.isdir(geo_dir):
        print("没有 geo 目录：%s" % geo_dir)
        return 1

    for f in sorted(os.listdir(geo_dir)):
        if not f.endswith(".geo.json"):
            continue
        base = f[:-len(".geo.json")]
        gp = os.path.join(geo_dir, f)
        g = json.load(io.open(gp, encoding="utf-8"))["minecraft:geometry"][0]
        dw = g["description"].get("texture_width")
        dh = g["description"].get("texture_height")

        # UV 上限（看有没有超出声明的空间）
        uv_max = 0.0

        def walk(bs):
            nonlocal uv_max
            for b in bs:
                for c in b.get("cubes", []) or []:
                    uv = c.get("uv")
                    if isinstance(uv, list) and len(uv) == 2:
                        uv_max = max(uv_max, float(uv[0]) + float(c["size"][0]),
                                     float(uv[1]) + float(c["size"][1]))
                walk(b.get("children", []) or [])

        walk(g["bones"])

        # 这个 geo 用的贴图：可能是 base.png，也可能是 base_<公司>.png（按公司分贴图）
        cands = [os.path.join(A, "textures", "block", base + ".png")]
        cands += sorted(p for p in glob.glob(os.path.join(A, "textures", "block", base + "_*.png"))
                        if not p.endswith(SKIP_SUFFIX_TUPLE))
        cands = [p for p in cands if os.path.exists(p)]
        if not cands:
            print("%-24s %-10s %-10s %-10s %s" % (base, "%sx%s" % (dw, dh), "（无）", "%.0f" % uv_max,
                                                  "✗ 贴图不存在"))
            problems.append((base, None, dw, dh, None, uv_max))
            continue

        bad = []
        sizes = set()
        for p in cands:
            im = Image.open(p)
            sizes.add(im.size)
            if im.size != (dw, dh):
                bad.append((p, im))

        size_txt = "、".join("%sx%s" % s for s in sorted(sizes))
        print("%-24s %-10s %-10s %-10s %s" % (
            base, "%sx%s" % (dw, dh), size_txt, "%.0f" % uv_max,
            "✓" if not bad else "✗ %d 张不一致 → 黑紫" % len(bad)))
        if uv_max > max(dw, dh):
            print("    ⚠️ UV 上限 %.0f 超过声明的 %s —— 模型的 UV 本身越界，也要改" % (uv_max, max(dw, dh)))
        if bad:
            problems.append((base, cands, dw, dh, bad, uv_max))

    print()
    if not problems:
        print("全部一致 ✓")
        return 0

    print("发现 %d 个问题：" % len(problems))
    for base, cands, dw, dh, bad, _uv in problems:
        if bad is None:
            print("   %s：贴图文件不存在（geo 声明 %sx%s）" % (base, dw, dh))
        else:
            print("   %s：%d 张要缩到 %sx%s" % (base, len(bad), dw, dh))

    if not do_fix:
        print()
        print("加 --fix 参数即可自动修复（缩放 PNG 去匹配声明）")
        return 1

    print()
    for base, cands, dw, dh, bad, _uv in problems:
        if not bad:
            continue
        for p, im in bad:
            # 备份：放一个子目录，避免扩展名问题
            bdir = os.path.join(os.path.dirname(p), "_orig")
            os.makedirs(bdir, exist_ok=True)
            backup = os.path.join(bdir, os.path.basename(p))
            if not os.path.exists(backup):
                im.save(backup)
            # 像素画必须用 NEAREST（最近邻）——LANCZOS 会把像素画糊掉
            # 整数倍缩放时 NEAREST 是无损的
            im.convert("RGBA").resize((dw, dh), Image.NEAREST).save(p)
            print("   修复 %-34s %s → %sx%s" % (os.path.basename(p), im.size, dw, dh))
    print("完成（原图备份在 textures/block/_orig/，可回退）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
