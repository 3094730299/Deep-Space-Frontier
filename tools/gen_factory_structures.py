# -*- coding: utf-8 -*-
"""
工厂建筑生成器：为「6 家公司 × 3 个等级」各造一座工厂，并写好世界生成的数据包。

    python tools/gen_factory_structures.py

输出（全在工程里，构建时自动打进 jar）：
  data/deepspacedeployer/structure/factory_<公司>_<等级>.nbt        18 个建筑
  data/deepspacedeployer/worldgen/template_pool/factory_*.json      18 个拼图池
  data/deepspacedeployer/worldgen/structure/factory_*.json          18 个结构定义
  data/deepspacedeployer/worldgen/structure_set/factory_<等级>.json 3 个生成规则（稀有度）
  data/deepspacedeployer/tags/worldgen/biome/has_factory_<等级>.json 3 个群系标签

建筑规则：低级 9×5×9（只有商店+收购站）、中级 13×7×13（多了工厂核心）、
高级 17×9×17（再加焊接台）。机器方块在蓝图里就写好了公司/等级属性，落地即正确。
"""
import gzip
import json
import os
import struct

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
DATA = os.path.join(PROJECT, "src", "main", "resources", "data", "deepspacedeployer")

TAG_BYTE, TAG_INT, TAG_STRING, TAG_LIST, TAG_COMPOUND = 1, 3, 8, 9, 10
MODID = "deepspacedeployer"

COMPANIES = [
    # (代号, 公司主题色方块)
    ("threshold", "minecraft:white_concrete"),
    ("whisperbelt", "minecraft:purple_concrete"),
    ("brokenedge", "minecraft:red_concrete"),
    ("skimmer", "minecraft:orange_concrete"),
    ("bedrock", "minecraft:brown_concrete"),
    ("astralink", "minecraft:light_blue_concrete"),
]

TIERS = {
    # 代号: (地基边长, 高度, 墙, 地板, 屋顶, 窗, 装饰)
    "low": (9, 5, "minecraft:stone_bricks", "minecraft:oak_planks",
            "minecraft:smooth_stone", "minecraft:glass_pane", "minecraft:iron_block"),
    "mid": (13, 7, "minecraft:deepslate_bricks", "minecraft:spruce_planks",
            "minecraft:deepslate_tiles", "minecraft:glass_pane", "minecraft:iron_block"),
    "high": (17, 9, "minecraft:polished_blackstone_bricks", "minecraft:dark_oak_planks",
             "minecraft:polished_deepslate", "minecraft:tinted_glass", "minecraft:copper_block"),
}

BIOMES = {
    "low": [
        "minecraft:plains", "minecraft:sunflower_plains", "minecraft:forest",
        "minecraft:flower_forest", "minecraft:birch_forest", "minecraft:old_growth_birch_forest",
        "minecraft:meadow", "minecraft:savanna", "minecraft:savanna_plateau",
        "minecraft:swamp", "minecraft:cherry_grove",
        "biomesoplenty:lavender_field", "biomesoplenty:forested_field", "biomesoplenty:field",
        "biomesoplenty:pasture", "biomesoplenty:prairie", "biomesoplenty:woodland",
        "biomesoplenty:old_growth_woodland", "biomesoplenty:scrubland",
        "biomesoplenty:mediterranean_forest", "biomesoplenty:seasonal_forest",
    ],
    "mid": [
        "minecraft:desert", "minecraft:badlands", "minecraft:eroded_badlands",
        "minecraft:wooded_badlands", "minecraft:taiga", "minecraft:old_growth_pine_taiga",
        "minecraft:old_growth_spruce_taiga", "minecraft:snowy_plains", "minecraft:snowy_taiga",
        "minecraft:windswept_hills", "minecraft:windswept_forest",
        "biomesoplenty:coniferous_forest", "biomesoplenty:redwood_forest", "biomesoplenty:highland",
        "biomesoplenty:moor", "biomesoplenty:mystic_grove", "biomesoplenty:dead_forest",
        "biomesoplenty:old_growth_dead_forest", "biomesoplenty:snowblossom_grove",
        "biomesoplenty:snowy_coniferous_forest",
    ],
    "high": [
        "minecraft:jagged_peaks", "minecraft:frozen_peaks", "minecraft:stony_peaks",
        "minecraft:stony_shore", "minecraft:windswept_gravelly_hills", "minecraft:snowy_slopes",
        "minecraft:grove", "minecraft:ice_spikes",
        "biomesoplenty:auroral_garden", "biomesoplenty:rocky_rainforest",
        "biomesoplenty:rainforest",
    ],
}

# 稀有度：spacing 越大越稀有
PLACEMENT = {
    "low": {"spacing": 40, "separation": 14, "salt": 147001},
    "mid": {"spacing": 60, "separation": 22, "salt": 147002},
    "high": {"spacing": 90, "separation": 34, "salt": 147003},
}

MACHINE_SLOTS = {
    # 等级: [(机器 id, x 偏移说明)]  —— x 运行时按边长算
    "low": ["factory_terminal", "buyer"],
    "mid": ["factory_terminal", "buyer", "factory_core"],
"high": ["factory_terminal", "buyer", "factory_core"],
}


# ---------------------------------------------------------------- NBT 写入
def _str(text):
    raw = text.encode("utf-8")
    return struct.pack(">H", len(raw)) + raw


def tag(tag_id, name, payload):
    return bytes([tag_id]) + _str(name) + payload


def t_int(v):
    return struct.pack(">i", v)


def t_byte(v):
    return struct.pack(">b", v)


def t_string(v):
    return _str(v)


def t_list_int(values):
    return bytes([TAG_INT]) + t_int(len(values)) + b"".join(t_int(v) for v in values)


def t_list_compound(items):
    return bytes([TAG_COMPOUND]) + t_int(len(items)) + b"".join(items)


def compound_body(entries):
    return b"".join(tag(tid, name, payload) for (tid, name, payload) in entries) + b"\x00"


def block_entry(x, y, z, state, nbt=None):
    parts = [
        (TAG_LIST, "pos", t_list_int([x, y, z])),
        (TAG_INT, "state", t_int(state)),
    ]
    if nbt is not None:
        parts.append((TAG_COMPOUND, "nbt", nbt))
    return compound_body(parts)


def palette_entry(name, properties=None):
    parts = [(TAG_STRING, "Name", t_string(name))]
    if properties:
        props = compound_body([(TAG_STRING, k, t_string(v)) for k, v in properties.items()])
        parts.append((TAG_COMPOUND, "Properties", props))
    return compound_body(parts)


def build_structure(size, palette_entries, blocks):
    body = compound_body([
        (TAG_INT, "DataVersion", t_int(3955)),
        (TAG_LIST, "size", t_list_int(list(size))),
        (TAG_LIST, "palette", t_list_compound(palette_entries)),
        (TAG_LIST, "blocks", t_list_compound(blocks)),
        (TAG_LIST, "entities", t_list_compound([])),
    ])
    return bytes([TAG_COMPOUND]) + struct.pack(">H", 0) + body


# ---------------------------------------------------------------- 建筑
def build_factory(company, tier):
    size_x, size_y, wall, floor_b, roof, window, decor = TIERS[tier]
    size_z = size_x
    company_block = dict(COMPANIES)[company]
    machines = MACHINE_SLOTS[tier]

    palette = ["minecraft:air", wall, floor_b, roof, window, company_block, decor, "minecraft:chest"]
    for machine in machines:
        palette.append(("machine", machine))

    def index_of(entry):
        return palette.index(entry)

    blocks = []

    def put(x, y, z, entry, nbt=None):
        blocks.append(block_entry(x, y, z, index_of(entry), nbt))

    # 地板
    for x in range(size_x):
        for z in range(size_z):
            put(x, 0, z, floor_b)

    # 内部空气（连墙内也清一下，避免树插进来）
    for y in range(1, size_y - 1):
        for x in range(1, size_x - 1):
            for z in range(1, size_z - 1):
                put(x, y, z, "minecraft:air")

    # 墙 + 窗 + 门
    door_x = size_x // 2
    for y in range(1, size_y - 1):
        for x in range(size_x):
            for z in (0, size_z - 1):
                if z == 0 and x == door_x and y <= 2:
                    continue  # 门口
                if y == 2 and (x % 3 == 1):
                    put(x, y, z, window)
                else:
                    put(x, y, z, wall)
        for z in range(1, size_z - 1):
            for x in (0, size_x - 1):
                if y == 2 and (z % 3 == 1):
                    put(x, y, z, window)
                else:
                    put(x, y, z, wall)

    # 屋顶 + 公司色边条
    for x in range(size_x):
        for z in range(size_z):
            edge = x in (0, size_x - 1) or z in (0, size_z - 1)
            put(x, size_y - 1, z, company_block if edge else roof)

    # 公司色装饰柱 + 四角装饰块
    for y in range(1, size_y - 1):
        put(1, y, size_z - 2, company_block)
        put(size_x - 2, y, size_z - 2, company_block)
    for (dx, dz) in [(1, 1), (size_x - 2, 1), (1, size_z - 2), (size_x - 2, size_z - 2)]:
        put(dx, 1, dz, decor)

    # 机器（贴后墙一排）
    z_line = size_z - 3
    positions = [2, 4, size_x - 5, size_x - 3]
    for i, machine in enumerate(machines):
        x = max(2, min(size_x - 3, positions[i]))
        blocks.append(block_entry(x, 1, z_line, index_of(("machine", machine))))

    # 箱子：里面放一点本公司票据
    chest = compound_body([
        (TAG_STRING, "id", t_string("minecraft:chest")),
        (TAG_LIST, "Items", t_list_compound([
            compound_body([
                (TAG_BYTE, "Slot", t_byte(0)),
                (TAG_STRING, "id", t_string("deepspacedeployer:%s_scrip" % company)),
                (TAG_INT, "count", t_int(8 + TIERS[tier][0])),
            ]),
        ])),
    ])
    put(size_x - 3, 1, 2, "minecraft:chest", chest)

    # 调色板：把 ("machine", id) 展开成真正带属性的方块
    resolved = []
    for entry in palette:
        if isinstance(entry, tuple) and entry[0] == "machine":
            resolved.append(palette_entry("deepspacedeployer:" + entry[1],
                                          {"company": company, "tier": tier, "lit": "false"}))
        elif entry == "minecraft:air":
            resolved.append(palette_entry("minecraft:air"))
        else:
            resolved.append(palette_entry(entry))

    return (size_x, size_y, size_z), resolved, blocks


# ---------------------------------------------------------------- 数据包
def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")


def main():
    structure_dir = os.path.join(DATA, "structure")
    pool_dir = os.path.join(DATA, "worldgen", "template_pool")
    worldgen_dir = os.path.join(DATA, "worldgen", "structure")
    set_dir = os.path.join(DATA, "worldgen", "structure_set")
    tag_dir = os.path.join(DATA, "tags", "worldgen", "biome")
    os.makedirs(structure_dir, exist_ok=True)

    by_tier = {tier: [] for tier in TIERS}

    for company, _ in COMPANIES:
        for tier in TIERS:
            name = "factory_%s_%s" % (company, tier)
            size, palette, blocks = build_factory(company, tier)
            with gzip.open(os.path.join(structure_dir, name + ".nbt"), "wb") as f:
                f.write(build_structure(size, palette, blocks))
            by_tier[tier].append(name)

            write_json(os.path.join(pool_dir, name + ".json"), {
                "fallback": "minecraft:empty",
                "elements": [{
                    "weight": 1,
                    "element": {
                        "element_type": "minecraft:single_pool_element",
                        "location": "%s:%s" % (MODID, name),
                        "processors": "minecraft:empty",
                        "projection": "rigid",
                    },
                }],
            })

            write_json(os.path.join(worldgen_dir, name + ".json"), {
                "type": "minecraft:jigsaw",
                "biomes": "#%s:has_factory_%s" % (MODID, tier),
                "step": "surface_structures",
                "terrain_adaptation": "beard_thin",
                "spawn_overrides": {},
                "start_pool": "%s:%s" % (MODID, name),
                "size": 1,
                "start_height": {"absolute": 0},
                "project_start_to_heightmap": "WORLD_SURFACE_WG",
                "max_distance_from_center": 96,
                "use_expansion_hack": False,
            })

    for tier, names in by_tier.items():
        write_json(os.path.join(set_dir, "factory_%s.json" % tier), {
            "structures": [
                {"structure": "%s:%s" % (MODID, name), "weight": 1} for name in names
            ],
            "placement": dict({"type": "minecraft:random_spread"}, **PLACEMENT[tier]),
        })
        write_json(os.path.join(tag_dir, "has_factory_%s.json" % tier), {
            "replace": False,
            "values": BIOMES[tier],
        })

    total = sum(len(v) for v in by_tier.values())
    print("生成完毕：")
    for tier, names in by_tier.items():
        entry = TIERS[tier]
        print("  %-4s 工厂 %d 座（建筑 %d×%d×%d，spacing=%d）"
              % (tier, len(names), entry[0], entry[1], entry[0], PLACEMENT[tier]["spacing"]))
    print("  共 %d 座建筑 + 3 个生成规则 + 3 个群系标签" % total)


if __name__ == "__main__":
    main()
