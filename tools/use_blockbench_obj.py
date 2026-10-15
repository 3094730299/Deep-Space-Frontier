# -*- coding: utf-8 -*-
"""
把 Blockbench 官方导出的 OBJ 接进模组。

    python tools/use_blockbench_obj.py

和之前"我自己转 OBJ"的区别：这一份是 **Blockbench 官方导出器**的产物，
坐标和 UV 的约定由它决定，我只需要按它的事实做三件确定的事：

  1. 贴图引用：它的 mtl 写的是 `map_Kd texture.png`（本地文件名），
     要改成 `命名空间:block/xxx` 才能在游戏里解析；
  2. `flip_v`：它的 v 是翻转的（第一个 vt 的 v = 1 - uv/64），所以模型 JSON 里必须 `flip_v: true`；
  3. 摆位：它的顶点以**原点为中心**、Y 还朝下，而方块局部空间是 0..1、Y 朝上，
     所以在模型 JSON 的 `display` 里用 gui/固定视角的变换把它摆正（渲染时按 display 的
     firstperson/fixed 变换？——不行）。**这里改用最稳的办法：直接改 obj 里的 v 行**
     （平移 + 翻转 Y），让 obj 自己就是方块空间。
"""
import io
import json
import os

ROOT = r"C:\Users\30947\Documents\deepseek-harness\default-workspace\deep-space-deployer"
A = os.path.join(ROOT, "src", "main", "resources", "assets", "deepspacedeployer")
SRC_DIR = r"C:\Users\30947\Desktop\星舰计划\动态模型"

SRC_OBJ = os.path.join(SRC_DIR, "通用商店终端模组实体.obj")
SRC_TEX = os.path.join(SRC_DIR, "texture.png")

OBJ_OUT = os.path.join(A, "models", "obj", "universal_terminal.obj")
MTL_OUT = os.path.join(A, "models", "obj", "universal_terminal.mtl")
JSON_OUT = os.path.join(A, "models", "block", "universal_terminal.json")
TEX_OUT = os.path.join(A, "textures", "block", "universal_terminal.png")

MATERIAL = "universal_terminal"
TEXTURE_REF = "deepspacedeployer:block/universal_terminal"


def main():
    lines = io.open(SRC_OBJ, encoding="utf-8", errors="replace").read().split("\n")

    verts = []
    for line in lines:
        if line.startswith("v "):
            verts.append([float(x) for x in line.split()[1:4]])

    xs = [p[0] for p in verts]
    ys = [p[1] for p in verts]
    zs = [p[2] for p in verts]
    y_min, y_max = min(ys), max(ys)
    print("原始包围盒: X %.5f..%.5f  Y %.5f..%.5f  Z %.5f..%.5f"
          % (min(xs), max(xs), y_min, y_max, min(zs), max(zs)))

    # 目标：方块局部空间 0..1，水平居中于 (0.5, 0.5)，最低点落在 y=0
    cx = (min(xs) + max(xs)) / 2.0
    cz = (min(zs) + max(zs)) / 2.0
    # ⚠️ Y **不要取反**！核对过源模型：底座 y 0..2、主机 y 1.5..15.5，
    #    也就是说"y 越大越高" —— 和 Minecraft 的方块空间一致，直接平移即可。
    #    （早先我以为它是实体模型的"y 朝下"约定，把 Y 取了反，结果整个模型上下颠倒。）
    def fix(p):
        return (p[0] - cx + 0.5,
                p[1] - y_min,             # 最低点（四爪 -0.287）落到 y=0
                p[2] - cz + 0.5)

    skip_prefix = ("#", "o ", "s ", "mtllib", "usemtl")
    out = []
    for line in lines:
        if line.startswith("v "):
            parts = line.split()
            q = fix([float(x) for x in parts[1:4]])
            out.append("v %.5f %.5f %.5f" % tuple(q))
        elif line.startswith(skip_prefix):
            continue
        else:
            out.append(line)

    header = ["# 由 Blockbench 5.2.2 导出，经 tools/use_blockbench_obj.py 摆位/改贴图引用",
              "mtllib universal_terminal.mtl",
              "o universal_terminal",
              "s off",
              "usemtl %s" % MATERIAL]
    os.makedirs(os.path.dirname(OBJ_OUT), exist_ok=True)
    io.open(OBJ_OUT, "w", encoding="utf-8", newline="").write("\n".join(header + out) + "\n")

    # 贴图引用改成游戏能解析的写法
    io.open(MTL_OUT, "w", encoding="utf-8", newline="").write(
        "newmtl %s\nKa 0 0 0 1\nKd 1 1 1 1\nmap_Kd %s\n" % (MATERIAL, TEXTURE_REF))

    model = {
        "ambientocclusion": False,
        "loader": "neoforge:obj",
        "model": "deepspacedeployer:models/obj/universal_terminal.obj",
        "automatic_culling": False,
        "shade_quads": True,
        # Blockbench 导出的 OBJ 里 v 是翻转的（1 - uv），所以必须开 flip_v
        "flip_v": True,
        "render_type": "minecraft:cutout_mipped",
    }
    io.open(JSON_OUT, "w", encoding="utf-8", newline="").write(
        json.dumps(model, ensure_ascii=False, indent=2) + "\n")

    raw = open(SRC_TEX, "rb").read()
    os.makedirs(os.path.dirname(TEX_OUT), exist_ok=True)
    open(TEX_OUT, "wb").write(raw)

    print("已写入 obj/mtl/模型 JSON/贴图（贴图 %d 字节）" % len(raw))
    print("变换后应对应的方块空间范围：X %.3f..%.3f  Y %.3f..%.3f  Z %.3f..%.3f"
          % (0.5 - (max(xs) - min(xs)) / 2, 0.5 + (max(xs) - min(xs)) / 2,
             0.0, y_max - y_min,
             0.5 - (max(zs) - min(zs)) / 2, 0.5 + (max(zs) - min(zs)) / 2))


if __name__ == "__main__":
    main()
