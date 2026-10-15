# -*- coding: utf-8 -*-
"""
从 .bbmodel 生成 OBJ 模型 —— 格式严格照整合包里**能正常工作的**模组
（mianbaos_modernwarfare / aeroworks / create_submarine / mekmm）来写。

    python tools/bbmodel_to_obj.py

对齐的要点（逐条拆开对照出来的，之前就是这些没对上才黑紫）：
  1. `.obj` 要带 `mtllib <同目录文件名>`，并且 `usemtl <材质名>`；
  2. **贴图路径写在 `.mtl` 的 `map_Kd` 里**（`命名空间:block/xxx`），模型 JSON 里不写 textures；
  3. 每个面写 `vn` 法线，`f` 用 `v/vt/vn` 三段式；
  4. 模型 JSON 里 `model` 路径**必须带 `models/` 前缀**。

输出：
    assets/deepspacedeployer/models/obj/universal_terminal.obj
    assets/deepspacedeployer/models/obj/universal_terminal.mtl
    assets/deepspacedeployer/models/block/universal_terminal.json
    assets/deepspacedeployer/textures/block/universal_terminal.png
"""
import base64
import json
import math
import os

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
ASSETS = os.path.join(PROJECT, "src", "main", "resources", "assets", "deepspacedeployer")

SOURCE = r"C:\Users\30947\Desktop\星舰计划\动态模型\通用商店终端模组实体.bbmodel"
OBJ_DIR = os.path.join(ASSETS, "models", "obj")
OBJ_OUT = os.path.join(OBJ_DIR, "universal_terminal.obj")
MTL_OUT = os.path.join(OBJ_DIR, "universal_terminal.mtl")
JSON_OUT = os.path.join(ASSETS, "models", "block", "universal_terminal.json")
TEX_OUT = os.path.join(ASSETS, "textures", "block", "universal_terminal.png")

TEXTURE_SIZE = 64.0
ORIGIN_OFFSET = (8.0, 0.0, 8.0)
MATERIAL = "universal_terminal"
TEXTURE_REF = "deepspacedeployer:block/universal_terminal"

FACES = ("north", "south", "east", "west", "up", "down")

FACE_NORMAL = {
    "north": (0, 0, -1), "south": (0, 0, 1),
    "east": (1, 0, 0), "west": (-1, 0, 0),
    "up": (0, 1, 0), "down": (0, -1, 0),
}


def face_corners(low, high, face):
    """从**面外侧看**逆时针排列（法线朝外）"""
    x1, y1, z1 = low
    x2, y2, z2 = high
    if face == "north":
        return [(x2, y1, z1), (x1, y1, z1), (x1, y2, z1), (x2, y2, z1)]
    if face == "south":
        return [(x1, y1, z2), (x2, y1, z2), (x2, y2, z2), (x1, y2, z2)]
    if face == "east":
        return [(x2, y1, z2), (x2, y1, z1), (x2, y2, z1), (x2, y2, z2)]
    if face == "west":
        return [(x1, y1, z1), (x1, y1, z2), (x1, y2, z2), (x1, y2, z1)]
    if face == "up":
        return [(x1, y2, z1), (x2, y2, z1), (x2, y2, z2), (x1, y2, z2)]
    return [(x1, y1, z2), (x2, y1, z2), (x2, y1, z1), (x1, y1, z1)]


def rotate_point(p, origin, rot):
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


def main():
    with open(SOURCE, encoding="utf-8") as f:
        bb = json.load(f)

    body = []
    v_count = 0
    t_count = 0
    n_count = 0
    faces_written = 0

    for element in bb["elements"]:
        frm = [float(v) for v in element["from"]]
        to = [float(v) for v in element["to"]]
        low = [min(frm[i], to[i]) + ORIGIN_OFFSET[i] for i in range(3)]
        high = [max(frm[i], to[i]) + ORIGIN_OFFSET[i] for i in range(3)]
        rot = [float(v) for v in (element.get("rotation") or [0, 0, 0])]
        raw_origin = element.get("origin") or element["from"]
        origin = [float(raw_origin[i]) + ORIGIN_OFFSET[i] for i in range(3)]
        has_rot = any(abs(v) > 1e-9 for v in rot)

        for face in FACES:
            data = (element.get("faces") or {}).get(face)
            if not data or not data.get("uv"):
                continue
            corners = face_corners(low, high, face)
            normal = list(FACE_NORMAL[face])
            if has_rot:
                corners = [rotate_point(p, origin, rot) for p in corners]
                normal = rotate_point(normal, [0, 0, 0], rot)

            u1, v1, u2, v2 = [float(v) for v in data["uv"]]
            uv_px = [(u1, v1), (u2, v1), (u2, v2), (u1, v2)]

            for p in corners:
                body.append("v %s %s %s" % tuple("%.5f" % (x / 16.0) for x in p))
            for (u, v) in uv_px:
                body.append("vt %.7f %.7f" % (u / TEXTURE_SIZE, v / TEXTURE_SIZE))
            body.append("vn %.4f %.4f %.4f" % tuple(normal))

            v_count += 4
            t_count += 4
            n_count += 1
            v0, t0, n0 = v_count - 3, t_count - 3, n_count
            body.append("f %d/%d/%d %d/%d/%d %d/%d/%d %d/%d/%d"
                        % (v0, t0, n0, v0 + 1, t0 + 1, n0, v0 + 2, t0 + 2, n0, v0 + 3, t0 + 3, n0))
            faces_written += 1

    os.makedirs(OBJ_DIR, exist_ok=True)
    with open(OBJ_OUT, "w", encoding="utf-8", newline="") as f:
        f.write("# 由 tools/bbmodel_to_obj.py 从 .bbmodel 生成\n")
        f.write("mtllib universal_terminal.mtl\n")
        f.write("o universal_terminal\n")
        f.write("s off\n")
        f.write("usemtl %s\n" % MATERIAL)
        f.write("\n".join(body))
        f.write("\n")

    # 贴图路径写在 mtl 的 map_Kd 里（能工作的模组就是这么做的）
    with open(MTL_OUT, "w", encoding="utf-8", newline="") as f:
        f.write("newmtl %s\n" % MATERIAL)
        f.write("Ka 0 0 0 1\n")
        f.write("Kd 1 1 1 1\n")
        f.write("map_Kd %s\n" % TEXTURE_REF)

    model = {
        "ambientocclusion": False,
        "loader": "neoforge:obj",
        # ⚠️ 必须带 models/ 前缀（OBJ 加载器按完整资源路径取文件）
        "model": "deepspacedeployer:models/obj/universal_terminal.obj",
        "automatic_culling": False,
        "shade_quads": True,
        "flip_v": False,
        "render_type": "minecraft:cutout_mipped",
    }
    with open(JSON_OUT, "w", encoding="utf-8", newline="") as f:
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

    print("立方体 %d → 顶点 %d / UV %d / 法线 %d / 面 %d"
          % (len(bb["elements"]), v_count, t_count, n_count, faces_written))
    print("obj   : %s" % os.path.relpath(OBJ_OUT, PROJECT))
    print("mtl   : %s" % os.path.relpath(MTL_OUT, PROJECT))
    print("model : %s" % os.path.relpath(JSON_OUT, PROJECT))
    print("贴图  : %d 字节" % written)


if __name__ == "__main__":
    main()
