package com.deepspacedeployer.factory;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.List;
import java.util.Map;

/**
 * 解析好的蓝图（服务端专有）。
 *
 * @param sizeX     尺寸
 * @param sizeY     尺寸
 * @param sizeZ     尺寸
 * @param materials 材料：物品 id → 数量（不含空气）
 * @param positions 非空气方块的相对坐标（用于"场地够不够空"的检查）
 * @param tag       原始 NBT（落地时再用原版的 StructureTemplate 放置）
 */
public record ParsedBlueprint(int sizeX, int sizeY, int sizeZ, Map<String, Integer> materials,
                              List<BlockPos> positions, CompoundTag tag) {

    public BlueprintInfo toInfo(String name) {
        return new BlueprintInfo(name, sizeX, sizeY, sizeZ, materials);
    }

    public int totalBlocks() {
        return positions.size();
    }
}
