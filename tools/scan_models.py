# -*- coding: utf-8 -*-
"""
盘点所有建模文件：格式、元素数、骨骼数、有没有动画、有没有配套的 geo.json。

    python tools/scan_models.py
"""
import io
import json
import os

ROOTS = [
    r"C:\Users\30947\Desktop\星舰计划",
    r"C:\Users\30947\Desktop\建模计划",
]


def inspect(path):
    try:
        bb = json.load(io.open(path, encoding="utf-8"))
    except Exception as e:
        return {"error": str(e)}
    info = {}
    info["format"] = (bb.get("meta") or {}).get("model_format")
    info["elements"] = len(bb.get("elements") or [])
    tex = bb.get("resolution") or {}
    info["texture"] = "%sx%s" % (tex.get("width"), tex.get("height"))

    # 骨骼（outliner 只有名字和结构，元素里带 uuid）
    names = {}

    def walk(nodes, depth=0):
        for n in nodes or []:
            if isinstance(n, dict):
                names[n.get("uuid")] = (n.get("name") or "?", depth)
                walk(n.get("children"), depth + 1)
            elif isinstance(n, str):
                names.setdefault(n, ("(元素)", depth))

    walk(bb.get("outliner"))
    info["bones"] = names

    # 动画
    anims = bb.get("animations") or []
    info["animations"] = []
    for a in anims:
        animators = a.get("animators") or {}
        with_kf = [t.get("name") for t in animators.values() if t.get("keyframes")]
        info["animations"].append({
            "name": a.get("name"),
            "length": a.get("length"),
            "loop": a.get("loop"),
            "bones": with_kf,
        })
    return info


def main():
    rows = []
    for root in ROOTS:
        if not os.path.isdir(root):
            continue
        for dirpath, dirs, files in os.walk(root):
            for f in files:
                if not f.lower().endswith(".bbmodel"):
                    continue
                p = os.path.join(dirpath, f)
                info = inspect(p)
                rows.append((p, os.path.getsize(p), os.path.getmtime(p), info))

    rows.sort(key=lambda r: (r[0].count(os.sep), r[0]))

    print("=" * 100)
    print("共 %d 个 .bbmodel" % len(rows))
    print("=" * 100)
    for p, size, mt, info in rows:
        rel = p.replace(r"C:\Users\30947\Desktop" + os.sep, "")
        print()
        print("■ %s" % rel)
        print("    %d 字节   %s" % (size, __import__("time").strftime("%m-%d %H:%M", __import__("time").localtime(mt))))
        if "error" in info:
            print("    ✗ 解析失败: %s" % info["error"])
            continue
        bones = info["bones"]
        named = [n for n, d in bones.values() if d == 0]
        print("    格式=%s  元素=%d  贴图=%s  顶层骨骼=%d" % (
            info["format"], info["elements"], info["texture"], len(named)))
        if named:
            print("    顶层骨骼名: %s" % ", ".join(str(n) for n in named[:12]))
        anims = info["animations"]
        if anims:
            for a in anims:
                print("    ★ 动画「%s」%s 秒 loop=%s，有键帧的骨骼: %s"
                      % (a["name"], a["length"], a["loop"], ", ".join(str(x) for x in a["bones"])))
        else:
            print("    （无动画）")


if __name__ == "__main__":
    main()
