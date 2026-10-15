# -*- coding: utf-8 -*-
"""
给机器方块加「放置朝向」。

    python tools/add_machine_facing.py

做法（和通用终端完全一致）：
  · 方块加 HORIZONTAL_FACING 属性 —— GeckoLib 的 GeoBlockRenderer
    会自动读这个属性旋转模型（它支持的属性：FACING / HORIZONTAL_FACING /
    VERTICAL_DIRECTION / FACING_HOPPER），所以渲染侧不用写任何旋转代码。
  · 放置时按玩家朝向设置（和通用终端一样：正面朝玩家）。
  · 四个机器的 blockstate 补上 facing=variant（y 旋转），否则会报模型缺失。
"""
import io
import json
import os
import re

ROOT = r"C:\Users\30947\Documents\deepseek-harness\default-workspace\deep-space-deployer"
J = os.path.join(ROOT, "src", "main", "java", "com", "deepspacedeployer")
A = os.path.join(ROOT, "src", "main", "resources", "assets", "deepspacedeployer")

# ---------- 1) MachineBlock 加属性 ----------
P = os.path.join(J, "block", "MachineBlock.java")
s = io.open(P, encoding="utf-8").read()

if "HORIZONTAL_FACING" not in s:
    s = s.replace(
        "    /** 工厂等级 */\n"
        "    public static final EnumProperty<Tier> TIER = EnumProperty.create(\"tier\", Tier.class);",
        "    /** 工厂等级 */\n"
        "    public static final EnumProperty<Tier> TIER = EnumProperty.create(\"tier\", Tier.class);\n"
        "    /**\n"
        "     * 放置朝向。\n"
        "     *\n"
        "     * <p>用 {@code HORIZONTAL_FACING}（而不是自造的属性）是有意的 ——\n"
        "     * GeckoLib 的 {@code GeoBlockRenderer} 会自动读这几个属性来旋转模型：\n"
        "     * {@code FACING} / {@code HORIZONTAL_FACING} / {@code VERTICAL_DIRECTION} /\n"
        "     * {@code FACING_HOPPER}。所以渲染侧一行旋转代码都不用写。</p>\n"
        "     */\n"
        "    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;")

    s = s.replace("        builder.add(LIT, COMPANY, TIER);",
                  "        builder.add(LIT, COMPANY, TIER, FACING);")

    s = s.replace("                .setValue(TIER, Tier.LOW));",
                  "                .setValue(TIER, Tier.LOW)\n"
                  "                .setValue(FACING, Direction.NORTH));")

    # getStateForPlacement 末尾设置朝向
    m = re.search(r'(public BlockState getStateForPlacement\(BlockPlaceContext context\) \{[\s\S]*?)\n    \}', s)
    if m:
        body = m.group(1)
        if "setValue(FACING" not in body:
            body2 = body.rstrip() + "\n\n        // 朝向：正面朝着玩家（和通用终端一致）\n" \
                                     "        return state.setValue(FACING, context.getHorizontalDirection().getOpposite());"
            s = s.replace(m.group(1), body2)
            print("✓ getStateForPlacement 设置朝向")

    # 需要 import DirectionProperty / Direction
    if "import net.minecraft.world.level.block.state.properties.DirectionProperty;" not in s:
        s = s.replace("import net.minecraft.world.level.block.state.properties.EnumProperty;",
                      "import net.minecraft.world.level.block.properties.DirectionProperty;")  # 占位，下面修
        s = s.replace("import net.minecraft.world.level.block.properties.DirectionProperty;",
                      "import net.minecraft.world.level.block.state.properties.DirectionProperty;")
    if "import net.minecraft.core.Direction;" not in s:
        s = s.replace("import net.minecraft.core.BlockPos;",
                      "import net.minecraft.core.BlockPos;\nimport net.minecraft.core.Direction;")

    io.open(P, "w", encoding="utf-8", newline="").write(s)
    print("✓ MachineBlock 加了 FACING")
else:
    print("· MachineBlock 已有 FACING")

# ---------- 2) 四个 blockstate 补 facing ----------
print()
Y = {"north": 0, "east": 90, "south": 180, "west": 270}
for name in ("factory_core", "buyer", "factory_terminal", "market"):
    p = os.path.join(A, "blockstates", name + ".json")
    if not os.path.exists(p):
        print("  · 跳过 %s（不存在）" % name)
        continue
    d = json.load(io.open(p, encoding="utf-8"))
    old = d.get("variants") or {}
    new = {}
    for key, val in old.items():
        if key.startswith("facing="):
            new[key] = val          # 已经加过了
            continue
        for facing, deg in Y.items():
            k = "facing=%s,%s" % (facing, key) if key else "facing=%s" % facing
            v = dict(val)
            if deg:
                v["y"] = deg
            new[k] = v
    io.open(p, "w", encoding="utf-8", newline="").write(
        json.dumps({"variants": new}, ensure_ascii=False, indent=2) + "\n")
    print("  %-22s %3d → %3d 个变体" % (name + ".json", len(old), len(new)))
