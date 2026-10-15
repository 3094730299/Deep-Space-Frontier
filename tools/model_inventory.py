# -*- coding: utf-8 -*-
"""
建模清单：把 .bbmodel 里的信息完整读出来（骨骼名、贴图、动画、尺寸），
并生成一份「待办清单」文档，供逐个建模排期。

    python tools/model_inventory.py
"""
import base64
import io
import json
import os
import time

ROOTS = [r"C:\Users\30947\Desktop\星舰计划", r"C:\Users\30947\Desktop\建模计划"]
OUT_MD = (r"C:\Users\30947\Documents\deepseek-harness\default-workspace\deep-space-deployer"
          r"\docs\建模清单.md")
OUT_TEX = (r"C:\Users\30947\Documents\deepseek-harness\default-workspace\deep-space-deployer"
           r"\tools\_model_textures")


def read_model(path):
    bb = json.load(io.open(path, encoding="utf-8"))
    elements = {e["uuid"]: e for e in (bb.get("elements") or [])}

    def walk(nodes, depth=0, out=None):
        out = out if out is not None else []
        for n in nodes or []:
            if not isinstance(n, dict):
                continue
            kids = n.get("children") or []
            cubes = [k for k in kids if isinstance(k, str)]
            out.append((n.get("name") or "(未命名)", depth, len(cubes)))
            walk([k for k in kids if isinstance(k, dict)], depth + 1, out)
        return out

    bones = walk(bb.get("outliner"))
    # 没放进骨骼的元素（顶层裸元素）也要算
    loose = [k for k in (bb.get("outliner") or []) if isinstance(k, str)]

    # 尺寸
    lo = [1e9] * 3
    hi = [-1e9] * 3
    for e in elements.values():
        f, t = e["from"], e["to"]
        for i in range(3):
            lo[i] = min(lo[i], f[i])
            hi[i] = max(hi[i], t[i])

    # 斜面（带 rotation 的元素）
    slanted = sum(1 for e in elements.values() if e.get("rotation"))

    # 贴图
    textures = []
    for t in (bb.get("textures") or []):
        src = t.get("source") or ""
        data = None
        if src.startswith("data:image"):
            try:
                data = base64.b64decode(src.split(",", 1)[1])
            except Exception:
                data = None
        textures.append((t.get("name") or "?", t.get("uv_width"), t.get("uv_height"), data))

    anims = []
    for a in (bb.get("animations") or []):
        kf = [t.get("name") for t in (a.get("animators") or {}).values() if t.get("keyframes")]
        anims.append((a.get("name"), a.get("length"), a.get("loop"), kf))

    return {
        "format": (bb.get("meta") or {}).get("model_format"),
        "elements": len(elements),
        "bones": bones,
        "loose": len(loose),
        "lo": lo,
        "hi": hi,
        "slanted": slanted,
        "textures": textures,
        "animations": anims,
        "resolution": bb.get("resolution") or {},
    }


def main():
    os.makedirs(OUT_TEX, exist_ok=True)
    rows = []
    for root in ROOTS:
        for dirpath, dirs, files in os.walk(root):
            for f in files:
                if f.lower().endswith(".bbmodel"):
                    p = os.path.join(dirpath, f)
                    try:
                        info = read_model(p)
                    except Exception as ex:
                        info = {"error": str(ex)}
                    rows.append((p, os.path.getsize(p), info))
    rows.sort(key=lambda r: r[0])

    md = ["# 建模清单（待接入的模型）", "",
          "> 自动生成：`python tools/model_inventory.py`", ""]
    print("共 %d 个 .bbmodel\n" % len(rows))

    for p, size, info in rows:
        rel = p.replace(r"C:\Users\30947\Desktop" + os.sep, "")
        print("=" * 90)
        print("■ %s" % rel)
        if "error" in info:
            print("   ✗ %s" % info["error"])
            continue
        top = [b for b in info["bones"] if b[1] == 0]
        named = [b for b in info["bones"] if b[0] != "(未命名)"]
        print("   格式=%s  元素=%d  斜面=%d  顶层骨骼=%d  裸元素=%d"
              % (info["format"], info["elements"], info["slanted"], len(top), info["loose"]))
        if named:
            print("   骨骼名（前 14）: %s" % ", ".join("%s%s" % ("  " * b[1], b[0]) for b in named[:14]))
        lo, hi = info["lo"], info["hi"]
        print("   尺寸 X %.1f..%.1f  Y %.1f..%.1f  Z %.1f..%.1f"
              % (lo[0], hi[0], lo[1], hi[1], lo[2], hi[2]))
        print("   分辨率=%sx%s" % (info["resolution"].get("width"), info["resolution"].get("height")))
        for name, w, h, data in info["textures"]:
            saved = ""
            if data:
                fn = "%s_%s.png" % (os.path.splitext(os.path.basename(p))[0], name)
                fn = fn.replace(" ", "_")
                fp = os.path.join(OUT_TEX, fn)
                with open(fp, "wb") as fh:
                    fh.write(data)
                saved = "  → 导出 %s (%d 字节)" % (fn, len(data))
            print("   贴图 %r %sx%s%s" % (name, w, h, saved))
        for name, length, loop, kf in info["animations"]:
            print("   ★ 动画「%s」%s秒 loop=%s 键帧骨骼=%s" % (name, length, loop, ", ".join(str(k) for k in kf)))

        md.append("## %s" % os.path.splitext(os.path.basename(p))[0])
        md.append("")
        md.append("- 位置：`%s`" % rel)
        md.append("- 格式 `%s` ｜ 元素 **%d** 个 ｜ **斜面 %d** 个 ｜ 贴图 %sx%s"
                  % (info["format"], info["elements"], info["slanted"],
                     info["resolution"].get("width"), info["resolution"].get("height")))
        md.append("- 骨骼 %d 个%s" % (len(info["bones"]),
                                    ("：" + "、".join(b[0] for b in info["bones"][:10])) if info["bones"] else ""))
        if info["animations"]:
            for name, length, loop, kf in info["animations"]:
                md.append("- ★ **动画「%s」** %s 秒 ｜ %d 个骨骼有键帧" % (name, length, len(kf)))
        else:
            md.append("- 无动画")
        md.append("")

    io.open(OUT_MD, "w", encoding="utf-8", newline="").write("\n".join(md))
    print()
    print("清单已写入 %s" % OUT_MD)
    print("贴图已导出到 %s" % OUT_TEX)


if __name__ == "__main__":
    main()
