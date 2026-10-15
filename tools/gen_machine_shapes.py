# -*- coding: utf-8 -*-
"""
完全按建模生成碰撞箱（逐层轮廓，含超出方格的部分）。

    python tools/gen_machine_shapes.py

坐标换算（GeckoLib 的 preRender 做 translate(0.5, 0, 0.5)，已从字节码确认）：
    方块空间 x = 像素x/16 + 0.5
    方块空间 y = 像素y/16          （0 = 方块底面）
    方块空间 z = 像素z/16 + 0.5

原版允许碰撞形状超出方格：BlockCollisions 的游标向外扩 1 格。
"""
import io
import json
import os

ROOT = r"C:\Users\30947\Documents\deepseek-harness\default-workspace\deep-space-deployer"
A = os.path.join(ROOT, "src", "main", "resources", "assets", "deepspacedeployer")

MODELS = [
    ("BUYER", "buyer.geo.json", "收购投送机"),
    ("CORE", "factory_core.geo.json", "载具打印机"),
]
STEP = 3.0        # 层厚（像素）
R = 4             # 轮廓四舍五入位数（用于合并相邻同轮廓层）


def cubes_of(path):
    g = json.load(io.open(path, encoding="utf-8"))["minecraft:geometry"][0]
    out = []

    def walk(b):
        for c in b.get("cubes", []) or []:
            o, s = c["origin"], c["size"]
            out.append((o[0], o[1], o[2], o[0] + s[0], o[1] + s[1], o[2] + s[2]))
        for ch in b.get("children", []) or []:
            walk(ch)

    for b in g["bones"]:
        walk(b)
    return out


def main():
    result = {}
    for enum, geo, cn in MODELS:
        cubes = cubes_of(os.path.join(A, "geo", "block", geo))
        lo_y = min(c[1] for c in cubes)
        hi_y = max(c[4] for c in cubes)
        lo_x = min(c[0] for c in cubes); hi_x = max(c[3] for c in cubes)
        lo_z = min(c[2] for c in cubes); hi_z = max(c[5] for c in cubes)

        print("=" * 78)
        print("%s（%s）" % (cn, enum))
        print("=" * 78)
        print("  模型像素  X %.2f..%.2f   Y %.2f..%.2f   Z %.2f..%.2f"
              % (lo_x, hi_x, lo_y, hi_y, lo_z, hi_z))
        print("  方块空间  X %.3f..%.3f   Y %.3f..%.3f   Z %.3f..%.3f"
              % (lo_x / 16 + 0.5, hi_x / 16 + 0.5, lo_y / 16, hi_y / 16,
                 lo_z / 16 + 0.5, hi_z / 16 + 0.5))
        print()

        # 逐层扫描：从模型最低到最高，每 STEP 像素一层
        layers = []
        y = lo_y
        while y < hi_y:
            y2 = min(y + STEP, hi_y)
            hit = [c for c in cubes if c[1] < y2 - 1e-6 and c[4] > y + 1e-6]
            if hit:
                layers.append((
                    y / 16.0, y2 / 16.0,
                    round(min(c[0] for c in hit) / 16.0 + 0.5, R),
                    round(max(c[3] for c in hit) / 16.0 + 0.5, R),
                    round(min(c[2] for c in hit) / 16.0 + 0.5, R),
                    round(max(c[5] for c in hit) / 16.0 + 0.5, R)))
            y = y2

        # 合并相邻且轮廓相同的层（只合并"紧挨着"的，绝不跳过）
        merged = []
        for L in layers:
            box = L[2:]
            if merged and merged[-1][4] == box and abs(merged[-1][1] - L[0]) < 1e-9:
                merged[-1] = (merged[-1][0], L[1], merged[-1][2], merged[-1][3], box)
            else:
                merged.append((L[0], L[1], L[2], L[3], box))

        print("  %d 层 → 合并成 %d 个盒子：" % (len(layers), len(merged)))
        lines = []
        for (a, b2, x1, x2, box) in merged:
            z1, z2 = box[2], box[3]
            print("    y %.4f..%.4f   X %.3f..%.3f   Z %.3f..%.3f   (%.2f×%.2f 格)"
                  % (a, b2, box[0], box[1], z1, z2, box[1] - box[0], z2 - z1))
            lines.append("            Shapes.box(%.4fD, %.4fD, %.4fD, %.4fD, %.4fD, %.4fD),"
                         % (box[0], a, z1, box[1], b2, z2))
        print()
        for l in lines:
            print(l)
        print()
        result[enum] = lines

    out = os.path.join(ROOT, "tools", "_machine_shapes.txt")
    with io.open(out, "w", encoding="utf-8", newline="") as f:
        for enum, lines in result.items():
            f.write("=== %s\n" % enum)
            f.write("\n".join(lines) + "\n\n")
    print("已写入 %s" % out)


if __name__ == "__main__":
    main()
