# -*- coding: utf-8 -*-
"""
给「精构仪」的【旧 UV 布局占位版】生成贴图（2026-10-19 的临时方案，现在已不用）。

    python tools/gen_precision_fabricator_texture.py
    python tools/gen_precision_fabricator_texture.py --force

⚠️ 现在老板已经在 .bbmodel 里画好了成品贴图（红色涂装，4146 色），
   成品贴图是从 bbmodel 的内嵌贴图直接导出的，**不要再跑这个脚本**。
   本脚本只认识旧的 13 色 UV 布局图；源图检测到 >64 色会直接拒绝。
   默认也不覆盖已存在的 precision_fabricator.png（除非 --force）。

## 历史：为什么当初不能直接用那张 PNG

`art/建模计划/建模贴图/精构仪.png` 当时是 **UV 布局图**：13 种平涂颜色，
一种颜色代表一组 UV 面，不是画好的材质。直接贴上去，机械臂/底座会像彩虹。

## 做法

读 `art/建模计划/模板/精构仪.bbmodel` 的骨骼分组，按"零件是干什么的"上材质：

  · **固定建模**（136 个 cube）：白色/浅蓝/钢/深钢机身；黄色 = 金色滑轨；
    蓝色 = 蓝色饰条；绿/粉（底座上的 UV 分区）= 钢（避免彩虹）。
  · **水平机 / 高低机 / 机械小臂 / 机械手轴**：统一深灰机械臂。
  · **蓝图**（1 个 cube）：深色底 + 青色网格，当屏幕/图纸。

再按面法线做明暗（up 亮、down 暗）、给大面板描 1px 深色边，输出 512×512
（geo 声明 256、PNG 2 倍，符合 README 铁律 13：声明不动，PNG 取整数倍）。

## 源文件（已从桌面同步到 art/）

    art/建模计划/模板/精构仪.bbmodel
    art/建模计划/建模贴图/精构仪.png
    art/建模计划/半成品/动态建模/精构仪/精构仪.geo.json / .animation.json
"""
import json
import math
import os
import random
import sys

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ART = os.path.join(ROOT, "art", "建模计划")
BB = os.path.join(ART, "模板", "精构仪.bbmodel")
UV = os.path.join(ART, "建模贴图", "精构仪.png")
OUT = os.path.join(ROOT, "src", "main", "resources", "assets", "deepspacedeployer",
                   "textures", "block", "precision_fabricator.png")

# 面 -> 明暗系数（模型坐标，不是世界坐标；只做整体立体感）
FACE_SHADE = {"up": 1.10, "down": 0.82, "north": 0.96, "south": 1.02,
              "east": 0.92, "west": 1.02}

# UV 布局颜色 -> 机身目标色。"固定建模"里的绿/粉是底座分区，按钢处理，避免彩虹。
BODY_COLOR = {
    (0xec, 0xf8, 0xfd): (210, 216, 223),   # 白
    (0xb4, 0xd4, 0xe1): (158, 180, 196),   # 浅蓝
    (0x6e, 0x78, 0x8c): (122, 132, 147),   # 钢
    (0x53, 0x61, 0x74): (86, 96, 112),     # 深钢
    (0xf8, 0xdd, 0x72): (202, 170, 80),    # 黄 -> 金
    (0xff, 0xf8, 0x99): (230, 208, 130),   # 浅黄 -> 浅金
    (0x5b, 0xbc, 0xf4): (78, 148, 206),    # 蓝
    (0x7b, 0xd4, 0xff): (120, 185, 230),   # 浅蓝
    (0x43, 0xe8, 0x8d): (110, 122, 138),   # 绿 -> 钢（底座）
    (0x7b, 0xff, 0xa3): (120, 132, 148),
    (0xf4, 0x86, 0x86): (110, 122, 138),   # 粉 -> 钢（底座）
    (0xff, 0xa7, 0xa4): (120, 132, 148),
}

ARM_COLOR = (96, 106, 122)                 # 机械臂：深灰
SCREEN_DARK = (16, 22, 30)                 # 蓝图屏：深色底
SCREEN_LINE = (55, 160, 150)               # 蓝图屏：青色网格
SCREEN_EDGE = (80, 205, 195)               # 蓝图屏：外框


def kind_of(group_name):
    """0 = 固定建模（机身/底座），1 = 机械臂，2 = 蓝图屏"""
    if group_name == "蓝图":
        return 2
    if group_name in ("水平机", "高低机", "机械小臂", "机械手轴"):
        return 1
    return 0


def main():
    if os.path.exists(OUT) and "--force" not in sys.argv:
        print("已存在（不覆盖）：%s" % os.path.relpath(OUT, ROOT))
        print("以后手绘了正式贴图也不会被冲掉；确实要重生成请加 --force")
        return

    bb = json.load(open(BB, encoding="utf-8"))
    probe = Image.open(UV).convert("RGBA")
    colors = len(probe.getcolors(1 << 24) or [])
    if colors > 64:
        print("源图是成品贴图（%d 色），不是 UV 布局；不生成。" % colors)
        print("成品贴图直接用 .bbmodel 内嵌的那张，不要再跑这个脚本。")
        return
    uv = probe
    w, h = uv.size
    scale = w / 256.0                     # UV 空间 256 -> PNG 像素
    uvpx = uv.load()

    # ---------- 1) 骨骼分组 -> 每个 UV 面属于哪一类 ----------
    groups = {g["uuid"]: g.get("name") for g in bb.get("groups", [])}
    elements = {e["uuid"]: e for e in bb["elements"]}
    elem_group = {}

    def walk(node, current):
        if isinstance(node, str):
            if node in elements:
                elem_group[node] = current
            return
        if isinstance(node, dict):
            group_name = groups.get(node.get("uuid"), current)
            for child in node.get("children", []) or []:
                walk(child, group_name)

    for node in bb.get("outliner", []):
        walk(node, "整体")

    gid = np.full((h, w), 255, np.uint8)
    shade = np.ones((h, w), np.float32)
    border = np.zeros((h, w), np.uint8)
    for uuid, element in elements.items():
        if element.get("name") == "cube_outline":
            continue                       # Blockbench 编辑辅助，不是模型
        group_id = kind_of(elem_group.get(uuid))
        for face, data in (element.get("faces") or {}).items():
            rect = data.get("uv")
            if not rect:
                continue
            u1, v1, u2, v2 = rect
            x1 = max(0, min(w - 1, int(round(min(u1, u2) * scale))))
            x2 = max(0, min(w - 1, int(round(max(u1, u2) * scale))))
            y1 = max(0, min(h - 1, int(round(min(v1, v2) * scale))))
            y2 = max(0, min(h - 1, int(round(max(v1, v2) * scale))))
            gid[y1:y2 + 1, x1:x2 + 1] = group_id
            shade[y1:y2 + 1, x1:x2 + 1] = FACE_SHADE.get(face, 1.0)
            # 大面板描 1px 深色边（细条不描，免得把 1px 的薄板吃掉）
            if (x2 - x1) >= 6 and (y2 - y1) >= 6:
                border[y1, x1:x2 + 1] = 1
                border[y2, x1:x2 + 1] = 1
                border[y1:y2 + 1, x1] = 1
                border[y1:y2 + 1, x2] = 1

    # ---------- 2) 上色 ----------
    rnd = random.Random(20261019)         # 固定种子：每次生成一致
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    dst = out.load()
    for y in range(h):
        for x in range(w):
            r, g, b, a = uvpx[x, y]
            if a <= 8:
                continue
            group_id = int(gid[y, x])
            if group_id == 2:
                # 蓝图屏：深色底 + 青色网格，外框亮青
                target = (SCREEN_DARK if not (x % 6 == 0 or y % 6 == 0)
                          else SCREEN_LINE)
                if border[y, x]:
                    target = SCREEN_EDGE
            elif group_id == 1:
                # 机械臂：统一深灰，按 UV 亮度轻微变化
                v = max(r, g, b) / 255.0
                target = (int(ARM_COLOR[0] * (0.92 + 0.16 * v)),
                          int(ARM_COLOR[1] * (0.92 + 0.16 * v)),
                          int(ARM_COLOR[2] * (0.92 + 0.16 * v)))
            else:
                target = BODY_COLOR.get((r, g, b), (120, 130, 145))

            s = float(shade[y, x])
            s *= 1.0 + rnd.uniform(-0.015, 0.015)
            s *= 1.0 + 0.018 * math.sin(y * 1.9 + x * 0.06)
            if border[y, x] and group_id != 2:
                s *= 0.80

            dst[x, y] = (max(0, min(255, int(target[0] * s))),
                         max(0, min(255, int(target[1] * s))),
                         max(0, min(255, int(target[2] * s))),
                         255)

    out.save(OUT)
    colors = len(out.convert("RGBA").getcolors(1 << 24) or [])
    counts = {}
    for uuid in elements:
        name = elem_group.get(uuid) or "?"
        counts[name] = counts.get(name, 0) + 1
    print("骨骼分组：%s" % counts)
    print("输出：%s（%dx%d，%d 色）"
          % (os.path.relpath(OUT, ROOT), out.width, out.height, colors))


if __name__ == "__main__":
    main()
