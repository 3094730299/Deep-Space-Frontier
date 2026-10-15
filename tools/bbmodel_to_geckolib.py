# -*- coding: utf-8 -*-
"""
把 GeckoLib 的 .bbmodel 转成 GeckoLib 的 geo.json + animation.json + 贴图。

    python tools/bbmodel_to_geckolib.py

输入：C:\\Users\\30947\\Desktop\\星舰计划\\动态模型\\通用商店终端.bbmodel
输出：
    assets/deepspacedeployer/geo/block/universal_terminal.geo.json
    assets/deepspacedeployer/animations/block/universal_terminal[_east|_south|_west].animation.json
    assets/deepspacedeployer/textures/block/universal_terminal.png

支持的 .bbmodel 结构（老板 2026-10-13 18:29 重存的新版）：
    animations[].animators[<uuid>].keyframes = [
        {"channel": "position"|"rotation"|"scale",
         "time": <秒>, "data_points": [{"x":..,"y":..,"z":..}]}, ... ]
    —— 也就是"扁平的关键帧列表"。旧版那种 "通道 → {时间: 值}" 字典也一并兼容。

坐标约定：这份模型**以原点为中心**导出（给实体渲染用的），
         而方块的几何原点是"西北下角"，所以 x、z 各 +8。

朝向：老板要求"放下后朝向玩家"。GeckoLib 的动画是烘焙好的数据，运行时改不了，
     所以这里**为 4 个朝向各生成一份动画**（文件名加后缀），
     由方块状态 FACING 决定播哪一段。
"""
import base64
import json
import math
import os

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
ASSETS = os.path.join(PROJECT, "src", "main", "resources", "assets", "deepspacedeployer")

SOURCE = r"C:\Users\30947\Desktop\星舰计划\动态模型\通用商店终端.bbmodel"
GEO_ID = "universal_terminal"
TEXTURE_NAME = "universal_terminal"
TEXTURE_SIZE = (64, 64)
ORIGIN_OFFSET = (8.0, 0.0, 8.0)      # bbmodel 以原点为中心 → 方块原点在西北下角

# 骨骼名映射：bbmodel 的名字（中文）→ geo.json 里的名字（ASCII，避免编码问题）
BONE_NAMES = {
    "主机": "main",
    "底座": "base",
    "固定爪": "claw_mount",
    "爪1": "claw1",
    "爪2": "claw2",
    "爪3": "claw3",
    "爪4": "claw4",
}

# 朝向 → 绕方块中心的旋转角
FACINGS = {"north": 0.0, "east": 90.0, "south": 180.0, "west": 270.0}
FACE_SUFFIX = {"north": "", "east": "_east", "south": "_south", "west": "_west"}


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def uuid_names(bb):
    """outliner 节点没有名字，名字藏在动画的 animators 里"""
    names = {}
    for anim in bb.get("animations", []):
        for uuid, animator in (anim.get("animators") or {}).items():
            if animator.get("name"):
                names[uuid] = animator["name"]
    return names


def fix_point(point):
    return [round(float(point[0]) + ORIGIN_OFFSET[0], 5),
            round(float(point[1]) + ORIGIN_OFFSET[1], 5),
            round(float(point[2]) + ORIGIN_OFFSET[2], 5)]


def cube_of(element):
    frm = [float(v) for v in element["from"]]
    to = [float(v) for v in element["to"]]
    low = [min(frm[i], to[i]) for i in range(3)]
    size = [round(abs(to[i] - frm[i]), 5) for i in range(3)]
    # GeckoLib 会**无条件**读 rotation / pivot（缺了反序列化成空数组 → 直接越界崩），
    # 所以这两个字段永远写全。
    cube = {
        "origin": fix_point(low),
        "size": size,
        "uv": {},
        "pivot": fix_point(element.get("origin") or element["from"]),
        "rotation": [round(float(v), 5) for v in (element.get("rotation") or [0, 0, 0])],
    }
    for face, data in (element.get("faces") or {}).items():
        uv = data.get("uv")
        if not uv:
            continue
        # bbmodel 的 UV 与 GeckoLib 同为"像素、原点在贴图左上"，直接搬
        cube["uv"][face] = {"uv": [round(float(v), 5) for v in uv]}
    return cube


def convert_node(node, elements, names):
    raw_name = names.get(node.get("uuid"))
    bone = {
        "name": BONE_NAMES.get(raw_name, raw_name or "bone"),
        "cubes": [],
        "pivot": fix_point(node.get("origin") or [0, 0, 0]),
        "rotation": [round(float(v), 5) for v in (node.get("rotation") or [0, 0, 0])],
    }
    for child in node.get("children") or []:
        if isinstance(child, str):
            element = elements.get(child)
            if element:
                bone["cubes"].append(cube_of(element))
        else:
            bone.setdefault("children", []).append(convert_node(child, elements, names))
    return bone


def build_geometry(bb):
    elements = {e["uuid"]: e for e in bb.get("elements", [])}
    names = uuid_names(bb)
    bones = []
    for node in bb.get("outliner", []):
        if not isinstance(node, str):
            bones.append(convert_node(node, elements, names))
    return {
        "format_version": "1.12.0",
        "minecraft:geometry": [{
            "description": {
                "identifier": "geometry." + GEO_ID,
                "texture_width": TEXTURE_SIZE[0],
                "texture_height": TEXTURE_SIZE[1],
                "visible_bounds_width": 3,
                "visible_bounds_height": 3,
                "visible_bounds_offset": [0, 1, 0],
            },
            "bones": bones,
        }],
    }


# ---------------- 动画 ----------------

def collect_keyframes(animator):
    """
    把 bbmodel 的关键帧整理成 {通道: [(时间, x, y, z), ...]}（按时间升序）。

    兼容两种结构：
      * 新：animator["keyframes"] = [{"channel":..,"time":..,"data_points":[{x,y,z}]}, ...]
      * 旧：animator["position"] = {"0.25": {x,y,z}, ...}
    """
    channels = {}

    def add(channel, time_text, point):
        try:
            time = float(time_text)
        except (TypeError, ValueError):
            return
        channels.setdefault(channel, []).append(
            (time,
             float(point.get("x", 0) or 0),
             float(point.get("y", 0) or 0),
             float(point.get("z", 0) or 0)))

    raw = animator.get("keyframes")
    if isinstance(raw, list):
        for entry in raw:
            channel = entry.get("channel")
            if channel not in ("position", "rotation", "scale"):
                continue
            for point in (entry.get("data_points") or []):
                add(channel, entry.get("time", 0), point)
    elif isinstance(raw, dict):
        for channel, keys in raw.items():
            if channel not in ("position", "rotation", "scale"):
                continue
            if isinstance(keys, dict):
                for time_text, point in keys.items():
                    if isinstance(point, dict):
                        add(channel, time_text, point)

    # ⚠️ GeckoLib 的 buildKeyframeStack 是**按顺序逐帧算时间差**的，所以时间必须
    #    严格升序、第一帧还得在 0，否则报
    #    "Invalid keyframe data - multiple starting keyframes?0"。
    for channel, frames in channels.items():
        frames.sort(key=lambda f: f[0])
        if frames and frames[0][0] > 1e-9:
            frames.insert(0, (0.0, frames[0][1], frames[0][2], frames[0][3]))
        channels[channel] = frames
    return channels


def rotate_y(x, z, yaw_deg):
    a = math.radians(yaw_deg)
    c, s = math.cos(a), math.sin(a)
    return x * c + z * s, -x * s + z * c


def build_animation(bb, yaw_deg):
    names = uuid_names(bb)
    animations = {}
    for anim in bb.get("animations", []):
        bones = {}
        for uuid, animator in (anim.get("animators") or {}).items():
            raw_name = names.get(uuid)
            bone_name = BONE_NAMES.get(raw_name, raw_name or "bone")
            channels_out = {}
            for channel, frames in collect_keyframes(animator).items():
                entries = {}
                for time, x, y, z in frames:
                    if channel == "position" and yaw_deg:
                        x, z = rotate_y(x, z, yaw_deg)
                    entries["%g" % round(time, 5)] = [x, y, z]
                if entries:
                    # GeckoLib 的动画键名：position / rotation / scale
                    channels_out[channel if channel != "position" else "position"] = entries
            if channels_out:
                bones[bone_name] = channels_out
        if not bones:
            continue
        length = anim.get("length")
        if length is None:
            times = [t for a in (anim.get("animators") or {}).values()
                     for ch in collect_keyframes(a).values() for t, *_ in ch]
            length = max(times) if times else 0.0
        animations[anim.get("name") or "animation"] = {
            "loop": anim.get("loop") or "hold",
            "animation_length": round(float(length), 5),
            "bones": bones,
        }
    return {"format_version": "1.8.0", "animations": animations}


# ---------------- 主流程 ----------------

def count_cubes(bone):
    total = len(bone.get("cubes", []))
    for child in bone.get("children", []) or []:
        total += count_cubes(child)
    return total


def main():
    bb = load(SOURCE)
    geo = build_geometry(bb)

    geo_dir = os.path.join(ASSETS, "geo", "block")
    anim_dir = os.path.join(ASSETS, "animations", "block")
    tex_dir = os.path.join(ASSETS, "textures", "block")
    for directory in (geo_dir, anim_dir, tex_dir):
        os.makedirs(directory, exist_ok=True)

    with open(os.path.join(geo_dir, GEO_ID + ".geo.json"), "w", encoding="utf-8") as f:
        json.dump(geo, f, ensure_ascii=False, indent=2)

    bones = geo["minecraft:geometry"][0]["bones"]
    source_cubes = len(bb.get("elements", []))
    output_cubes = sum(count_cubes(b) for b in bones)
    print("geo.json      : %d 个顶层骨骼，%d 个立方体（源模型 %d 个）"
          % (len(bones), output_cubes, source_cubes))
    assert output_cubes == source_cubes, "立方体数量对不上，转换有遗漏！"
    print("骨骼名        : %s" % [b["name"] for b in bones])

    # 只生成一份动画（朝北）；朝向的旋转在渲染器里做，没必要烘 4 份。
    anim = build_animation(bb, 0.0)
    with open(os.path.join(anim_dir, GEO_ID + ".animation.json"), "w", encoding="utf-8") as f:
        json.dump(anim, f, ensure_ascii=False, indent=2)
    for anim_name, data in anim["animations"].items():
        tracks = sum(len(v) for v in data["bones"].values())
        print("  动画「%s」length=%s loop=%s 骨骼=%d 轨道=%d"
              % (anim_name, data["animation_length"], data["loop"], len(data["bones"]), tracks))

    written = 0
    for texture in bb.get("textures", []):
        source = texture.get("source") or ""
        if source.startswith("data:image/png;base64,"):
            raw = base64.b64decode(source.split(",", 1)[1])
            with open(os.path.join(tex_dir, TEXTURE_NAME + ".png"), "wb") as f:
                f.write(raw)
            written = len(raw)
            break
    print("贴图          : %d 字节 → textures/block/%s.png" % (written, TEXTURE_NAME))


if __name__ == "__main__":
    main()
