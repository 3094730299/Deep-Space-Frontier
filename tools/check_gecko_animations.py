# -*- coding: utf-8 -*-
"""
GeckoLib 动画文件自检 —— 专治「动画完全不动、但日志只有一行 ERROR」这类问题。

    python tools/check_gecko_animations.py          # 只检查
    python tools/check_gecko_animations.py --fix    # 自动修（只调顺序，绝不改数值）

## 为什么要它

GeckoLib 4.9.3 的 `BakedAnimationsAdapter.getKeyframes` 有一处**不看就必踩**的规则，
已经用字节码确认（javap -p -c，偏移 149~202）：

    tick = readTimestamp(entry.getKey())
    if (tick != 0.0)          -> 正常处理这个关键帧
    if (keyframes.isEmpty())  -> 正常处理（说明 0.0 是第一个）
    否则                      -> throw JsonParseException(
                                     "Invalid keyframe data - multiple starting keyframes?" + tick)

也就是说：**只要某个通道里 `"0.0"` 不是 JSON 里的第一项，GeckoLib 就整份文件拒绝加载。**

三个要说清的后果：

1. GeckoLib **不会**帮你把关键帧排序，它按 JSON 的书写顺序读；
2. 抛出的异常会一路冒到 `FileLoader.loadAnimationsFile`，
   **整份 animation.json 全部作废** —— 同一个文件里别的动画（哪怕写得好好的）也一起没了。
   所以现象是「模型还在，但一动都不动」，而不是「只有某一个动画不播」；
3. 日志里只有一行：
   `[ERROR] [GeckoLib/]: Unable to parse animation: <动画名>`
   被别的模组的报错刷屏时极容易漏看（本项目就漏看了整整一轮）。

## 判定规则

* 规则 1（★ GeckoLib 硬性要求）：`"0.0"` 必须是该通道对象的第一项。
* 规则 2：关键帧时间必须**递增**书写（GeckoLib 不排序，乱序插值会跳变）。
* 规则 3：`loop` 只能是 `default` / `play_once` / `hold_on_last_frame`（写错会静默回退成 default）。
* 规则 4：每个 `vector` 必须是 3 个数的数组（少了 GeckoLib 直接抛异常）。

`--fix` 只做一件事：把每个通道的关键帧按键名数值**升序重排**。
**所有数值（时间、vector、easing）一个都不动** —— 修完会自我校验
「语义内容与修之前完全一致」，不一致就拒绝写入。
"""
import glob
import io
import json
import os
import sys
from collections import OrderedDict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ANIM_DIR = os.path.join(ROOT, "src", "main", "resources", "assets",
                        "deepspacedeployer", "animations", "block")

VALID_LOOP = {"default", "play_once", "hold_on_last_frame"}
CHANNELS = ("position", "rotation", "scale")


def read_pairs(path):
    """读 JSON，但保留键的书写顺序（GeckoLib 就是按这个顺序读的）。"""
    with io.open(path, encoding="utf-8") as f:
        return json.load(f, object_pairs_hook=OrderedDict)


def check_channel(anim, bone, chan, obj):
    """返回该通道的问题列表。"""
    problems = []
    keys = list(obj.keys())

    # 规则 4：vector 必须 3 个数
    for k, v in obj.items():
        if isinstance(v, dict) and "vector" in v:
            vec = v["vector"]
            if not isinstance(vec, list) or len(vec) != 3:
                problems.append((anim, bone, chan, k,
                                 "vector 必须是 3 个数的数组，实际=%r" % (vec,)))

    def as_float(k):
        try:
            return float(k)
        except ValueError:
            return None

    times = [as_float(k) for k in keys]
    if any(t is None for t in times):
        problems.append((anim, bone, chan, "-",
                         "关键帧名不是数字：%r" % (keys,)))
        return problems

    # 规则 1：0.0 必须是第一项
    if 0.0 in times and times.index(0.0) != 0:
        problems.append((anim, bone, chan, keys[times.index(0.0)],
                         "「0.0」在第 %d 项，不是第一项 → GeckoLib 会抛 "
                         "Invalid keyframe data - multiple starting keyframes?0.0，"
                         "整份文件作废" % (times.index(0.0) + 1)))

    # 规则 2：必须递增
    if times != sorted(times):
        problems.append((anim, bone, chan, "-",
                         "关键帧顺序不是递增：%s" % (times,)))

    return problems


def check_file(path):
    data = read_pairs(path)
    problems = []
    name = os.path.basename(path)
    anims = data.get("animations", {})
    if not anims:
        problems.append((name, "-", "-", "-", "没有 animations 段"))
    for anim, ad in anims.items():
        loop = ad.get("loop")
        if loop not in VALID_LOOP:
            problems.append((anim, "-", "-", "-",
                             "loop=%r 不在 %s 里（会被静默当成 default）"
                             % (loop, sorted(VALID_LOOP))))
        for bone, bd in ad.get("bones", {}).items():
            for chan in CHANNELS:
                obj = bd.get(chan)
                if isinstance(obj, dict):
                    problems += check_channel(anim, bone, chan, obj)
    return data, problems


def reorder(data):
    """把每个通道的关键帧按键名数值升序重排（不改任何数值）。"""
    for ad in data.get("animations", {}).values():
        for bd in ad.get("bones", {}).values():
            for chan in CHANNELS:
                obj = bd.get(chan)
                if isinstance(obj, dict) and obj:
                    try:
                        order = sorted(obj.keys(), key=float)
                    except ValueError:
                        continue
                    if list(obj.keys()) != order:
                        items = [(k, obj[k]) for k in order]
                        obj.clear()
                        obj.update(items)
    return data


def dump(path, data):
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")


def main():
    fix = "--fix" in sys.argv

    files = sorted(glob.glob(os.path.join(ANIM_DIR, "*.json")))
    if not files:
        print("× 没找到动画文件：%s" % ANIM_DIR)
        return 1

    print("检查 %d 份动画文件……\n" % len(files))
    total = 0
    fixed = 0

    for path in files:
        name = os.path.basename(path)
        data, problems = check_file(path)

        if problems and fix:
            # 语义快照：重排前后必须完全一致，否则拒绝写盘
            before = json.loads(json.dumps(data, ensure_ascii=False))
            new = reorder(data)
            after = json.loads(json.dumps(new, ensure_ascii=False))
            if before != after:
                print("× %s：重排会改到数值，已拒绝写入（这不该发生，请报告）" % name)
                return 1
            dump(path, new)
            _, problems = check_file(path)
            fixed += 1
            print("✔ %s：已修正关键帧顺序（数值未动）%s"
                  % (name, "" if not problems else " —— 但仍有 %d 处问题" % len(problems)))

        for anim, bone, chan, key, msg in problems:
            total += 1
            print("× %s" % name)
            print("    动画=%s 骨骼=%s 通道=%s 关键帧=%s" % (anim, bone, chan, key))
            print("    %s" % msg)

        if not problems:
            anims = list(json.load(io.open(path, encoding="utf-8"))["animations"].keys())
            print("✔ %-34s 动画：%s" % (name, "、".join(anims)))

    print()
    if total:
        print("【%d 处问题】%s" % (total, "（已修）" if fix else " → 跑 --fix 自动修正"))
        return 0 if fix else 1
    print("全部动画文件符合 GeckoLib 规则 ✓%s" % ("（本次修正了 %d 份）" % fixed if fixed else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
