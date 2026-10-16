# -*- coding: utf-8 -*-
"""
GeckoLib 模型自检 + 自动修复：**PNG 尺寸必须是 geo 声明的【整数倍】**。

    python tools/check_gecko_textures.py            # 只检查
    python tools/check_gecko_textures.py --fix      # 检查并修复（把 PNG 重采样成整数倍）

## 规则（2026-10-15 用字节码确认后改正）

`geo.json` 的 `description.texture_width/height` 是 **UV 坐标系**，
GeckoLib 在 `GeoQuad.build` 里就是这么算的（`javap -p -c` 确认）：

    normalizedU = uv / texture_width      // uv 是 geo 里写的像素坐标
    normalizedV = v  / texture_height

所以只要两个条件同时成立就正确：

1. **UV 坐标不超过声明**（UV 上限 ≤ 声明值）；
2. **PNG 的宽高都是声明的整数倍**（1×、2×、3×… 都行）。

**PNG 比声明大是好事**：声明 64 + PNG 128 ⇒ 采样点 = (uv/64)×128，
落在原图的 2 倍位置，也就是**同一块图案、两倍分辨率**。

## ⚠️ 两个曾经踩过的坑（都真发生过）

* **把声明改成 PNG 尺寸** → UV 空间被改小（40/128 而不是 40/64），
  模型只采到一半区域 → **整片花屏**（通用终端就这么坏过一次）；
* **为了"对上"把 PNG 缩小** → 分辨率白丢一半（299 色 → 138 色），
  贴图肉眼变糊（老板原话："糊 / 糙 / 像素块很大"）。

**正确做法：声明保持不动，PNG 用高分辨率的那份（整数倍）。**
"""
import io
import glob
import json
import math
import os
import sys

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
A = os.path.join(ROOT, "src", "main", "resources", "assets", "deepspacedeployer")

# 原版方块模型的贴图（按公司 × 等级 × 亮/灭 分），不归 GeckoLib 管，排除掉
SKIP_SUFFIX_TUPLE = ("_lit.png", "_high.png", "_mid.png", "_low.png")


def ratio_ok(png, decl):
    """PNG 宽高是不是声明的整数倍（1×、2×…）。返回 (是否OK, 倍率文本)"""
    rx = png[0] / float(decl[0])
    ry = png[1] / float(decl[1])
    ok = (abs(rx - round(rx)) < 1e-6 and abs(ry - round(ry)) < 1e-6
          and round(rx) >= 1 and round(ry) >= 1)
    if not ok:
        return False, "%.2f×/%.2f×" % (rx, ry)
    rx, ry = int(round(rx)), int(round(ry))
    return True, ("%d×" % rx) if rx == ry else ("%d×/%d×" % (rx, ry))


def main():
    do_fix = "--fix" in sys.argv
    problems = []
    print("%-22s %-9s %-11s %-8s %-7s %s"
          % ("geo", "声明UV", "PNG实际", "倍率", "UV上限", "状态"))
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
        ratios = set()
        for p in cands:
            im = Image.open(p)
            sizes.add(im.size)
            ok, rt = ratio_ok(im.size, (dw, dh))
            ratios.add(rt)
            if not ok:
                bad.append((p, im, rt))

        size_txt = "、".join("%sx%s" % s for s in sorted(sizes))
        ratio_txt = "、".join(sorted(ratios))
        print("%-22s %-9s %-11s %-8s %-7s %s" % (
            base, "%sx%s" % (dw, dh), size_txt, ratio_txt, "%.0f" % uv_max,
            "✓" if not bad else "✗ %d 张不是整数倍 → 会花屏" % len(bad)))
        if uv_max > max(dw, dh):
            print("    ⚠️ UV 上限 %.0f 超过声明 %s —— 模型 UV 本身越界，也要改"
                  % (uv_max, max(dw, dh)))
        if bad:
            problems.append((base, cands, dw, dh, bad, uv_max))

    print()
    if not problems:
        print("全部合规 ✓（贴图尺寸都是声明的整数倍）")
        return 0

    print("发现 %d 个问题：" % len(problems))
    for base, cands, dw, dh, bad, _uv in problems:
        if bad is None:
            print("   %s：贴图文件不存在（geo 声明 %sx%s）" % (base, dw, dh))
        else:
            for p, im, rt in bad:
                print("   %s：%dx%d 对声明 %sx%s 是 %s（不是整数倍）"
                      % (os.path.basename(p), im.size[0], im.size[1], dw, dh, rt))

    if not do_fix:
        print()
        print("加 --fix 参数可自动修复：把 PNG 最近邻重采样到【最接近的整数倍】")
        print("（⚠️ 绝不改声明 —— 改声明会让 UV 空间错位，整片花屏）")
        return 1

    print()
    for base, cands, dw, dh, bad, _uv in problems:
        if not bad:
            continue
        for p, im, _rt in bad:
            # 备份：放一个子目录，避免扩展名问题
            bdir = os.path.join(os.path.dirname(p), "_orig")
            os.makedirs(bdir, exist_ok=True)
            backup = os.path.join(bdir, os.path.basename(p))
            if not os.path.exists(backup):
                im.save(backup)
            # 目标：最接近的整数倍（比如 100 对 64 → 2× = 128）。
            # 宁可放大也不缩小 —— 缩小会永久丢掉分辨率（这正是通用终端糊掉的原因）。
            rx = max(1, int(round(im.size[0] / float(dw))))
            ry = max(1, int(round(im.size[1] / float(dh))))
            target = (dw * rx, dh * ry)
            # 像素画必须用 NEAREST（最近邻）——LANCZOS 会把像素画糊掉
            im.convert("RGBA").resize(target, Image.NEAREST).save(p)
            print("   修复 %-34s %s → %s（%d×）"
                  % (os.path.basename(p), im.size, target, rx))
    print("完成（原图备份在 textures/block/_orig/，可回退）")
    print("⚠️ 没有动任何 geo 声明 —— 声明是 UV 坐标系，只能靠改 PNG 去适配它")
    return 0


if __name__ == "__main__":
    sys.exit(main())
