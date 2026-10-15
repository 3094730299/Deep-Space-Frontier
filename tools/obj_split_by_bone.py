# -*- coding: utf-8 -*-
"""
用**源模型（.bbmodel）的骨骼 y 范围**来定归属，不再靠导出坐标推断。

    python tools/obj_split_by_bone.py

数据（来自老板的 .bbmodel，是原始事实）：
    底座   y  0.000 ..  2.000
    爪1~4  y -0.287 ..  0.713
    主机   y  1.500 .. 15.500   （含顶盖装饰）

做法：
  1. 按**元素顺序**把导出的 40 个 `o cube` 与源模型的 40 个元素一一对应
     （两种导出的元素顺序都来自同一个 Blockbench 列表）；
  2. 每个元素按**源里的 y 中心**归类：接近 1 → 底座，接近 0.2 → 爪子，其余 → 主机；
  3. 顶点坐标**原样使用**（不再自己猜 Y 该不该翻 —— 屏幕上已经在正确位置的
     就是这套坐标，早先"显示正常"的那一版用的就是它）。
"""
import io
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
ASSETS = os.path.join(PROJECT, "src", "main", "resources", "assets", "deepspacedeployer")

BB = r"C:\Users\30947\Desktop\星舰计划\动态模型\通用商店终端模组实体.bbmodel"
OBJ_IN = r"C:\Users\30947\Desktop\星舰计划\动态模型\通用商店终端模组实体.obj"
OBJ_OUT = os.path.join(ASSETS, "models", "obj", "universal_terminal.obj")

BONE_ORDER = ("base", "main", "claw1", "claw2", "claw3", "claw4")


def classify(source_element):
    """按源元素的 y 中心分类"""
    f = [float(v) for v in source_element["from"]]
    t = [float(v) for v in source_element["to"]]
    yc = (min(f[1], t[1]) + max(f[1], t[1])) / 2.0
    xs = (min(f[0], t[0]) + max(f[0], t[0])) / 2.0
    zs = (min(f[2], t[2]) + max(f[2], t[2])) / 2.0
    # 爪子：y 中心贴近 0.2（源范围 -0.287..0.713），且离中心较远
    if -0.3 <= yc <= 0.75:
        # 四个爪子按方位分：北(z-)/东(x+)/南(z+)/西(x-)
        if abs(zs) > abs(xs):
            return "claw1" if zs < 0 else "claw3"
        return "claw2" if xs > 0 else "claw4"
    if yc < 1.0:                      # 底座那批（0..2，中心 1）
        return "base"
    if yc < 1.4:
        return "base"
    return "main"


def main():
    bb = json.load(io.open(BB, encoding="utf-8"))
    source_elements = bb["elements"]

    lines = io.open(OBJ_IN, encoding="utf-8", errors="replace").read().split("\n")
    verts, uvs, normals = [], [], []
    blocks = []
    current = None
    for line in lines:
        if line.startswith("o "):
            current = {"faces": []}
            blocks.append(current)
        elif line.startswith("v "):
            verts.append([float(x) for x in line.split()[1:4]])
        elif line.startswith("vt "):
            uvs.append(line)
        elif line.startswith("vn "):
            normals.append(line)
        elif line.startswith("f ") and current is not None:
            idx = []
            for token in line.split()[1:]:
                b = token.split("/")
                idx.append((int(b[0]), int(b[1]) if len(b) > 1 else 0, int(b[2]) if len(b) > 2 else 0))
            current["faces"].append(idx)

    # 顶点坐标原样使用（这套坐标在屏幕上位置已经正确）
    fixed = [[p[0] * 16.0 + 8.0, p[1] * 16.0, p[2] * 16.0 + 8.0] for p in verts]

    groups = {}
    counts = {}
    for index, block in enumerate(blocks):
        if not block["faces"] or index >= len(source_elements):
            continue
        bone = classify(source_elements[index])
        groups.setdefault(bone, []).extend(block["faces"])
        counts[bone] = counts.get(bone, 0) + 1

    out = ["# 由 tools/obj_split_by_bone.py 生成（按源模型骨骼 y 范围归类）",
           "mtllib universal_terminal.mtl"]
    for p in fixed:
        out.append("v %.5f %.5f %.5f" % tuple(p))
    out.extend(uvs)
    out.extend(normals)
    for bone in BONE_ORDER:
        group = groups.get(bone)
        if not group:
            continue
        out.append("g %s" % bone)
        out.append("usemtl universal_terminal")
        for idx in group:
            out.append("f " + " ".join("%d/%d/%d" % t for t in idx))

    io.open(OBJ_OUT, "w", encoding="utf-8", newline="").write("\n".join(out) + "\n")

    print("元素归类统计:", counts)
    print("坐标范围 X %.2f..%.2f  Y %.2f..%.2f  Z %.2f..%.2f 像素"
          % (min(q[0] for q in fixed), max(q[0] for q in fixed),
             min(q[1] for q in fixed), max(q[1] for q in fixed),
             min(q[2] for q in fixed), max(q[2] for q in fixed)))
    for bone in BONE_ORDER:
        group = groups.get(bone)
        if not group:
            continue
        bx = [fixed[vi - 1][0] for idx in group for vi, _, _ in idx]
        by = [fixed[vi - 1][1] for idx in group for vi, _, _ in idx]
        bz = [fixed[vi - 1][2] for idx in group for vi, _, _ in idx]
        print("   组 %-6s 面 %3d  X %6.2f..%6.2f  Y %5.2f..%5.2f  Z %6.2f..%6.2f"
              % (bone, len(group), min(bx), max(bx), min(by), max(by), min(bz), max(bz)))


if __name__ == "__main__":
    main()
