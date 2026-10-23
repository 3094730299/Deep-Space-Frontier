# -*- coding: utf-8 -*-
"""
生成 36 种实体货币的物品模型、贴图和语言键。

用法：python tools/generate_currency_assets.py
数据源：Company.java 里的六家公司；面额固定 1/5/10/20/50/100。
"""
import json
import os
import re

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
RES = os.path.join(PROJECT, "src", "main", "resources")
ASSETS = os.path.join(RES, "assets", "deepspacedeployer")
ITEM_MODELS = os.path.join(ASSETS, "models", "item")
ITEM_TEXTURES = os.path.join(ASSETS, "textures", "item")
LANG_DIR = os.path.join(ASSETS, "lang")
COMPANY_JAVA = os.path.join(PROJECT, "src", "main", "java", "com",
                            "deepspacedeployer", "Company.java")

DENOMINATIONS = [1, 5, 10, 20, 50, 100]

# 3x5 像素数字
FONT = {
    "0": ("111", "101", "101", "101", "111"),
    "1": ("010", "110", "010", "010", "111"),
    "2": ("111", "001", "111", "100", "111"),
    "3": ("111", "001", "111", "001", "111"),
    "4": ("101", "101", "111", "001", "001"),
    "5": ("111", "100", "111", "001", "111"),
    "6": ("111", "100", "111", "101", "111"),
    "7": ("111", "001", "001", "010", "010"),
    "8": ("111", "101", "111", "101", "111"),
    "9": ("111", "101", "111", "001", "111"),
}


def mix(a, b, t):
    """0xRRGGBB 线性混合，t=0 全是 a，t=1 全是 b。"""
    ar, ag, ab = (a >> 16) & 0xFF, (a >> 8) & 0xFF, a & 0xFF
    br, bg, bb = (b >> 16) & 0xFF, (b >> 8) & 0xFF, b & 0xFF
    return (round(ar + (br - ar) * t) << 16
            | round(ag + (bg - ag) * t) << 8
            | round(ab + (bb - ab) * t))


def rgba(color, alpha=255):
    return ((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, alpha)


def parse_companies():
    text = open(COMPANY_JAVA, encoding="utf-8").read()
    pattern = re.compile(
        r'([A-Z_]+)\("([a-z]+)",\s*"([^"]+)",\s*"([^"]+)",\s*0x([0-9A-Fa-f]{6})\)')
    out = []
    for match in pattern.finditer(text):
        out.append({
            "enum": match.group(1),
            "id": match.group(2),
            "cn": match.group(3),
            "en": match.group(4),
            "color": int(match.group(5), 16),
        })
    if len(out) != 6:
        raise SystemExit("Company.java 解析出 %d 家公司，预期 6 家" % len(out))
    return out


def draw_digits(draw, text, color, y=6):
    width = len(text) * 4 - 1
    x = (16 - width) // 2
    for char in text:
        rows = FONT[char]
        for row, bits in enumerate(rows):
            for col, bit in enumerate(bits):
                if bit == "1":
                    draw.point((x + col, y + row), fill=color)
        x += 4


def make_texture(company, value):
    base = company["color"]
    dark = mix(base, 0x101018, 0.72)
    edge = mix(base, 0x101018, 0.50)
    main = mix(base, 0x101018, 0.22)
    band = mix(base, 0xFFFFFF, 0.38)
    center = mix(base, 0xFFFFFF, 0.74)
    ink = mix(base, 0x080810, 0.86)

    image = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)

    # 透明外圈 + 深色边框
    draw.rectangle((1, 2, 14, 13), fill=rgba(edge))
    draw.rectangle((2, 3, 13, 12), fill=rgba(main))

    # 上下装饰条
    draw.rectangle((2, 3, 13, 4), fill=rgba(band))
    draw.rectangle((2, 11, 13, 12), fill=rgba(band))

    # 中间浅色面额区
    draw.rectangle((2, 5, 13, 10), fill=rgba(center))
    draw.line((2, 5, 13, 5), fill=rgba(edge))
    draw.line((2, 10, 13, 10), fill=rgba(edge))

    # 左右公司色标 + 角点
    draw.rectangle((2, 6, 2, 9), fill=rgba(dark))
    draw.rectangle((13, 6, 13, 9), fill=rgba(dark))
    draw.point((3, 4), fill=rgba(dark))
    draw.point((12, 4), fill=rgba(dark))

    # 面额数字
    draw_digits(draw, str(value), rgba(ink))

    # 底部小色块，增加“券”的感觉
    draw.rectangle((3, 12, 5, 12), fill=rgba(dark))
    draw.rectangle((10, 12, 12, 12), fill=rgba(dark))

    return image


def write_models_and_textures(companies):
    os.makedirs(ITEM_MODELS, exist_ok=True)
    os.makedirs(ITEM_TEXTURES, exist_ok=True)
    for company in companies:
        for value in DENOMINATIONS:
            name = "%s_scrip_%d" % (company["id"], value)
            model = {
                "parent": "minecraft:item/generated",
                "textures": {"layer0": "deepspacedeployer:item/" + name},
            }
            with open(os.path.join(ITEM_MODELS, name + ".json"), "w",
                      encoding="utf-8", newline="\n") as handle:
                json.dump(model, handle, ensure_ascii=False, indent=2)
                handle.write("\n")
            make_texture(company, value).save(
                os.path.join(ITEM_TEXTURES, name + ".png"))


def update_lang(companies):
    for lang, name_key, tooltip in (
        ("zh_cn.json", "item.deepspacedeployer.%s", {
            "tooltip.deepspacedeployer.currency_company": "发行公司：%s",
            "tooltip.deepspacedeployer.currency_value": "面额：%s 元",
            "tooltip.deepspacedeployer.currency_deposit": "可在深空货币银行存取",
        }),
        ("en_us.json", "item.deepspacedeployer.%s", {
            "tooltip.deepspacedeployer.currency_company": "Issuer: %s",
            "tooltip.deepspacedeployer.currency_value": "Face value: %s",
            "tooltip.deepspacedeployer.currency_deposit": "Deposit or withdraw at the Currency Bank",
        }),
    ):
        path = os.path.join(LANG_DIR, lang)
        data = json.load(open(path, encoding="utf-8"))
        for company in companies:
            display = company["cn"] if lang.startswith("zh") else company["en"]
            suffix = "元券" if lang.startswith("zh") else "Credit Note"
            for value in DENOMINATIONS:
                key = name_key % ("%s_scrip_%d" % (company["id"], value))
                data[key] = "%s %d %s" % (display, value, suffix)
        data.update(tooltip)
        with open(path, "w", encoding="utf-8", newline="\n") as handle:
            json.dump(data, handle, ensure_ascii=False, indent=2)
            handle.write("\n")


def main():
    companies = parse_companies()
    write_models_and_textures(companies)
    update_lang(companies)
    print("已生成 %d 家公司 × %d 种面额 = %d 种实体货币"
          % (len(companies), len(DENOMINATIONS), len(companies) * len(DENOMINATIONS)))


if __name__ == "__main__":
    main()
