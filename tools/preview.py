# -*- coding: utf-8 -*-
"""
贴图预览器：把生成好的贴图放大 8 倍拼成一张图，不用进游戏就能看效果。
    python tools/preview.py
输出：docs/textures-preview.png
"""
import os

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
ASSETS = os.path.join(PROJECT, "src", "main", "resources", "assets", "deepspacedeployer")
OUT = os.path.join(PROJECT, "docs", "textures-preview.png")

SCALE = 8
PAD = 8


def load_dir(path):
    if not os.path.isdir(path):
        return []
    names = sorted(f for f in os.listdir(path) if f.endswith(".png"))
    return [(n[:-4], Image.open(os.path.join(path, n)).convert("RGBA")) for n in names]


def main():
    blocks = load_dir(os.path.join(ASSETS, "textures", "block"))
    items = load_dir(os.path.join(ASSETS, "textures", "item"))
    logo_path = os.path.join(PROJECT, "src", "main", "resources", "deepspacedeployer_logo.png")

    cell = 16 * SCALE
    cols = max(len(blocks), len(items), 1)
    width = cols * (cell + PAD) + PAD
    height = (2 * (cell + PAD + 12)) + PAD

    canvas = Image.new("RGBA", (width, height), (24, 26, 34, 255))

    for row, group in enumerate((blocks, items)):
        y = PAD + row * (cell + PAD + 12)
        for i, (name, img) in enumerate(group):
            x = PAD + i * (cell + PAD)
            big = img.resize((cell, cell), Image.NEAREST)
            canvas.alpha_composite(big, (x, y))

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    canvas.save(OUT)

    if os.path.exists(logo_path):
        logo = Image.open(logo_path).convert("RGBA")
        logo.save(os.path.join(PROJECT, "docs", "logo-preview.png"))

    print("方块贴图：", ", ".join(n for n, _ in blocks))
    print("物品贴图：", ", ".join(n for n, _ in items))
    print("已输出：", OUT)


if __name__ == "__main__":
    main()
