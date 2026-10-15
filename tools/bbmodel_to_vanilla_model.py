# -*- coding: utf-8 -*-
"""
终极版转换：**把所有旋转全部烘进几何**，输出"零旋转"的原版方块模型。

    python tools/bbmodel_to_vanilla_model.py

为什么这么做（把之前所有坑一次性绕开）：
  1. 原版 element 的旋转只接受 0/±22.5/±45 → 干脆**一个 rotation 字段都不写**；
  2. 旋转角度方向很容易搞反 → 没有旋转字段，就不存在方向问题；
  3. 面被旋转后 UV 要跟着转 → 现在由脚本按"面旋转后的真实顶点"重排 UV；
  4. 多轴旋转原版表达不了 → 不需要表达，烘进顶点就行。

做法：每个立方体的每个面，用它的旋转矩阵把 4 个顶点算到最终位置，
      再按"这个面朝向哪根轴"输出一块**薄板**：
        平面内两根轴 = 4 个真实顶点的范围；
        法线那根轴   = 4 个顶点的平均值 ± 极小厚度。
      薄板的两个面正好落在真实顶点所在的两个平面上，与原面完全重合。

输入用 **modded_entity 那份**（老板 2026-10-13 18:45 另存的，以原点为中心、
带"放置"动画的版本），静态坐标 + 方块原点偏移即可。
"""
import base64
import json
import math
import os

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
ASSETS = os.path.join(PROJECT, "src", "main", "resources", "assets", "deepspacedeployer")

SOURCE = r"C:\Users\30947\Desktop\星舰计划\动态模型\通用商店终端.bbmodel"
MODEL_OUT = os.path.join(ASSETS, "models", "block", "universal_terminal.json")
TEX_OUT = os.path.join(ASSETS, "textures", "block", "universal_terminal.png")

TEXTURE_REF = "deepspacedeployer:block/universal_terminal"
PIXELS_PER_UNIT = 4.0
THICKNESS = 0.0008               # 薄板厚度（1/16 格）
ORIGIN_OFFSET = (8.0, 0.0, 8.0)  # 这份 bbmodel 以原点为中心 → 方块原点在西北下角

FACES = ("north", "south", "east", "west", "up", "down")
# 面 → (法线轴, 平面内 u 轴, 平面内 v 轴)：从面外侧看，u 向右、v 向上
FACE_AXES = {
    "north": (2, 0, 1), "south": (2, 0, 1),
    "east": (0, 2, 1), "west": (0, 2, 1),
    "up": (1, 0, 2), "down": (1, 0, 2),
}


def face_quad(low, high, face):
    x1, y1, z1 = low
    x2, y2, z2 = high
    if face == "north":
        return [(x1, y1, z1), (x2, y1, z1), (x2, y2, z1), (x1, y2, z1)]
    if face == "south":
        return [(x2, y1, z2), (x1, y1, z2), (x1, y2, z2), (x2, y2, z2)]
    if face == "east":
        return [(x2, y1, z1), (x2, y1, z2), (x2, y2, z2), (x2, y2, z1)]
    if face == "west":
        return [(x1, y1, z2), (x1, y1, z1), (x1, y2, z1), (x1, y2, z2)]
    if face == "up":
        return [(x1, y2, z2), (x2, y2, z2), (x2, y2, z1), (x1, y2, z1)]
    return [(x1, y1, z1), (x2, y1, z1), (x2, y1, z2), (x1, y1, z2)]


def rotate_point(p, origin, rot):
    """绕 origin 依次转 x、y、z（角度制、右手系；java_block 与原版同一套约定）"""
    x, y, z = [p[i] - origin[i] for i in range(3)]
    for axis, angle in enumerate(rot):
        if abs(angle) < 1e-9:
            continue
        a = math.radians(angle)
        c, s = math.cos(a), math.sin(a)
        if axis == 0:
            y, z = y * c - z * s, y * s + z * c
        elif axis == 1:
            x, z = x * c + z * s, -x * s + z * c
        else:
            x, y = x * c - y * s, x * s + y * c
    return [x + origin[0], y + origin[1], z + origin[2]]


def uv_of(data, flip_u, flip_v):
    u1, v1, u2, v2 = [float(v) / PIXELS_PER_UNIT for v in data["uv"]]
    if flip_u:
        u1, u2 = u2, u1
    if flip_v:
        v1, v2 = v2, v1
    return [round(u1, 4), round(v1, 4), round(u2, 4), round(v2, 4)]


def main():
    with open(SOURCE, encoding="utf-8") as f:
        bb = json.load(f)

    elements = []
    rotated_cubes = 0

    for element in bb["elements"]:
        frm = [float(v) for v in element["from"]]
        to = [float(v) for v in element["to"]]
        low = [min(frm[i], to[i]) + ORIGIN_OFFSET[i] for i in range(3)]
        high = [max(frm[i], to[i]) + ORIGIN_OFFSET[i] for i in range(3)]
        rot = [float(v) for v in (element.get("rotation") or [0, 0, 0])]
        raw_origin = element.get("origin") or element["from"]
        origin = [float(raw_origin[i]) + ORIGIN_OFFSET[i] for i in range(3)]
        has_rot = any(abs(v) > 1e-9 for v in rot)
        if has_rot:
            rotated_cubes += 1

        for face in FACES:
            data = (element.get("faces") or {}).get(face)
            if not data or not data.get("uv"):
                continue
            before = face_quad(low, high, face)
            pts = [rotate_point(p, origin, rot) for p in before] if has_rot else before

            normal, ax_u, ax_v = FACE_AXES[face]
            flip_u = flip_v = False
            if has_rot:
                flip_u = (pts[1][ax_u] - pts[0][ax_u]) * (before[1][ax_u] - before[0][ax_u]) < 0
                flip_v = (pts[3][ax_v] - pts[0][ax_v]) * (before[3][ax_v] - before[0][ax_v]) < 0

            lo = [0.0, 0.0, 0.0]
            hi = [0.0, 0.0, 0.0]
            for a in (ax_u, ax_v):
                vals = [p[a] for p in pts]
                lo[a], hi[a] = min(vals), max(vals)
            mid = sum(p[normal] for p in pts) / 4.0
            lo[normal] = mid - THICKNESS
            hi[normal] = mid + THICKNESS
            for i in range(3):
                if hi[i] - lo[i] < 1e-4:
                    hi[i] = lo[i] + 1e-4

            elements.append({
                "from": [round(v, 5) for v in lo],
                "to": [round(v, 5) for v in hi],
                "faces": {face: {"uv": uv_of(data, flip_u, flip_v), "texture": "#all"}},
            })

    # 整体落到底面（按烘好的真实顶点算，绝不用 element 的 from/to 猜）
    lowest = min(e["from"][1] for e in elements)
    if abs(lowest) > 1e-6:
        for e in elements:
            e["from"][1] = round(e["from"][1] - lowest, 5)
            e["to"][1] = round(e["to"][1] - lowest, 5)
        print("整体上移 %.3f 单位（让最低点落在 y=0）" % (-lowest))
    else:
        print("最低点已在 y=0")

    model = {
        "parent": "minecraft:block/block",
        "ambientocclusion": False,
        "textures": {"particle": TEXTURE_REF, "all": TEXTURE_REF},
        "elements": elements,
    }
    os.makedirs(os.path.dirname(MODEL_OUT), exist_ok=True)
    with open(MODEL_OUT, "w", encoding="utf-8", newline="") as f:
        json.dump(model, f, ensure_ascii=False, indent=2)

    written = 0
    for texture in bb.get("textures", []):
        source = texture.get("source") or ""
        if source.startswith("data:image/png;base64,"):
            raw = base64.b64decode(source.split(",", 1)[1])
            with open(TEX_OUT, "wb") as f:
                f.write(raw)
            written = len(raw)
            break

    lo = [min(e["from"][i] for e in elements) for i in range(3)]
    hi = [max(e["to"][i] for e in elements) for i in range(3)]
    print("源立方体 %d 个（其中带旋转 %d 个）→ 输出面-element %d 个"
          % (len(bb["elements"]), rotated_cubes, len(elements)))
    print("总包围盒: X %.3f..%.3f  Y %.3f..%.3f  Z %.3f..%.3f"
          % (lo[0], hi[0], lo[1], hi[1], lo[2], hi[2]))
    print("带 rotation 字段的 element: %d 个（应为 0）"
          % len([e for e in elements if "rotation" in e]))
    print("模型: %s" % os.path.relpath(MODEL_OUT, PROJECT))
    print("贴图: %s（%d 字节）" % (os.path.relpath(TEX_OUT, PROJECT), written))


if __name__ == "__main__":
    main()
