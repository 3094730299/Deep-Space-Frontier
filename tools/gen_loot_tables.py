# -*- coding: utf-8 -*-
"""
掉落表生成器：四种机器方块各一份 loot_table。
    python tools/gen_loot_tables.py

为什么必须要有：原版里**没有掉落表的方块被挖掉什么都不掉**。
我们这个模组的机器里可能装着银行卡、材料、以及"存在方块里的钱"，
所以掉落表要：
  1) 掉出方块本身（用 copy_state 保住"公司/等级"两个属性 —— 挖起来再放下去还是原来那台）
  2) 让爆炸炸掉时也照常掉落（survives_explosion）
  3) 里面的东西由代码在 playerWillDestroy 里吐出来（见 MachineBlockEntity#dropEverything）

输出：src/main/resources/data/deepspacedeployer/loot_table/blocks/<机器>.json
"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
OUT_DIR = os.path.join(PROJECT, "src", "main", "resources", "data",
                       "deepspacedeployer", "loot_table", "blocks")

MACHINES = ["factory_terminal", "factory_core", "welder", "buyer"]


def loot_table(name):
    return {
        "type": "minecraft:block",
        "pools": [
            {
                "bonus_rolls": 0.0,
                "conditions": [
                    {"condition": "minecraft:survives_explosion"}
                ],
                "entries": [
                    {
                        "type": "minecraft:item",
                        "name": "deepspacedeployer:%s" % name,
                        "functions": [
                            {
                                "function": "minecraft:copy_state",
                                "source": "block_state",
                                "block": "deepspacedeployer:%s" % name,
                                "properties": ["company", "tier"]
                            }
                        ]
                    }
                ],
                "rolls": 1.0
            }
        ],
        "random_sequence": "deepspacedeployer:blocks/%s" % name
    }


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    for name in MACHINES:
        path = os.path.join(OUT_DIR, name + ".json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(loot_table(name), f, indent=2)
            f.write("\n")
        print("已写出:", path)


if __name__ == "__main__":
    main()
