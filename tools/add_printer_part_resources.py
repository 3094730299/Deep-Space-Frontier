# -*- coding: utf-8 -*-
"""
给「载具打印机」多方块结构的部件格补资源：

    python tools/add_printer_part_resources.py

需要的文件：
  · blockstates/printer_part.json   —— 所有状态组合（2 列 × 6 行 × 4 朝向 × formed）
  · models/block/printer_part.json  —— 空模型（部件格不画自己，画面由控制器统一渲染）
  · models/item/printer_part.json   —— 物品图标（指向 item 贴图，防"模型缺失"报错）
  · lang 里加名字
"""
import io
import json
import os

ROOT = r"C:\Users\30947\Documents\deepseek-harness\default-workspace\deep-space-deployer"
A = os.path.join(ROOT, "src", "main", "resources", "assets", "deepspacedeployer")

# ---------- 1) blockstates ----------
variants = {}
for col in ("c0", "c1", "c2", "c3", "c4", "c5"):
    for layer in ("l0", "l1"):
        for facing in ("north", "east", "south", "west"):
            variants["column=%s,layer=%s,facing=%s,formed=true" % (col, layer, facing)] = {
                "model": "deepspacedeployer:block/printer_part"}
            variants["column=%s,layer=%s,facing=%s,formed=false" % (col, layer, facing)] = {
                "model": "deepspacedeployer:block/printer_part"}

p = os.path.join(A, "blockstates", "printer_part.json")
io.open(p, "w", encoding="utf-8", newline="").write(
    json.dumps({"variants": variants}, ensure_ascii=False, indent=2) + "\n")
print("blockstates  → %d 个变体" % len(variants))

# ---------- 2) 空方块模型 ----------
p = os.path.join(A, "models", "block", "printer_part.json")
io.open(p, "w", encoding="utf-8", newline="").write(
    json.dumps({"textures": {}, "elements": []}, ensure_ascii=False, indent=2) + "\n")
print("block model  → 空模型（不画自己）")

# ---------- 3) 物品模型（用控制器的图标，反正不给玩家拿） ----------
p = os.path.join(A, "models", "item", "printer_part.json")
io.open(p, "w", encoding="utf-8", newline="").write(
    json.dumps({"parent": "minecraft:item/generated",
                "textures": {"layer0": "deepspacedeployer:item/factory_core"}},
               ensure_ascii=False, indent=2) + "\n")
print("item model   → 指向 item/factory_core 图标")

# ---------- 4) 语言 ----------
for name, block_name, item_name in (
        ("zh_cn.json", "载具打印机（结构件）", "载具打印机（结构件）"),
        ("en_us.json", "Vehicle Printer (Part)", "Vehicle Printer (Part)")):
    p = os.path.join(A, "lang", name)
    if not os.path.exists(p):
        continue
    d = json.load(io.open(p, encoding="utf-8"))
    d["block.deepspacedeployer.printer_part"] = block_name
    d["item.deepspacedeployer.printer_part"] = item_name
    io.open(p, "w", encoding="utf-8", newline="").write(
        json.dumps(d, ensure_ascii=False, indent=2) + "\n")
    print("lang %-12s 加了 printer_part" % name)
