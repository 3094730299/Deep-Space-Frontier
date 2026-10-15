# -*- coding: utf-8 -*-
"""
发布素材生成器：封面图 + 商店页展示图（CurseForge / Modrinth 用）
    python tools/gen_artwork.py

输出到 art/：
  cover-512.png          1:1 封面（模组页主图）
  gallery-1-overview.png 1280×720 展示图（功能总览）
  gallery-2-variants.png 1280×720 展示图（6 公司 × 3 等级外观）

中文字体优先用微软雅黑（Windows 自带），找不到就退回 Pillow 默认字体（英文仍正常）。
"""
import glob
import os
import random

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
OUT_DIR = os.path.join(PROJECT, "art")
BLOCK_TEX = os.path.join(PROJECT, "src", "main", "resources", "assets", "deepspacedeployer", "textures", "block")
ITEM_TEX = os.path.join(PROJECT, "src", "main", "resources", "assets", "deepspacedeployer", "textures", "item")

COMPANIES = [
    ("threshold", (232, 244, 255)),
    ("whisperbelt", (180, 124, 255)),
    ("brokenedge", (255, 90, 90)),
    ("skimmer", (255, 179, 71)),
    ("bedrock", (154, 140, 120)),
    ("astralink", (84, 200, 255)),
]

BG_TOP = (12, 14, 24)
BG_BOTTOM = (26, 32, 48)
ACCENT = (84, 200, 255)
GOLD = (255, 209, 102)

FONT_CANDIDATES = [
    r"C:\Windows\Fonts\msyh.ttc",
    r"C:\Windows\Fonts\msyhbd.ttc",
    r"C:\Windows\Fonts\simhei.ttf",
]


def font(size, bold=False):
    paths = FONT_CANDIDATES if not bold else FONT_CANDIDATES[1:] + FONT_CANDIDATES[:1]
    for path in paths:
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size)
            except Exception:
                continue
    return ImageFont.load_default(size=size)


def gradient(size, top, bottom):
    img = Image.new("RGB", size, top)
    draw = ImageDraw.Draw(img)
    w, h = size
    for y in range(h):
        t = y / max(1, h - 1)
        color = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
        draw.line([(0, y), (w, y)], fill=color)
    return img


def stars(img, count, seed=7):
    rnd = random.Random(seed)
    draw = ImageDraw.Draw(img)
    w, h = img.size
    for _ in range(count):
        x, y = rnd.randrange(w), rnd.randrange(int(h * 0.6))
        v = rnd.randint(140, 255)
        r = 1 if rnd.random() > 0.15 else 2
        draw.ellipse([x, y, x + r, y + r], fill=(v, v, min(255, v + 20)))


def factory_silhouette(draw, x, y, w, h, color=(38, 44, 58), edge=ACCENT):
    """厂房剪影：厂房 + 三根烟囱 + 一排亮着的窗"""
    draw.rectangle([x, y + h * 0.35, x + w, y + h], fill=color)
    draw.rectangle([x, y + h * 0.35, x + w, y + h], outline=edge)
    for i in range(3):
        cx = x + w * (0.15 + i * 0.22)
        cw = w * 0.12
        draw.rectangle([cx, y, cx + cw, y + h * 0.35], fill=color)
        draw.rectangle([cx, y, cx + cw, y + h * 0.35], outline=edge)
    cols = 4
    for i in range(cols):
        wx = x + w * (0.08 + i * 0.23)
        draw.rectangle([wx, y + h * 0.5, wx + w * 0.13, y + h * 0.78], fill=ACCENT)


def draw_text_center(draw, text, y, fnt, fill, width):
    box = draw.textbbox((0, 0), text, font=fnt)
    draw.text(((width - (box[2] - box[0])) / 2, y), text, font=fnt, fill=fill)


def cover():
    size = 512
    img = gradient((size, size), BG_TOP, BG_BOTTOM).convert("RGBA")
    stars(img, 160)
    draw = ImageDraw.Draw(img)

    # 外框
    draw.rectangle([6, 6, size - 7, size - 7], outline=ACCENT, width=3)
    draw.rectangle([14, 14, size - 15, size - 15], outline=(84, 200, 255, 90), width=1)

    # 六家公司色条
    chip_w = 46
    total = chip_w * len(COMPANIES) + 8 * (len(COMPANIES) - 1)
    start = (size - total) // 2
    for i, (_, color) in enumerate(COMPANIES):
        x = start + i * (chip_w + 8)
        draw.rectangle([x, 60, x + chip_w, 78], fill=color)

    draw_text_center(draw, "DEEP SPACE", 118, font(58, True), (240, 248, 255), size)
    draw_text_center(draw, "DEPLOYER", 176, font(58, True), ACCENT, size)
    draw_text_center(draw, "深空铸造体", 248, font(44, True), GOLD, size)

    factory_silhouette(draw, 70, 300, size - 140, 120)

    draw_text_center(draw, "Aeronautics Factory Addon", 442, font(21), (200, 214, 232), size)
    draw_text_center(draw, "Minecraft 1.21.1  ·  NeoForge", 468, font(19), (150, 164, 184), size)

    path = os.path.join(OUT_DIR, "cover-512.png")
    img.convert("RGB").save(path)
    print("已生成", path)


def texture_cell(name, scale):
    path = os.path.join(BLOCK_TEX, name + ".png")
    if not os.path.exists(path):
        return None
    return Image.open(path).convert("RGBA").resize((16 * scale, 16 * scale), Image.NEAREST)


SHORT_NAMES = {
    "threshold": "Thresh",
    "whisperbelt": "Whisper",
    "brokenedge": "Broken",
    "skimmer": "Skimmer",
    "bedrock": "Bedrock",
    "astralink": "Astralink",
}

MACHINE_LABELS = [
    ("factory_terminal", "商店 Terminal"),
    ("factory_core", "工厂 Core"),
    ("welder", "焊接 Welder"),
    ("buyer", "收购 Buyer"),
]


def gallery_variants():
    w, h = 1280, 720
    img = gradient((w, h), BG_TOP, BG_BOTTOM).convert("RGBA")
    stars(img, 220)
    draw = ImageDraw.Draw(img)
    draw.rectangle([4, 4, w - 5, h - 5], outline=ACCENT, width=2)

    draw_text_center(draw, "6 Companies × 3 Tiers = 72 Looks", 20, font(40, True), (240, 248, 255), w)
    draw_text_center(draw, "六家公司 × 三个等级 = 72 种机器外观", 70, font(26, True), GOLD, w)

    scale = 3
    cell = 16 * scale          # 48
    left = 190                 # 给左边的行标签留位置
    top = 130
    gap_x = 14
    for col, (company, color) in enumerate(COMPANIES):
        x = left + col * (cell + gap_x)
        label = SHORT_NAMES.get(company, company)
        box = draw.textbbox((0, 0), label, font=font(15))
        draw.text((x + (cell - (box[2] - box[0])) / 2, top - 26), label, font=font(15), fill=color)

    for row, (machine, label) in enumerate(MACHINE_LABELS):
        group_y = top + row * (cell * 3 + 12)
        draw.text((16, group_y + cell * 1.2), label, font=font(17), fill=(214, 226, 242))
        for col, (company, _) in enumerate(COMPANIES):
            x = left + col * (cell + gap_x)
            for tier_index, tier in enumerate(("low", "mid", "high")):
                tex = texture_cell("%s_%s_%s" % (machine, company, tier), scale)
                if tex:
                    img.alpha_composite(tex, (x, group_y + tier_index * cell))
            if col == 0:
                for tier_index, tier_label in enumerate(("L", "M", "H")):
                    draw.text((left - 18, group_y + tier_index * cell + 16), tier_label,
                              font=font(15), fill=(150, 164, 184))
        # 组之间画一条分隔线
        if row < len(MACHINE_LABELS) - 1:
            line_y = group_y + cell * 3 + 4
            draw.line([(16, line_y), (left + len(COMPANIES) * (cell + gap_x), line_y)],
                      fill=(60, 70, 88))

    # 右侧说明
    lines = [
        ("每个方块都有公司配色与等级标记", GOLD),
        ("Company tint + tier pips (1/2/3)", (220, 230, 245)),
        ("低级厂：只能买基础货、收购价 0.8×", (200, 214, 232)),
        ("中级厂：解锁工厂核心、收购价 1.0×", (200, 214, 232)),
        ("高级厂：解锁焊接台 + 开关碰撞、1.2×", (200, 214, 232)),
        ("18 座工厂会按等级刷在不同群系", (200, 214, 232)),
        ("（含 BiomesOPlenty 群系支持）", (150, 164, 184)),
    ]
    ty = 200
    for text, color in lines:
        draw.text((860, ty), text, font=font(19), fill=color)
        ty += 32

    path = os.path.join(OUT_DIR, "gallery-2-variants.png")
    img.convert("RGB").save(path)
    print("已生成", path)


def gallery_overview():
    w, h = 1280, 720
    img = gradient((w, h), (10, 12, 20), (22, 26, 40)).convert("RGBA")
    stars(img, 200)
    draw = ImageDraw.Draw(img)
    draw.rectangle([4, 4, w - 5, h - 5], outline=ACCENT, width=2)

    draw_text_center(draw, "DEEP SPACE DEPLOYER", 30, font(56, True), (240, 248, 255), w)
    draw_text_center(draw, "深空铸造体 · 航空学附属工厂模组", 100, font(32, True), GOLD, w)

    factory_silhouette(draw, 60, 190, 420, 220)

    # 物品图标一列
    icons = ["bank_card", "threshold_scrip", "whisperbelt_scrip", "brokeredge_scrip",
             "skimmer_scrip", "bedrock_scrip", "astralink_scrip", "blueprint_voucher"]
    x = 540
    y = 200
    for i, name in enumerate(icons):
        path = os.path.join(ITEM_TEX, name + ".png")
        if os.path.exists(path):
            tex = Image.open(path).convert("RGBA").resize((64, 64), Image.NEAREST)
            img.alpha_composite(tex, (x + (i % 4) * 72, y + (i // 4) * 72))

    lines = [
        "六家公司各自的票据 + 银行卡账户体系",
        "收购站自动收材料换钱（支持漏斗/机械手）",
        "商店按等级 + 声望解锁模块，也卖蓝图凭证",
        "工厂：选蓝图 → 投材料 → 一键组装 → 落地物理化",
        "俯视图选投放点 / 焊接台焊接 / 高级台开关碰撞",
        "6 公司 × 低中高级工厂在世界自然生成",
    ]
    ty = 380
    for text in lines:
        draw.text((540, ty), "· " + text, font=font(22), fill=(214, 226, 242))
        ty += 34

    draw_text_center(draw, "Minecraft 1.21.1  ·  NeoForge  ·  Create: Aeronautics addon", 660, font(22),
                     (150, 164, 184), w)

    path = os.path.join(OUT_DIR, "gallery-1-overview.png")
    img.convert("RGB").save(path)
    print("已生成", path)


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    cover()
    gallery_overview()
    gallery_variants()


if __name__ == "__main__":
    main()
