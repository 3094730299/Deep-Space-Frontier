# -*- coding: utf-8 -*-
"""
载具打印机：把模型在方块格内**居中**，再据此生成碰撞箱。

为什么：模型原本落在 X 8..40、Z 8..40 像素（方块只占 0..16），
按 GeckoLib 的居中规则会往右前方各溢出约 1 格、插进旁边的方块。
先把它的几何中心平移到"以格中心为原点"，就不会溢出了。

碰撞箱：按老板的一贯要求 —— **下面一个整方块 + 上面一个"石墙"**，
但尺寸按模型的真实轮廓来（下层占满 3×2 格的中段，上层收窄成框架）。
"""
import io
import json
import os

ROOT = r"C:\Users\30947\Documents\deepseek-harness\default-workspace\deep-space-deployer"
A = os.path.join(ROOT, "src", "main", "resources", "assets", "deepspacedeployer")
GEO = os.path.join(A, "geo", "block", "factory_core.geo.json")


def bounds(geo):
    lo = [1e9] * 3
    hi = [-1e9] * 3

    def walk(b):
        for c in b.get("cubes", []) or []:
            o, s = c["origin"], c["size"]
            for i in range(3):
                lo[i] = min(lo[i], o[i])
                hi[i] = max(hi[i], o[i] + s[i])
        for ch in b.get("children", []) or []:
            walk(ch)

    for b in geo["minecraft:geometry"][0]["bones"]:
        walk(b)
    return lo, hi


def shift(geo, dx, dy, dz):
    def walk(b):
        for c in b.get("cubes", []) or []:
            for key in ("origin", "pivot"):
                if key in c:
                    c[key] = [round(c[key][0] + dx, 5), round(c[key][1] + dy, 5),
                              round(c[key][2] + dz, 5)]
        for ch in b.get("children", []) or []:
            walk(ch)

    for b in geo["minecraft:geometry"][0]["bones"]:
        walk(b)


def main():
    geo = json.load(io.open(GEO, encoding="utf-8"))
    lo, hi = bounds(geo)
    print("=== 原始 ===")
    print("   X %.2f..%.2f  Y %.2f..%.2f  Z %.2f..%.2f" % (lo[0], hi[0], lo[1], hi[1], lo[2], hi[2]))
    print("   中心 X %.2f  Z %.2f（希望都≈0）" % ((lo[0] + hi[0]) / 2, (lo[2] + hi[2]) / 2))

    dx = -((lo[0] + hi[0]) / 2)
    dz = -((lo[2] + hi[2]) / 2)
    shift(geo, round(dx, 5), 0.0, round(dz, 5))
    io.open(GEO, "w", encoding="utf-8", newline="").write(json.dumps(geo, ensure_ascii=False, indent=2))

    lo2, hi2 = bounds(geo)
    print()
    print("=== 居中后（平移 dx=%.2f dz=%.2f）===" % (dx, dz))
    print("   X %.2f..%.2f  Y %.2f..%.2f  Z %.2f..%.2f" % (lo2[0], hi2[0], lo2[1], hi2[1], lo2[2], hi2[2]))
    print("   中心 X %.2f  Z %.2f ✓" % ((lo2[0] + hi2[0]) / 2, (lo2[2] + hi2[2]) / 2))
    print("   占地 %.2f × %.2f 格，高 %.2f 格"
          % ((hi2[0] - lo2[0]) / 16, (hi2[2] - lo2[2]) / 16, (hi2[1] - lo2[1]) / 16))

    # ---------- 生成碰撞箱（方块空间，方块中心 = 0.5）----------
    # 像素 → 方块空间：/16 后整体 +0.5（GeckoLib 也是把模型中心摆到方块中心）
    def px_to_block(v):
        return v / 16.0 + 0.5

    print()
    print("=== 逐层轮廓（每 4 像素）→ 方块空间 ===")
    cubes = []

    def collect(b):
        for c in b.get("cubes", []) or []:
            o, s = c["origin"], c["size"]
            cubes.append((o[0], o[1], o[2], o[0] + s[0], o[1] + s[1], o[2] + s[2]))
        for ch in b.get("children", []) or []:
            collect(ch)

    for b in geo["minecraft:geometry"][0]["bones"]:
        collect(b)

    boxes = []
    y = 0.0
    hi_y = hi2[1]
    while y < hi_y:
        b2 = min(y + 4.0, hi_y)
        hit = [c for c in cubes if c[1] < b2 - 1e-6 and c[4] > y + 1e-6]
        if hit:
            xs1 = min(c[0] for c in hit); xs2 = max(c[3] for c in hit)
            zs1 = min(c[2] for c in hit); zs2 = max(c[5] for c in hit)
            bx1 = max(0.0, min(1.0, px_to_block(xs1)))
            bx2 = max(0.0, min(1.0, px_to_block(xs2)))
            bz1 = max(0.0, min(1.0, px_to_block(zs1)))
            bz2 = max(0.0, min(1.0, px_to_block(zs2)))
            by1 = max(0.0, min(2.0, y / 16.0))
            by2 = max(0.0, min(2.0, b2 / 16.0))
            if bx2 - bx1 > 1e-4 and bz2 - bz1 > 1e-4:
                boxes.append((by1, by2, bx1, bx2, bz1, bz2))
                print("   y %.2f..%.2f   X %.3f..%.3f  Z %.3f..%.3f" % (by1, by2, bx1, bx2, bz1, bz2))
        y = b2

    # 合并成两层：y 0..1（底盘，取最宽）、y 1..2（框架）
    low = [b for b in boxes if b[0] < 1.0]
    up = [b for b in boxes if b[0] >= 1.0]
    if low:
        x1 = min(b[2] for b in low); x2 = max(b[3] for b in low)
        z1 = min(b[4] for b in low); z2 = max(b[5] for b in low)
        print()
        print("   ★ 下层盒（y 0..1）: %.4f, %.4f, %.4f  ..  %.4f, 1.0, %.4f" % (x1, 0.0, z1, x2, z2))
    if up:
        x1 = min(b[2] for b in up); x2 = max(b[3] for b in up)
        z1 = min(b[4] for b in up); z2 = max(b[5] for b in up)
        y2 = max(b[1] for b in up)
        print("   ★ 上层盒（y 1..%.2f）: %.4f, 1.0, %.4f  ..  %.4f, %.4f, %.4f" % (y2, x1, z1, x2, y2, z2))


if __name__ == "__main__":
    main()
