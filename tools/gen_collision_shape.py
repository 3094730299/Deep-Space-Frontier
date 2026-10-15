# -*- coding: utf-8 -*-
"""
从 geo.json 几何算出通用终端的碰撞箱（分层的多个盒子）。

    python tools/gen_collision_shape.py

思路参考 impact_drill 的 HullCoreBlock：多个盒子 `Shapes.or` 合并成一个 VoxelShape。
这里按**高度分层**取包围盒，让碰撞箱贴合模型轮廓（底座细、立柱更细、托盘宽）。

坐标换算：模型像素是以原点为中心的，方块空间原点在西北下角 → x/z 各 +8；
Y 直接为像素值（底座在 y≈0）。最后 /16 转成 0..1。
"""
import io
import json
import os

ROOT = r"C:\Users\30947\Documents\deepseek-harness\default-workspace\deep-space-deployer"
GEO = os.path.join(ROOT, "src", "main", "resources", "assets", "deepspacedeployer",
                   "geo", "block", "universal_terminal.geo.json")
OUT = os.path.join(ROOT, "tools", "_collision_boxes.txt")


def main():
    geo = json.load(io.open(GEO, encoding="utf-8"))["minecraft:geometry"][0]
    cubes = []
    for bone in geo["bones"]:
        for c in (bone.get("cubes") or []):
            o, s = c["origin"], c["size"]
            cubes.append((o[0], o[1], o[2], o[0] + s[0], o[1] + s[1], o[2] + s[2]))

    lo_y = min(c[1] for c in cubes)
    hi_y = max(c[4] for c in cubes)
    print("模型 Y 范围: %.2f .. %.2f（像素）" % (lo_y, hi_y))
    print("共 %d 个立方体" % len(cubes))

    # 分层：用"水平截面的宽度"来决定每层的形状
    bands = []
    step = 1.0
    y = max(0.0, lo_y)
    while y < hi_y:
        bands.append((y, min(y + step, hi_y)))
        y += step

    print()
    print("=== 逐层水平截面（像素）===")
    profile = []
    for (a, b) in bands:
        hit = [c for c in cubes if c[1] < b - 1e-6 and c[4] > a + 1e-6]
        if not hit:
            profile.append((a, b, None))
            continue
        xs1 = min(c[0] for c in hit)
        xs2 = max(c[3] for c in hit)
        zs1 = min(c[2] for c in hit)
        zs2 = max(c[5] for c in hit)
        profile.append((a, b, (xs1, xs2, zs1, zs2)))
        print("  y %5.1f..%5.1f  X %7.2f..%6.2f  Z %7.2f..%6.2f  (%2d 个)" % (a, b, xs1, xs2, zs1, zs2, len(hit)))

    # 合并相邻的相同截面，减少盒子数量
    merged = []
    for (a, b, box) in profile:
        if box is None:
            continue
        key = tuple(round(v, 2) for v in box)
        if merged and merged[-1][3] == key:
            merged[-1] = (merged[-1][0], b, merged[-1][2], key)
        else:
            merged.append((a, b, box, key))

    print()
    print("=== 合并后 %d 层 ===" % len(merged))
    java = []
    for (a, b, box, key) in merged:
        xs1, xs2, zs1, zs2 = box
        x1 = max(0.0, min(1.0, (xs1 + 8.0) / 16.0))
        x2 = max(0.0, min(1.0, (xs2 + 8.0) / 16.0))
        z1 = max(0.0, min(1.0, (zs1 + 8.0) / 16.0))
        z2 = max(0.0, min(1.0, (zs2 + 8.0) / 16.0))
        y1 = max(0.0, min(1.0, a / 16.0))
        y2 = max(0.0, min(1.0, b / 16.0))
        if x2 - x1 < 1e-4 or z2 - z1 < 1e-4 or y2 - y1 < 1e-4:
            continue
        print("  y %5.2f..%5.2f  盒 %.4f,%.4f,%.4f .. %.4f,%.4f,%.4f"
              % (y1, y2, x1, y1, z1, x2, y2, z2))
        java.append("            Shapes.box(%.4f, %.4f, %.4f, %.4f, %.4f, %.4f),"
                    % (x1, y1, z1, x2, y2, z2))

    print()
    print("=== Java 片段（%d 个盒子）===" % len(java))
    for l in java:
        print(l)
    io.open(OUT, "w", encoding="utf-8", newline="").write("\n".join(java) + "\n")
    print()
    print("已写入 %s" % OUT)


if __name__ == "__main__":
    main()
