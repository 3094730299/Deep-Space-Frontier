# -*- coding: utf-8 -*-
"""
深空铸造体 —— 贴图与模型生成器
================================
    python tools/gen_textures.py

生成 4 种功能方块 × 6 家公司 × 3 个等级（共 72 种外观，含点亮版 144 张贴图）：

  assets/deepspacedeployer/textures/block/<机器>_<公司>_<等级>.png        (+ _lit)
  assets/deepspacedeployer/blockstates/<机器>.json                        18 种变体 × 点亮
  assets/deepspacedeployer/models/block/<机器>_<公司>_<等级>.json          (+ _lit)
  assets/deepspacedeployer/textures/item/*.png                            银行卡 + 6 家票据
  deepspacedeployer_logo.png                                              模组图标

⚠️ 这个脚本会覆盖上面的文件（物品贴图除外，它们也是这里生成的）。
"""
import json
import os
import random

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
RES = os.path.join(PROJECT, "src", "main", "resources")
ASSETS = os.path.join(RES, "assets", "deepspacedeployer")
MODID = "deepspacedeployer"

BASE = (58, 63, 74)
BASE_DARK = (30, 33, 40)
BASE_LIGHT = (86, 92, 106)
SCREEN = (14, 16, 22)

MACHINES = [
    ("factory_terminal", "screen"),
    ("factory_core", "gear"),
    ("buyer", "funnel"),
    ("market", "tag"),
]

COMPANIES = [
    ("threshold", (232, 244, 255)),
    ("whisperbelt", (180, 124, 255)),
    ("brokenedge", (255, 90, 90)),
    ("skimmer", (255, 179, 71)),
    ("bedrock", (154, 140, 120)),
    ("astralink", (84, 200, 255)),
]

TIERS = [("low", 0), ("mid", 1), ("high", 2)]


def shift(color, amount):
    return tuple(max(0, min(255, c + amount)) for c in color)


def noisy(img, amount=9, seed=1):
    rnd = random.Random(seed)
    px = img.load()
    w, h = img.size
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            d = rnd.randint(-amount, amount)
            px[x, y] = (max(0, min(255, r + d)), max(0, min(255, g + d)), max(0, min(255, b + d)), a)
    return img


def plate(draw, company_color, tier_rank):
    """底板：等级越高越"厚重"（边框更亮、加金边）"""
    body = shift(BASE, tier_rank * 6)
    draw.rectangle([0, 0, 15, 15], fill=body + (255,))
    draw.rectangle([0, 0, 15, 15], outline=BASE_DARK + (255,), width=1)
    draw.rectangle([1, 1, 14, 1], fill=BASE_LIGHT + (255,))
    draw.rectangle([1, 14, 14, 14], fill=shift(BASE_DARK, -6) + (255,))
    # 公司色条纹（左上到右下的小斜条简化成一条横线）
    draw.rectangle([2, 2, 6, 2], fill=company_color + (255,))
    # 铆钉
    for (x, y) in [(2, 3), (13, 3), (2, 12), (13, 12)]:
        draw.point((x, y), fill=BASE_DARK)
    # 等级标记：左下角 1/2/3 个小方块
    for i in range(tier_rank + 1):
        draw.point((2 + i * 2, 13), fill=(255, 214, 102, 255))
    # 高级加一圈金边
    if tier_rank == 2:
        draw.rectangle([1, 1, 14, 14], outline=(255, 214, 102, 120), width=1)


def motif(draw, kind, company_color, lit):
    accent = company_color if lit else tuple(c // 2 for c in company_color)
    if kind == "screen":
        draw.rectangle([3, 4, 12, 9], fill=SCREEN + (255,))
        if lit:
            draw.rectangle([4, 5, 11, 8], fill=accent + (255,))
            for x in range(5, 11, 2):
                draw.line([(x, 5), (x, 8)], fill=(255, 255, 255, 200))
        else:
            draw.rectangle([4, 5, 11, 8], fill=tuple(c // 3 for c in company_color) + (255,))
        draw.rectangle([6, 11, 9, 11], fill=(accent if lit else BASE_DARK) + (255,))
    elif kind == "gear":
        draw.ellipse([4, 4, 11, 10], fill=BASE_DARK + (255,))
        draw.ellipse([5, 5, 10, 9], fill=accent + (255,))
        draw.ellipse([7, 6, 8, 7], fill=SCREEN + (255,))
        for dx in (-4, 4):
            draw.rectangle([int(7.5 + dx) - 1, 6, int(7.5 + dx) + 1, 8], fill=BASE_DARK + (255,))
    elif kind == "spark":
        draw.rectangle([6, 9, 9, 12], fill=BASE_DARK + (255,))
        draw.rectangle([7, 4, 8, 9], fill=(126, 132, 146, 255))
        if lit:
            draw.rectangle([5, 2, 10, 4], fill=accent + (255,))
            draw.point((6, 1), fill=(255, 255, 210, 255))
            draw.point((9, 1), fill=(255, 255, 210, 255))
        else:
            draw.rectangle([6, 3, 9, 4], fill=(70, 74, 84, 255))
    elif kind == "funnel":
        draw.polygon([(4, 4), (11, 4), (9, 9), (6, 9)], fill=BASE_DARK + (255,))
        draw.polygon([(5, 5), (10, 5), (8, 8), (7, 8)], fill=accent + (255,))
        draw.rectangle([6, 10, 9, 11], fill=BASE_DARK + (255,))
    elif kind == "tag":
        # 寄售市场：一块价签（带小孔）+ 底下一条"价格条"
        draw.polygon([(3, 4), (11, 4), (13, 7), (11, 10), (3, 10)], fill=BASE_DARK + (255,))
        draw.polygon([(4, 5), (10, 5), (12, 7), (10, 9), (4, 9)], fill=accent + (255,))
        draw.point((5, 6), fill=SCREEN)
        if lit:
            draw.rectangle([5, 11, 11, 11], fill=(255, 214, 102, 255))
        else:
            draw.rectangle([5, 11, 11, 11], fill=shift(BASE_DARK, -4) + (255,))


def block_texture(kind, motif_name, company_color, tier_rank, lit):
    img = Image.new("RGBA", (16, 16), BASE + (255,))
    draw = ImageDraw.Draw(img)
    plate(draw, company_color, tier_rank)
    motif(draw, motif_name, company_color, lit)
    return noisy(img, 7, seed=hash((kind, motif_name, company_color, tier_rank, lit)) & 0xFFFF)


def item_texture_scrip(accent, seed):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.rectangle([1, 4, 14, 11], fill=(238, 238, 230, 255))
    draw.rectangle([1, 4, 14, 11], outline=(120, 120, 112, 255))
    draw.rectangle([2, 5, 13, 10], outline=accent + (255,))
    draw.ellipse([6, 6, 9, 9], fill=accent + (255,))
    draw.point((7, 7), fill=(255, 255, 255, 255))
    draw.line([(3, 6), (3, 9)], fill=accent + (160,))
    draw.line([(12, 6), (12, 9)], fill=accent + (160,))
    return noisy(img, 6, seed=seed)


def item_texture_card():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.rectangle([1, 3, 14, 12], fill=(28, 34, 52, 255))
    draw.rectangle([1, 3, 14, 12], outline=(12, 16, 26, 255))
    draw.rectangle([1, 5, 14, 6], fill=(210, 176, 84, 255))
    draw.rectangle([3, 8, 6, 10], fill=(232, 196, 104, 255))
    draw.point((4, 9), fill=(120, 96, 40, 255))
    draw.rectangle([9, 9, 13, 10], fill=(84, 200, 255, 255))
    draw.point((2, 11), fill=(84, 200, 255, 200))
    return noisy(img, 6, seed=99)


def item_texture_voucher():
    """蓝图凭证：一卷图纸 + 蜡封"""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.rectangle([2, 3, 13, 12], fill=(226, 220, 200, 255))
    draw.rectangle([2, 3, 13, 12], outline=(140, 132, 112, 255))
    draw.rectangle([1, 3, 2, 12], fill=(196, 188, 168, 255))
    draw.rectangle([13, 3, 14, 12], fill=(196, 188, 168, 255))
    for y in (5, 7, 9):
        draw.line([(4, y), (11, y)], fill=(84, 150, 220, 255))
    draw.rectangle([6, 6, 9, 9], outline=(84, 150, 220, 255))
    draw.ellipse([6, 11, 9, 14], fill=(200, 60, 60, 255))
    return noisy(img, 6, seed=555)


def item_texture_license(color):
    """公司授权铭牌：金属牌 + 公司色条"""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.rectangle([2, 3, 13, 12], fill=(58, 63, 74, 255))
    draw.rectangle([2, 3, 13, 12], outline=(30, 33, 40, 255))
    draw.rectangle([3, 4, 12, 6], fill=color + (255,))
    draw.rectangle([4, 8, 11, 9], fill=(200, 205, 215, 255))
    draw.rectangle([4, 10, 9, 11], fill=(150, 156, 168, 255))
    draw.point((12, 11), fill=(255, 214, 102, 255))
    return noisy(img, 5, seed=777)


def item_texture_tier_kit():
    """等级升级套件：工具箱 + 三个金星"""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.rectangle([6, 3, 9, 6], fill=(90, 62, 38, 255))
    draw.rectangle([2, 6, 13, 13], fill=(120, 84, 52, 255))
    draw.rectangle([2, 6, 13, 13], outline=(70, 48, 30, 255))
    draw.rectangle([2, 8, 13, 9], fill=(180, 140, 80, 255))
    for i in range(3):
        draw.rectangle([4 + i * 3, 10, 5 + i * 3, 11], fill=(255, 214, 102, 255))
    return noisy(img, 5, seed=778)


def logo():
    size = 128
    img = Image.new("RGBA", (size, size), (10, 12, 20, 255))
    draw = ImageDraw.Draw(img)
    rnd = random.Random(7)
    for _ in range(90):
        x, y = rnd.randrange(size), rnd.randrange(size // 2)
        v = rnd.randint(120, 255)
        draw.point((x, y), fill=(v, v, 255, 255))
    draw.ellipse([86, 8, 122, 44], fill=(46, 60, 96, 255))
    draw.ellipse([92, 14, 116, 38], fill=(70, 92, 140, 255))
    draw.rectangle([14, 70, 114, 116], fill=(40, 46, 58, 255))
    draw.rectangle([14, 70, 114, 116], outline=(84, 200, 255, 255))
    for cx in (28, 50, 72):
        draw.rectangle([cx, 46, cx + 12, 70], fill=(52, 58, 72, 255))
        draw.rectangle([cx, 46, cx + 12, 70], outline=(84, 200, 255, 180))
        draw.rectangle([cx + 3, 38, cx + 9, 46], fill=(120, 126, 140, 255))
    for i in range(4):
        x = 20 + i * 24
        draw.rectangle([x, 84, x + 14, 106], fill=(84, 200, 255, 220))
        draw.rectangle([x + 3, 88, x + 11, 102], fill=(16, 18, 24, 255))
    draw.rectangle([14, 116, 114, 120], fill=(84, 200, 255, 255))
    return img


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")


def main():
    block_tex_dir = os.path.join(ASSETS, "textures", "block")
    item_tex_dir = os.path.join(ASSETS, "textures", "item")
    os.makedirs(block_tex_dir, exist_ok=True)
    os.makedirs(item_tex_dir, exist_ok=True)

    textures = 0
    models = 0

    for machine, motif_name in MACHINES:
        variants = {}
        for company_id, color in COMPANIES:
            for tier_id, tier_rank in TIERS:
                base_name = f"{machine}_{company_id}_{tier_id}"
                for lit in (False, True):
                    suffix = "_lit" if lit else ""
                    texture_name = base_name + suffix
                    block_texture(machine, motif_name, color, tier_rank, lit).save(
                        os.path.join(block_tex_dir, texture_name + ".png"))
                    textures += 1
                    write_json(os.path.join(ASSETS, "models", "block", texture_name + ".json"), {
                        "parent": "minecraft:block/cube_all",
                        "textures": {"all": f"{MODID}:block/{texture_name}"},
                    })
                    models += 1
                variants[f"company={company_id},tier={tier_id},lit=false"] = {
                    "model": f"{MODID}:block/{base_name}"}
                variants[f"company={company_id},tier={tier_id},lit=true"] = {
                    "model": f"{MODID}:block/{base_name}_lit"}

        write_json(os.path.join(ASSETS, "blockstates", machine + ".json"), {"variants": variants})

        # 物品默认显示"阈限实验集团 / 低级"那一版
        write_json(os.path.join(ASSETS, "models", "item", machine + ".json"), {
            "parent": f"{MODID}:block/{machine}_threshold_low",
        })

    # 物品贴图
    # ⚠️ 下面这三张（银行卡 / 蓝图凭证 / 等级升级套件）现在用的是**手绘定稿图**
    #    （老板放在 桌面\正在开发的模组\模组贴图\定图 里，已装进工程并备份到 art/hand-picked/）。
    #    生成脚本**不要**再覆盖它们，否则重跑一次就把手绘图盖回程序生成的版本了。
    HAND_PICKED = {"bank_card.png", "blueprint_voucher.png", "tier_upgrade_kit.png"}
    skipped = []

    def save_item_texture(img, filename):
        """写物品贴图；手绘定稿的那几张跳过（模型 JSON 还是要写）"""
        if filename in HAND_PICKED:
            skipped.append(filename)
            return
        img.save(os.path.join(item_tex_dir, filename))

    save_item_texture(item_texture_card(), "bank_card.png")
    write_json(os.path.join(ASSETS, "models", "item", "bank_card.json"), {
        "parent": "minecraft:item/generated",
        "textures": {"layer0": f"{MODID}:item/bank_card"},
    })
    for i, (cid, color) in enumerate(COMPANIES):
        name = f"{cid}_scrip"
        save_item_texture(item_texture_scrip(color, seed=1000 + i), name + ".png")
        write_json(os.path.join(ASSETS, "models", "item", name + ".json"), {
            "parent": "minecraft:item/generated",
            "textures": {"layer0": f"{MODID}:item/{name}"},
        })

    # 蓝图凭证
    save_item_texture(item_texture_voucher(), "blueprint_voucher.png")
    write_json(os.path.join(ASSETS, "models", "item", "blueprint_voucher.json"), {
        "parent": "minecraft:item/generated",
        "textures": {"layer0": f"{MODID}:item/blueprint_voucher"},
    })

    # 公司授权铭牌（六家各一张，颜色不同）
    for cid, color in COMPANIES:
        name = f"{cid}_license"
        save_item_texture(item_texture_license(color), name + ".png")
        write_json(os.path.join(ASSETS, "models", "item", name + ".json"), {
            "parent": "minecraft:item/generated",
            "textures": {"layer0": f"{MODID}:item/{name}"},
        })

    # 等级升级套件
    save_item_texture(item_texture_tier_kit(), "tier_upgrade_kit.png")
    write_json(os.path.join(ASSETS, "models", "item", "tier_upgrade_kit.json"), {
        "parent": "minecraft:item/generated",
        "textures": {"layer0": f"{MODID}:item/tier_upgrade_kit"},
    })

    if skipped:
        print("  （跳过手绘定稿贴图，未覆盖）", "、".join(sorted(skipped)))

    logo().save(os.path.join(RES, "deepspacedeployer_logo.png"))

    print("生成完毕：")
    print("  方块贴图", textures, "张（%d 机器 × %d 公司 × %d 等级 × 2）"
          % (len(MACHINES), len(COMPANIES), len(TIERS)))
    print("  方块模型", models, "个")
    print("  物品贴图", len(COMPANIES) + 1, "张（手绘定稿的三张不覆盖）")


if __name__ == "__main__":
    main()
