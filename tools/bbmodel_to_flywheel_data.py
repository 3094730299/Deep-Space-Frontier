# -*- coding: utf-8 -*-
"""
从 .bbmodel 生成 Flywheel 视觉所需的数据（Java 源文件）。

    python tools/bbmodel_to_flywheel_data.py

输出：src/main/java/com/deepspacedeployer/client/flywheel/UniversalTerminalGeometry.java

坐标系用的是**源模型（你建的）的坐标**，不做任何翻转推断：
    · x/z 以方块中心为 0（与 .bbmodel 一致）
    · y 向上为正、底座在 y≈0
    · 单位是"像素"（1/16 格）——和 .bbmodel 一致，直观好核对

每个立方体记录：位置、尺寸、绕自身 pivot 的旋转、(面 → UV 像素矩形)、所属骨骼。
渲染器用 Flywheel 的 TransformedInstance 逐立方体施加变换 ——
UV 是**逐面直写**的，所以不存在"UV 布局对不对"的问题。
"""
import io
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)

BB = r"C:\Users\30947\Desktop\星舰计划\动态模型\通用商店终端模组实体.bbmodel"
OUT = os.path.join(PROJECT, "src", "main", "java", "com", "deepspacedeployer",
                   "client", "flywheel", "UniversalTerminalGeometry.java")

BONE_NAMES = {"主机": "MAIN", "底座": "BASE", "爪1": "CLAW1", "爪2": "CLAW2",
              "爪3": "CLAW3", "爪4": "CLAW4", "固定爪": "MAIN"}


def main():
    bb = json.load(io.open(BB, encoding="utf-8"))
    names = {}
    for anim in bb.get("animations", []):
        for uuid, animator in (anim.get("animators") or {}).items():
            if animator.get("name"):
                names[uuid] = animator["name"]

    element_bone = {}

    def walk(node, inherited):
        if not isinstance(node, dict):
            return
        bone = BONE_NAMES.get(names.get(node.get("uuid")), inherited)
        for child in node.get("children") or []:
            if isinstance(child, str):
                element_bone[child] = bone
            else:
                walk(child, bone)

    for node in bb["outliner"]:
        walk(node, "MAIN")

    lines = []
    counts = {}
    for index, e in enumerate(bb["elements"]):
        frm = [float(v) for v in e["from"]]
        to = [float(v) for v in e["to"]]
        low = [min(frm[i], to[i]) for i in range(3)]
        size = [abs(to[i] - frm[i]) for i in range(3)]
        rot = [float(v) for v in (e.get("rotation") or [0, 0, 0])]
        origin = [float(v) for v in (e.get("origin") or e["from"])]
        bone = element_bone.get(e["uuid"], "MAIN")
        counts[bone] = counts.get(bone, 0) + 1

        faces = e.get("faces") or {}
        uv = {}
        for face in ("north", "south", "east", "west", "up", "down"):
            data = faces.get(face)
            if data and data.get("uv"):
                uv[face.upper()] = [round(float(v), 3) for v in data["uv"]]

        lines.append("    // #%d (%s)" % (index, names.get(
            next((u for u, n in names.items() if BONE_NAMES.get(n) == bone), ""), bone)))
        lines.append("    new Cube(Bone.%s," % bone)
        lines.append("            %.4ff, %.4ff, %.4ff, %.4ff, %.4ff, %.4ff," % tuple(low + size))
        lines.append("            %.4ff, %.4ff, %.4ff, %.4ff, %.4ff, %.4ff," % tuple(rot + origin))
        face_entries = ", ".join(
            "new FaceUv(Face.%s, new float[]{%s})" % (k, ", ".join("%.3ff" % v for v in uv[k]))
            for k in ("NORTH", "SOUTH", "EAST", "WEST", "UP", "DOWN") if k in uv)
        lines.append("            new FaceUv[]{%s})," % face_entries)

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    io.open(OUT, "w", encoding="utf-8", newline="").write('''package com.deepspacedeployer.client.flywheel;

/**
 * 通用终端的几何数据 —— <b>由 tools/bbmodel_to_flywheel_data.py 从 .bbmodel 生成</b>。
 *
 * <p>坐标系就是源 .bbmodel 的坐标系（单位 = 1/16 格的"像素"）：
 * x/z 以方块中心为 0，y 向上为正、底座在 y≈0。所以这里的数据和老板建模时看到的一致，
 * 不需要任何"轴要不要翻"的推断。</p>
 */
public final class UniversalTerminalGeometry {

    /** 骨骼 */
    public enum Bone { BASE, MAIN, CLAW1, CLAW2, CLAW3, CLAW4 }

    /** 面 */
    public enum Face { NORTH, SOUTH, EAST, WEST, UP, DOWN }

    /** 一个面的 UV（像素，与 .bbmodel 一致：左上原点） */
    public record FaceUv(Face face, float[] uv) {
    }

    /** 一个立方体：位置 + 尺寸 + 绕 pivot 的旋转 + 各面 UV + 所属骨骼 */
    public record Cube(Bone bone,
                       float x, float y, float z, float sx, float sy, float sz,
                       float rotX, float rotY, float rotZ,
                       float pivotX, float pivotY, float pivotZ,
                       FaceUv[] uvs) {
    }

    public static final Cube[] CUBES = {
%s
    };

    private UniversalTerminalGeometry() {
    }
}
''' % "\n".join(lines))

    print("已生成 %s" % os.path.relpath(OUT, PROJECT))
    print("立方体 %d 个，骨骼分布: %s" % (len(bb["elements"]), counts))


if __name__ == "__main__":
    main()
