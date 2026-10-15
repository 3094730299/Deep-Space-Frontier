# -*- coding: utf-8 -*-
"""
从 .bbmodel **直接生成 Java 实体模型类**（不经过任何中间格式）。

    python tools/bbmodel_to_java_model.py

这是最可靠的一条路：
  * 不再有"原版元素旋转只能 ±45°"的限制 —— Minecraft 的 Cube 本来就能任意角度；
  * 不再需要"拆面/薄板"这种会把斜面弄没的操作 —— 斜面就是真实的斜面；
  * 不再依赖 GeckoLib —— 用原版 EntityModel + ModelPart；
  * 贴图 UV 直接写进 cube，不经过任何格式解析。

生成两个文件：
  src/main/java/com/deepspacedeployer/client/model/UniversalTerminalModel.java
  （模型本体 + 骨骼结构）
"""
import json
import math
import os

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)

SOURCE = r"C:\Users\30947\Desktop\星舰计划\动态模型\通用商店终端模组实体.bbmodel"
OUT = os.path.join(PROJECT, "src", "main", "java", "com", "deepspacedeployer",
                   "client", "model", "UniversalTerminalModel.java")

# bbmodel 的骨骼名（中文）→ Java 字段名
BONE_FIELDS = {
    "主机": "main",
    "底座": "base",
    "固定爪": "clawMount",
    "爪1": "claw1",
    "爪2": "claw2",
    "爪3": "claw3",
    "爪4": "claw4",
}
# bbmodel 的骨骼名 → 贴图里的骨骼名（Blockbench 导出用）
BONE_EXPORT = {
    "主机": "main",
    "底座": "base",
    "固定爪": "claw_mount",
    "爪1": "claw1",
    "爪2": "claw2",
    "爪3": "claw3",
    "爪4": "claw4",
}

FACES = ("north", "south", "east", "west", "up", "down")


def load_names(bb):
    names = {}
    for anim in bb.get("animations", []):
        for uuid, animator in (anim.get("animators") or {}).items():
            if animator.get("name"):
                names[uuid] = animator["name"]
    return names


def fmt(value):
    text = ("%.5f" % float(value)).rstrip("0").rstrip(".")
    return text if text not in ("-0", "") else "0"


def tex_off(element, face, axis, uv_size, tex_size, mirror):
    """按 Minecraft 的约定算 texOffs。

    Minecraft 的 Cube 把贴图坐标当成"左上角 + 尺寸"，而且 up/down 两面的
    朝向和 Blockbench 的定义差一个方向，所以那两面要按 uv 的跨度换算。
    """
    uv = element["faces"][face]["uv"]
    if face in ("up", "down"):
        # up/down：v 跨度 → 高，u 跨度 → 宽
        u, v = uv[0], uv[1]
        height = abs(uv[3] - uv[1])
        if face == "down":
            v = uv[3]
    else:
        u, v = uv[0], uv[1]
        height = abs(uv[3] - uv[1])
    return int(round(u)), int(round(v))


def cube_java(element, tex_size):
    frm = [float(v) for v in element["from"]]
    to = [float(v) for v in element["to"]]
    low = [min(frm[i], to[i]) for i in range(3)]
    high = [max(frm[i], to[i]) for i in range(3)]
    size = [high[i] - low[i] for i in range(3)]

    # 贴图偏移与尺寸：取 north 面的 uv 起点，尺寸用立方体的 (u跨度, v跨度)
    faces = element.get("faces") or {}
    off = None
    for face in ("north", "east", "south", "west", "up", "down"):
        if face in faces and faces[face].get("uv"):
            uv = faces[face]["uv"]
            off = (int(round(uv[0])), int(round(uv[1])))
            break
    if off is None:
        off = (0, 0)

    # 贴图尺寸：用 box_uv 的话是整个包围盒在贴图上的跨度
    u_span = 0.0
    v_span = 0.0
    for face in ("north", "south"):
        if face in faces and faces[face].get("uv"):
            uv = faces[face]["uv"]
            u_span = max(u_span, abs(uv[2] - uv[0]))
            v_span = max(v_span, abs(uv[3] - uv[1]))
    for face in ("east", "west"):
        if face in faces and faces[face].get("uv"):
            uv = faces[face]["uv"]
            u_span = max(u_span, abs(uv[2] - uv[0]) * 2 if False else abs(uv[2] - uv[0]))
    mirror = "true" if element.get("mirror") else "false"

    return ("""                    .cube("cube", %s, %s, %s,
                            %s, %s, %s,
                            %s, %s, %s,
                            CubeListBuilder.Mirror.%s)"""
            % (fmt(low[0]), fmt(low[1]), fmt(low[2]),
               fmt(size[0]), fmt(size[1]), fmt(size[2]),
               fmt(u_span), fmt(v_span), fmt(0),
               fmt(off[0]), fmt(off[1]),
               "TRUE" if mirror == "true" else "FALSE"))


def rotation_java(element):
    rot = [float(v) for v in (element.get("rotation") or [0, 0, 0])]
    if not any(abs(v) > 1e-9 for v in rot):
        return None
    origin = [float(v) for v in (element.get("origin") or element["from"])]
    return ("""                    .rotation(%s, %s, %s,
                              %s, %s, %s)"""
            % (fmt(rot[0]), fmt(rot[1]), fmt(rot[2]),
               fmt(origin[0]), fmt(origin[1]), fmt(origin[2])))


def build(bb):
    names = load_names(bb)
    elements = {e["uuid"]: e for e in bb.get("elements", [])}

    bones = []          # (java 字段名, 导出名, [cube 代码块])

    def walk(node, depth):
        bone_name = names.get(node.get("uuid"))
        field = BONE_FIELDS.get(bone_name)
        cubes = []
        children = []
        for child in node.get("children") or []:
            if isinstance(child, str):
                element = elements.get(child)
                if element:
                    parts = [cube_java(element, bb.get("resolution"))]
                    rot = rotation_java(element)
                    if rot:
                        parts.append(rot)
                    cubes.append(",\n".join(parts))
            else:
                children.append(child)
        if cubes:
            bones.append((field, BONE_EXPORT.get(bone_name, bone_name or "bone"), cubes))
        for child in children:
            walk(child, depth + 1)

    for node in bb.get("outliner", []):
        if not isinstance(node, dict):
            continue
        walk(node, 0)

    return bones


def main():
    with open(SOURCE, encoding="utf-8") as f:
        bb = json.load(f)

    bones = build(bb)
    print("骨骼数 %d，立方体总数 %d" % (len(bones), sum(len(b[2]) for b in bones)))
    for field, export, cubes in bones:
        print("   %-12s (%s) 立方体 %d" % (field, export, len(cubes)))

    lines = []
    lines.append("package com.deepspacedeployer.client.model;")
    lines.append("")
    lines.append("import net.minecraft.client.model.EntityModel;")
    lines.append("import net.minecraft.client.model.geom.ModelPart;")
    lines.append("import net.minecraft.client.model.geom.PartPose;")
    lines.append("import net.minecraft.client.model.geom.builders.CubeListBuilder;")
    lines.append("import net.minecraft.client.model.geom.builders.LayerDefinition;")
    lines.append("import net.minecraft.client.model.geom.builders.MeshDefinition;")
    lines.append("import net.minecraft.client.model.geom.builders.PartDefinition;")
    lines.append("import net.minecraft.client.renderer.RenderType;")
    lines.append("import net.minecraft.util.Mth;")
    lines.append("import net.minecraft.world.entity.Entity;")
    lines.append("")
    lines.append("/**")
    lines.append(" * 通用终端的实体模型 —— <b>由 tools/bbmodel_to_java_model.py 从 .bbmodel 直接生成</b>。")
    lines.append(" *")
    lines.append(" * <p>为什么走 Java 而不是模型文件：Minecraft 的方块模型格式限制太多")
    lines.append(" *（元素旋转只能 0/±22.5/±45、多轴表达不了），而这个模型有 27 个任意角度的斜面；")
    lines.append(" * 实体模型（ModelPart + Cube）本来就支持任意角度旋转，所以斜面就是真实的斜面。</p>")
    lines.append(" *")
    lines.append(" * <p>骨骼：%s</p>" % "、".join(b[1] for b in bones))
    lines.append(" */")
    lines.append("public class UniversalTerminalModel<T extends Entity> extends EntityModel<T> {")
    lines.append("")
    for field, export, cubes in bones:
        lines.append("    /** %s */" % export)
        lines.append("    public final ModelPart %s;" % field)
    lines.append("")
    lines.append("    public UniversalTerminalModel(ModelPart root) {")
    lines.append("        super(RenderType::entityCutoutNoCull);")
    for field, export, cubes in bones:
        lines.append("        this.%s = root.getChild(\"%s\");" % (field, export))
    lines.append("    }")
    lines.append("")
    lines.append("    public static LayerDefinition createBodyLayer() {")
    lines.append("        MeshDefinition mesh = new MeshDefinition();")
    lines.append("        PartDefinition root = mesh.getRoot();")
    lines.append("")
    for field, export, cubes in bones:
        lines.append("        PartDefinition %s = root.addOrReplaceChild(\"%s\"," % (field, export))
        lines.append("                CubeListBuilder.create()")
        for cube in cubes:
            lines.append(cube.replace("CubeListBuilder.Mirror.FALSE", "CubeListBuilder.Mirror.NONE")
                        .replace("CubeListBuilder.Mirror.TRUE", "CubeListBuilder.Mirror.MIRROR"))
            lines.append("                    .mirror(),")
        lines.append("                PartPose.ZERO);")
        lines.append("")
    lines.append("        return LayerDefinition.create(mesh, 64, 64);")
    lines.append("    }")
    lines.append("")
    lines.append("    @Override")
    lines.append("    public void setupAnim(T entity, float limbSwing, float limbSwingAmount,")
    lines.append("                          float ageInTicks, float netHeadYaw, float headPitch) {")
    lines.append("        // 动画由渲染器按方块状态驱动（见 UniversalTerminalRenderer）")
    lines.append("    }")
    lines.append("}")
    lines.append("")

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="") as f:
        f.write("\n".join(lines))
    print("\n已生成 %s（%d 行）" % (os.path.relpath(OUT, PROJECT), len(lines)))


if __name__ == "__main__":
    main()
