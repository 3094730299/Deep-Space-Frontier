# -*- coding: utf-8 -*-
"""
示例蓝图生成器：直接写原版结构 NBT（机械动力蓝图就是同一个格式）。
    python tools/gen_example_blueprints.py

会生成到：
  examples/blueprints/*.nbt                     ← 工程里的备份
  <游戏目录>/config/deepspacedeployer/blueprints/ ← 直接能用的位置（自动复制）

进游戏后在工厂核心界面点「蓝图」页 → 选一份 → 投材料 → 一键组装。
"""
import gzip
import os
import shutil
import struct

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
EXAMPLES = os.path.join(PROJECT, "examples", "blueprints")
GAME_BLUEPRINTS = r"C:\Users\30947\Desktop\.minecraft\versions\孢子危机\config\deepspacedeployer\blueprints"

# ---------------------------------------------------------------- NBT 写入
TAG_BYTE, TAG_INT, TAG_STRING, TAG_LIST, TAG_COMPOUND = 1, 3, 8, 9, 10


def _str(text):
    raw = text.encode("utf-8")
    return struct.pack(">H", len(raw)) + raw


def tag(tag_id, name, payload):
    return bytes([tag_id]) + _str(name) + payload


def t_int(value):
    return struct.pack(">i", value)


def t_string(value):
    return _str(value)


def t_list_int(values):
    return bytes([TAG_INT]) + t_int(len(values)) + b"".join(t_int(v) for v in values)


def t_list_compound(items):
    """items 是已经拼好的 compound 负载（不带名字的那种）"""
    return bytes([TAG_COMPOUND]) + t_int(len(items)) + b"".join(items)


def compound_body(entries):
    """entries: [(tag_id, name, payload)]，返回 compound 的负载部分"""
    out = b"".join(tag(tid, name, payload) for (tid, name, payload) in entries)
    return out + b"\x00"


def empty_list_compound():
    return t_list_compound([])


def build_structure(size, palette, blocks):
    """
    size: (x, y, z)
    palette: [块 id 字符串]（索引 0 必须是 minecraft:air）
    blocks: [(x, y, z, palette_index)]
    """
    palette_items = []
    for name in palette:
        palette_items.append(compound_body([(TAG_STRING, "Name", t_string(name))]))

    block_items = []
    for (x, y, z, state) in blocks:
        block_items.append(compound_body([
            (TAG_LIST, "pos", t_list_int([x, y, z])),
            (TAG_INT, "state", t_int(state)),
        ]))

    body = compound_body([
        (TAG_INT, "DataVersion", t_int(3955)),
        (TAG_LIST, "size", t_list_int(list(size))),
        (TAG_LIST, "palette", t_list_compound(palette_items)),
        (TAG_LIST, "blocks", t_list_compound(block_items)),
        (TAG_LIST, "entities", empty_list_compound()),
    ])
    # 根 compound 需要"空名字"
    return bytes([TAG_COMPOUND]) + struct.pack(">H", 0) + body


# ---------------------------------------------------------------- 蓝图内容
def test_platform():
    """5×3×5 的小平台：安山机壳地板 + 四角黄铜机壳 + 中心传动轴和螺旋桨"""
    palette = ["minecraft:air", "create:andesite_casing", "create:brass_casing",
               "create:shaft", "create:propeller"]
    blocks = []
    for x in range(5):
        for z in range(5):
            blocks.append((x, 0, z, 1))
    for (x, z) in [(0, 0), (4, 0), (0, 4), (4, 4)]:
        blocks.append((x, 1, z, 2))
    blocks.append((2, 1, 2, 3))
    blocks.append((2, 2, 2, 4))
    return (5, 3, 5), palette, blocks


def small_airship():
    """9×6×7 的小飞船骨架：黄铜船底 + 安山机壳甲板 + 两根螺旋桨柱"""
    palette = ["minecraft:air", "create:brass_casing", "create:andesite_casing",
               "create:shaft", "create:propeller", "create:iron_sheet"]
    blocks = []
    # 船底（椭圆近似）
    for x in range(9):
        for z in range(7):
            dx = (x - 4) / 4.0
            dz = (z - 3) / 3.0
            if dx * dx + dz * dz <= 1.05:
                blocks.append((x, 0, z, 1))
    # 甲板
    for x in range(1, 8):
        for z in range(1, 6):
            blocks.append((x, 1, z, 2))
    # 两侧螺旋桨柱
    for y in range(2, 5):
        blocks.append((1, y, 3, 3))
        blocks.append((7, y, 3, 3))
    blocks.append((1, 5, 3, 4))
    blocks.append((7, 5, 3, 4))
    # 船头装饰
    blocks.append((4, 2, 0, 5))
    blocks.append((4, 2, 6, 5))
    return (9, 6, 7), palette, blocks


def main():
    os.makedirs(EXAMPLES, exist_ok=True)
    targets = [
        ("test_platform", test_platform()),
        ("small_airship", small_airship()),
    ]
    for name, (size, palette, blocks) in targets:
        data = build_structure(size, palette, blocks)
        path = os.path.join(EXAMPLES, name + ".nbt")
        with gzip.open(path, "wb") as f:
            f.write(data)
        print("已生成 %s：%s×%s×%s，%d 个方块" % (name, size[0], size[1], size[2], len(blocks)))

    game_root = r"C:\Users\30947\Desktop\.minecraft\versions\孢子危机"
    if os.path.isdir(game_root):
        os.makedirs(GAME_BLUEPRINTS, exist_ok=True)
        for name, _ in targets:
            shutil.copy2(os.path.join(EXAMPLES, name + ".nbt"),
                         os.path.join(GAME_BLUEPRINTS, name + ".nbt"))
        print("已复制到游戏目录：", GAME_BLUEPRINTS)
    else:
        print("（没找到游戏目录，跳过复制）")


if __name__ == "__main__":
    main()
