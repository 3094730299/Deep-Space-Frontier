# -*- coding: utf-8 -*-
"""
导入老板的新通用终端模型（替换旧的）。

    python tools/import_new_terminal_model.py

做三件事：
  1. 复制新 geo.json → assets/.../geo/block/universal_terminal.geo.json
     并**修正贴图尺寸声明**（geo 里写 64×64，实际贴图是 128×128 —— 不改会 UV 全错）；
  2. 从 .bbmodel 里**生成 animation.json**（新格式是 keyframes 列表 + data_points，
     要转成 GeckoLib 的 {"vector": [x, y, z]} 写法）；
  3. 复制新贴图。
"""
import io
import json
import os
import shutil

ROOT = r"C:\Users\30947\Documents\deepseek-harness\default-workspace\deep-space-deployer"
A = os.path.join(ROOT, "src", "main", "resources", "assets", "deepspacedeployer")
SRC = r"C:\Users\30947\Desktop\建模计划"

GEO_SRC = os.path.join(SRC, r"半成品\动态建模\通用终端 - Converted.geo.json")
BB_SRC = os.path.join(SRC, r"模板\通用终端 - Converted.bbmodel")
TEX_SRC = os.path.join(SRC, r"建模贴图\通用终端.png")

NAME = "universal_terminal"
TEX_SIZE = (128, 128)


def tidy_vector(value):
    """把 [x, y, z] 里的浮点收拾干净（-0.0 → 0，去掉多余小数）"""
    out = []
    for v in value:
        f = round(float(v), 5)
        if abs(f) < 1e-9:
            f = 0.0
        out.append(f)
    return out


def main():
    # ---------- 1) geo.json ----------
    geo = json.load(io.open(GEO_SRC, encoding="utf-8"))
    g = geo["minecraft:geometry"][0]
    # ⚠️ 不要动贴图尺寸声明！UV 坐标是按 Blockbench 里的贴图尺寸写的，
    #    改了就全部错位（我曾把 64 改成 128，结果整个模型贴图乱掉）。
    old = (g["description"].get("texture_width"), g["description"].get("texture_height"))
    g["description"]["identifier"] = "geometry." + NAME

    # ⚠️ GeckoLib 4.9.3 只支持 format_version = 1.12.0。
    #    新版 Blockbench 会导出 1.21.110 之类，此时几何被静默拒绝加载：
    #    [GeckoLib WARN] Unsupported geometry json version ... Supported versions: 1.12.0
    #    现象是模型能显示但完全没有动画。两种格式字段相同，直接改版本号即可。
    fv = geo.get("format_version")
    if fv != "1.12.0":
        geo["format_version"] = "1.12.0"
        print("      format_version: %s → 1.12.0（GeckoLib 只认这个）" % fv)
    geo_out = os.path.join(A, "geo", "block", NAME + ".geo.json")
    io.open(geo_out, "w", encoding="utf-8", newline="").write(
        json.dumps(geo, ensure_ascii=False, indent=2))
    print("geo   → %s" % os.path.relpath(geo_out, ROOT))
    print("      贴图尺寸声明: %s（保持导出原值，不修改）" % (old,))

    def count(bones):
        n = 0
        for b in bones:
            n += len(b.get("cubes", []))
            n += count(b.get("children", []) or [])
        return n

    names = []

    def collect(bones, depth=0):
        for b in bones:
            names.append("%s%s(%d)" % ("  " * depth, b.get("name"), len(b.get("cubes", []))))
            collect(b.get("children", []) or [], depth + 1)

    collect(g["bones"])
    print("      骨骼 %d 个，立方体 %d 个" % (len(g["bones"]), count(g["bones"])))
    for line in names:
        print("        " + line)

    # ---------- 2) animation.json（从 bbmodel 生成）----------
    bb = json.load(io.open(BB_SRC, encoding="utf-8"))
    animations = {}
    for anim in bb.get("animations", []):
        bones_out = {}
        for uuid, animator in (anim.get("animators") or {}).items():
            bone = animator.get("name")
            if not bone:
                continue
            frames = animator.get("keyframes")
            if not isinstance(frames, list):
                continue
            channels = {}
            for entry in frames:
                channel = entry.get("channel")
                if channel not in ("position", "rotation", "scale"):
                    continue
                time = entry.get("time", 0)
                for point in (entry.get("data_points") or []):
                    vector = tidy_vector([point.get("x", 0), point.get("y", 0), point.get("z", 0)])
                    key = "%g" % round(float(time), 5)
                    channels.setdefault(channel, {})[key] = {"vector": vector}
            if channels:
                bones_out[bone] = channels
        if not bones_out:
            continue
        # ⚠️ GeckoLib 只认 default / play_once / hold_on_last_frame。
        #    bbmodel 里写的是 "hold"，必须翻译，否则会回退成 default（动画不播）。
        raw_loop = str(anim.get("loop") or "play_once").lower()
        loop = {"hold": "hold_on_last_frame", "true": "loop", "false": "play_once"}.get(
            raw_loop, raw_loop or "play_once")
        animations[anim.get("name") or "animation"] = {
            "loop": loop,
            "animation_length": round(float(anim.get("length") or 0.0), 5),
            "bones": bones_out,
        }

    anim_out = os.path.join(A, "animations", "block", NAME + ".animation.json")
    io.open(anim_out, "w", encoding="utf-8", newline="").write(
        json.dumps({"format_version": "1.8.0", "animations": animations},
                   ensure_ascii=False, indent=2))
    print()
    print("anim  → %s" % os.path.relpath(anim_out, ROOT))
    for name, data in animations.items():
        print("      动画「%s」length=%s loop=%s" % (name, data["animation_length"], data["loop"]))
        for bone, chans in data["bones"].items():
            for ch, keys in chans.items():
                times = sorted(keys, key=float)
                vals = [keys[t]["vector"] for t in times]
                print("        %-8s %-9s %d 帧  %s" % (bone, ch, len(times), vals))

    # ---------- 3) 贴图 ----------
    tex_out = os.path.join(A, "textures", "block", NAME + ".png")
    shutil.copyfile(TEX_SRC, tex_out)
    from PIL import Image
    size = Image.open(tex_out).size
    print()
    print("贴图  → %s（%s，%d 字节）" % (os.path.relpath(tex_out, ROOT), size, os.path.getsize(tex_out)))


if __name__ == "__main__":
    main()
