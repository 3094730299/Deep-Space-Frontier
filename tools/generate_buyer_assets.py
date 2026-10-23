# -*- coding: utf-8 -*-
"""生成收购投送机 3 个中立等级的资源（模型 / 贴图 / 方块状态 / 物品模型 / 战利品）。"""
import json
import os
from pathlib import Path

from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parent
ASSETS = PROJECT / "src/main/resources/assets/deepspacedeployer"
DATA = PROJECT / "src/main/resources/data/deepspacedeployer"
BLOCK_MODELS = ASSETS / "models/block"
ITEM_MODELS = ASSETS / "models/item"
BLOCK_TEXTURES = ASSETS / "textures/block"
BLOCKSTATES = ASSETS / "blockstates"
LOOT = DATA / "loot_table/blocks"

COMPANIES = ["threshold", "whisperbelt", "brokenedge", "skimmer", "bedrock", "astralink"]
TIERS = ["low", "mid", "high"]
FACINGS = ["north", "east", "south", "west"]
ROTATION = {"north": None, "east": 90, "south": 180, "west": 270}

FONT = {
    "1": ("010", "110", "010", "010", "111"),
    "2": ("111", "001", "111", "100", "111"),
    "3": ("111", "001", "111", "001", "111"),
}


def mix(a, b, t):
    ar, ag, ab = (a >> 16) & 0xFF, (a >> 8) & 0xFF, a & 0xFF
    br, bg, bb = (b >> 16) & 0xFF, (b >> 8) & 0xFF, b & 0xFF
    return (round(ar + (br - ar) * t) << 16
            | round(ag + (bg - ag) * t) << 8
            | round(ab + (bb - ab) * t))


def rgba(color, alpha=255):
    return ((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, alpha)


def draw_tier(draw, text, color, x, y):
    for char in text:
        for row, bits in enumerate(FONT[char]):
            for col, bit in enumerate(bits):
                if bit == "1":
                    draw.point((x + col, y + row), fill=color)
        x += 4


def make_block_texture(tier, lit):
    # 中立工业灰 + 等级色；lit 时橙色更亮
    base = {"low": 0x5A6672, "mid": 0x6B6258, "high": 0x7A5A42}[tier]
    accent = {"low": 0x8FA4B5, "mid": 0xC0A06A, "high": 0xE08A45}[tier]
    if lit:
        base = mix(base, 0xFFB45A, 0.28)
        accent = mix(accent, 0xFFD080, 0.45)
    dark = mix(base, 0x151A20, 0.65)
    light = mix(base, 0xFFFFFF, 0.35)

    image = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.rectangle((0, 0, 15, 15), fill=rgba(dark))
    draw.rectangle((1, 1, 14, 14), fill=rgba(base))
    draw.rectangle((2, 2, 13, 5), fill=rgba(light))
    draw.rectangle((2, 6, 13, 7), fill=rgba(accent))
    draw.rectangle((2, 8, 13, 13), fill=rgba(mix(base, 0x000000, 0.18)))
    # 中央投送口 + 箭头
    draw.rectangle((5, 8, 10, 12), fill=rgba(dark))
    draw.polygon([(7, 9), (8, 9), (8, 11), (9, 11), (7, 13), (5, 11), (6, 11), (6, 9)],
                 fill=rgba(accent if lit else light))
    # 等级角标
    draw_tier(draw, {"low": "1", "mid": "2", "high": "3"}[tier], rgba(accent), 11, 2)
    # 角落铆钉
    for x, y in ((2, 13), (13, 13)):
        draw.point((x, y), fill=rgba(accent))
    return image


def make_geo_texture(tier):
    """从旧阈值贴图去色，保留 UV 布局，做成中立金属色。"""
    source = Image.open(BLOCK_TEXTURES / "buyer_threshold.png").convert("RGBA")
    palettes = {
        "low": (0x2E3842, 0x8FA0AE),
        "mid": (0x3A352E, 0xB5A184),
        "high": (0x3B2C20, 0xD69A62),
    }
    dark, light = palettes[tier]
    out = Image.new("RGBA", source.size, (0, 0, 0, 0))
    src = source.load()
    dst = out.load()
    for y in range(source.height):
        for x in range(source.width):
            r, g, b, a = src[x, y]
            if a == 0:
                continue
            lum = (r * 0.299 + g * 0.587 + b * 0.114) / 255.0
            color = mix(dark, light, max(0.0, min(1.0, lum)))
            dst[x, y] = rgba(color, a)
    return out


def write_models():
    for tier in TIERS:
        for lit in (False, True):
            name = "buyer_%s%s" % (tier, "_lit" if lit else "")
            model = {
                "parent": "minecraft:block/cube_all",
                "textures": {"all": "deepspacedeployer:block/" + name},
            }
            (BLOCK_MODELS / (name + ".json")).write_text(
                json.dumps(model, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        item = {
            "parent": "deepspacedeployer:block/buyer_" + tier,
        }
        (ITEM_MODELS / ("buyer_" + tier + ".json")).write_text(
            json.dumps(item, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_blockstate():
    variants = {}
    for company in COMPANIES:
        for tier in TIERS:
            for facing in FACINGS:
                for lit in (False, True):
                    key = "facing=%s,company=%s,tier=%s,lit=%s" % (
                        facing, company, tier, "true" if lit else "false")
                    variant = {
                        "model": "deepspacedeployer:block/buyer_%s%s" % (
                            tier, "_lit" if lit else ""),
                    }
                    if ROTATION[facing] is not None:
                        variant["y"] = ROTATION[facing]
                    variants[key] = variant
    (BLOCKSTATES / "buyer.json").write_text(
        json.dumps({"variants": variants}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8")


def write_loot():
    entries = []
    for tier in TIERS:
        entries.append({
            "type": "minecraft:item",
            "name": "deepspacedeployer:buyer_" + tier,
            "conditions": [{
                "condition": "minecraft:block_state_property",
                "block": "deepspacedeployer:buyer",
                "properties": {"tier": tier},
            }],
        })
    loot = {
        "type": "minecraft:block",
        "pools": [{
            "bonus_rolls": 0.0,
            "conditions": [{"condition": "minecraft:survives_explosion"}],
            "entries": entries,
            "rolls": 1.0,
        }],
        "random_sequence": "deepspacedeployer:blocks/buyer",
    }
    (LOOT / "buyer.json").write_text(
        json.dumps(loot, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main():
    for tier in TIERS:
        make_block_texture(tier, False).save(BLOCK_TEXTURES / ("buyer_%s.png" % tier))
        make_block_texture(tier, True).save(BLOCK_TEXTURES / ("buyer_%s_lit.png" % tier))
        make_geo_texture(tier).save(BLOCK_TEXTURES / ("buyer_neutral_%s.png" % tier))
    write_models()
    write_blockstate()
    write_loot()
    print("收购投送机资源已生成：3 个等级 / 6 张方块贴图 / 3 张 GeckoLib 贴图 / 144 个方块状态")


if __name__ == "__main__":
    main()
