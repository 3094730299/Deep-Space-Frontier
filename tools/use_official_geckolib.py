# -*- coding: utf-8 -*-
"""
把老板导出的 GeckoLib 官方文件接进模组。

    python tools/use_official_geckolib.py

几何（.geo.json）和动画（.animation.json）都用**官方导出器的产物**，
脚本只做三件确定的事：
  1. 复制到 GeckoLib 要求的路径（geo/block/、animations/block/）；
  2. 修正 geo.json 的 identifier（GeckoLib 不看它，但保持整洁）；
  3. 打印骨骼名，供 Java 侧核对。
"""
import io
import json
import os
import shutil

ROOT = r"C:\Users\30947\Documents\deepseek-harness\default-workspace\deep-space-deployer"
A = os.path.join(ROOT, "src", "main", "resources", "assets", "deepspacedeployer")
SRC = r"C:\Users\30947\Desktop\星舰计划\动态模型"

GEO_SRC = os.path.join(SRC, "星联动力商店终端 - Converted.geo.json")
ANIM_SRC = os.path.join(SRC, "通用商店终端 - Converted.animation.json")
TEX_SRC = os.path.join(SRC, "texture.png")

NAME = "universal_terminal"


def main():
    for directory in ("geo/block", "animations/block", "textures/block"):
        os.makedirs(os.path.join(A, directory), exist_ok=True)

    # ---- 几何 ----
    geo = json.load(io.open(GEO_SRC, encoding="utf-8"))
    geometry = geo["minecraft:geometry"][0]
    geometry["description"]["identifier"] = "geometry." + NAME
    geo_out = os.path.join(A, "geo", "block", NAME + ".geo.json")
    io.open(geo_out, "w", encoding="utf-8", newline="").write(
        json.dumps(geo, ensure_ascii=False, indent=2))
    print("geo   → %s" % os.path.relpath(geo_out, ROOT))

    bones = []

    def collect(list_, depth=0):
        for bone in list_:
            bones.append((bone.get("name"), len(bone.get("cubes", [])), depth))
            collect(bone.get("children", []) or [], depth + 1)

    collect(geometry["bones"])
    print("      骨骼：%s" % ", ".join("%s(%d)" % (n, c) for n, c, _ in bones))

    # ---- 动画 ----
    anim = json.load(io.open(ANIM_SRC, encoding="utf-8"))
    anim_out = os.path.join(A, "animations", "block", NAME + ".animation.json")
    io.open(anim_out, "w", encoding="utf-8", newline="").write(
        json.dumps(anim, ensure_ascii=False, indent=2))
    print("anim  → %s" % os.path.relpath(anim_out, ROOT))
    for anim_name, data in (anim.get("animations") or {}).items():
        print("      动画「%s」length=%s loop=%s 骨骼=%s"
              % (anim_name, data.get("animation_length"), data.get("loop"),
                 list((data.get("bones") or {}).keys())))

    # ---- 贴图 ----
    tex_out = os.path.join(A, "textures", "block", NAME + ".png")
    shutil.copyfile(TEX_SRC, tex_out)
    print("贴图  → %s（%d 字节）" % (os.path.relpath(tex_out, ROOT), os.path.getsize(tex_out)))


if __name__ == "__main__":
    main()
