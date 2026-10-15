# -*- coding: utf-8 -*-
"""
价目表生成器：把默认价目表写成数据包 JSON，方便你在文件里直接改。
    python tools/gen_shop_json.py

输出：src/main/resources/data/deepspacedeployer/shop/<公司代号>.json
改完价格后在游戏里执行 /reload 即可生效（多人服务器不用重启）。
"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
OUT_DIR = os.path.join(PROJECT, "src", "main", "resources", "data", "deepspacedeployer", "shop")

COMPANIES = ["threshold", "whisperbelt", "brokenedge", "skimmer", "bedrock", "astralink"]

# 通用收购表：{物品 id, 一次收几个, 总价}
COMMON_SELL = [
    ("minecraft:iron_ingot", 1, 8),
    ("minecraft:copper_ingot", 1, 5),
    ("minecraft:gold_ingot", 1, 12),
    ("minecraft:redstone", 1, 3),
    ("minecraft:quartz", 1, 6),
    ("minecraft:diamond", 1, 45),
    ("minecraft:netherite_scrap", 1, 200),
    ("create:andesite_alloy", 1, 10),
    ("create:zinc_ingot", 1, 9),
    ("create:brass_ingot", 1, 20),
    ("create:iron_sheet", 1, 7),
    ("create:copper_sheet", 1, 6),
    ("create:brass_sheet", 1, 16),
    ("create:electron_tube", 1, 28),
    ("create:precision_mechanism", 1, 95),
    ("create:sturdy_sheet", 1, 65),
    ("create:powdered_obsidian", 1, 32),
    ("create:rose_quartz", 1, 22),
    ("create:propeller", 1, 48),
    ("create:whisk", 1, 48),
    ("aeronautics:end_stone_powder", 1, 14),
    ("aeronautics:levitite", 1, 160),
    ("simulated:altitude_sensor", 1, 45),
    ("simulated:laser_sensor", 1, 45),
]

# 各公司卖给玩家的商品：(物品 id, 数量, 价格, 需要等级, 需要声望[, 蓝图名])
# 带第 6 项的 = 蓝图凭证：买下来对着工厂核心右键即可选中该结构
BUY = {
    "threshold": [
        ("aeronautics:aviators_goggles", 1, 260, "low", 0),
        ("simulated:altitude_sensor", 1, 120, "low", 0),
        ("simulated:gimbal_sensor", 1, 180, "mid", 0),
        ("simulated:laser_sensor", 1, 200, "mid", 5),
        ("simulated:laser_pointer", 1, 200, "mid", 5),
        ("simulated:analog_transmission", 1, 320, "mid", 10),
        ("create:precision_mechanism", 4, 420, "mid", 0),
        ("deepspacedeployer:blueprint_voucher", 1, 900, "low", 0, "test_platform"),
        ("deepspacedeployer:blueprint_voucher", 1, 3200, "mid", 10, "small_airship"),
        ("deepspacedeployer:threshold_license", 1, 2500, "mid", 10),
    ],
    "whisperbelt": [
        ("aeronautics:white_envelope", 2, 140, "low", 0),
        ("aeronautics:black_envelope", 2, 140, "low", 0),
        ("aeronautics:smart_propeller", 1, 380, "mid", 5),
        ("simulated:white_symmetric_sail", 2, 300, "mid", 5),
        ("simulated:black_portable_engine", 1, 400, "mid", 5),
        ("simulated:directional_linked_receiver", 1, 520, "high", 20),
        ("deepspacedeployer:whisperbelt_license", 1, 2500, "mid", 10),
    ],
    "brokenedge": [
        ("create:sturdy_sheet", 4, 320, "low", 0),
        ("aeronautics:mounted_potato_cannon", 1, 300, "low", 0),
        ("create:brass_sheet", 8, 200, "low", 0),
        ("simulated:iron_handle", 4, 160, "mid", 0),
        ("simulated:docking_connector", 1, 450, "mid", 10),
        ("aeronautics:adjustable_burner", 1, 700, "high", 25),
        ("deepspacedeployer:brokenedge_license", 1, 2500, "mid", 10),
    ],
    "skimmer": [
        ("aeronautics:wooden_propeller", 1, 90, "low", 0),
        ("aeronautics:andesite_propeller", 1, 190, "low", 0),
        ("aeronautics:propeller_bearing", 1, 260, "low", 0),
        ("simulated:orange_portable_engine", 1, 380, "mid", 5),
        ("simulated:yellow_portable_engine", 1, 380, "mid", 5),
        ("aeronautics:gyroscopic_propeller_bearing", 1, 620, "mid", 15),
        ("aeronautics:steam_vent", 1, 550, "high", 20),
        ("deepspacedeployer:blueprint_voucher", 1, 1100, "low", 0, "test_platform"),
        ("deepspacedeployer:blueprint_voucher", 1, 3600, "mid", 15, "small_airship"),
        ("deepspacedeployer:skimmer_license", 1, 2500, "mid", 10),
    ],
    "bedrock": [
        ("create:iron_sheet", 8, 90, "low", 0),
        ("create:copper_sheet", 8, 80, "low", 0),
        ("simulated:gray_handle", 4, 140, "low", 0),
        ("simulated:gray_nameplate", 4, 120, "low", 0),
        ("simulated:analog_transmission", 1, 340, "mid", 5),
        ("simulated:directional_gearshift", 1, 380, "mid", 10),
        ("simulated:auger_shaft", 1, 420, "mid", 10),
        ("simulated:auger_cog", 1, 420, "high", 20),
        ("deepspacedeployer:bedrock_license", 1, 2500, "mid", 10),
        ("deepspacedeployer:tier_upgrade_kit", 1, 3000, "low", 5),
    ],
    "astralink": [
        ("create:electron_tube", 4, 150, "low", 0),
        ("aeronautics:levitite", 1, 420, "mid", 0),
        ("aeronautics:levitite_blend_bucket", 1, 300, "mid", 5),
        ("simulated:altitude_sensor", 1, 130, "low", 0),
        ("simulated:directional_linked_receiver", 1, 560, "high", 20),
        ("simulated:creative_physics_staff", 1, 5000, "high", 60),
        ("deepspacedeployer:astralink_license", 1, 2500, "mid", 10),
        ("deepspacedeployer:tier_upgrade_kit", 1, 3200, "mid", 10),
    ],
}


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    for company in COMPANIES:
        buy = []
        for row in BUY[company]:
            entry = {"item": row[0], "count": row[1], "price": row[2], "tier": row[3], "reputation": row[4]}
            if len(row) > 5:
                entry["blueprint"] = row[5]
            buy.append(entry)
        data = {
            "company": company,
            "_说明": ("价格单位是该公司票据；tier=low/mid/high 是需要的工厂等级；reputation 是需要的声望；"
                      "blueprint 只用于蓝图凭证。改完用 /reload 生效。"),
            "buy": buy,
            "sell": [
                {"item": item, "count": count, "price": price}
                for (item, count, price) in COMMON_SELL
            ],
        }
        path = os.path.join(OUT_DIR, company + ".json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, ensure_ascii=False)
            f.write("\n")
        print("已写出:", path)


if __name__ == "__main__":
    main()
