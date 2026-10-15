package com.deepspacedeployer.factory;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 蓝图解析：直接读结构 NBT 的 {@code size / palette / blocks}。
 *
 * <p><b>为什么不干脆用 {@code StructureTemplate#filterBlocks}：</b>
 * 那个方法的过滤器实现是 {@code state.is(filter)}，而原版 {@code BlockStateBase#is(Block)}
 * 就是 {@code getBlock() == block} —— 传 null 时恒为 false，一个方块都筛不出来
 * （材料清单会永远是空的）。直接读 NBT 既准确又不依赖这些边角行为。</p>
 *
 * <p>顺带说明：原版 {@code StructureTemplate#load} 同时支持 {@code palette}（单数）和
 * {@code palettes}（复数，多个随机调色板），这里也两种都认。</p>
 */
public final class BlueprintParser {

    /** 解析失败（格式不对）返回 null */
    public static ParsedBlueprint parse(CompoundTag tag, HolderGetter<Block> blockGetter) {
        ListTag sizeTag = tag.getList("size", Tag.TAG_INT);
        if (sizeTag.size() < 3) {
            return null;
        }
        int sizeX = sizeTag.getInt(0);
        int sizeY = sizeTag.getInt(1);
        int sizeZ = sizeTag.getInt(2);
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
            return null;
        }

        List<BlockState> states = new ArrayList<>();
        if (tag.contains("palettes", Tag.TAG_LIST)) {
            ListTag palettes = tag.getList("palettes", Tag.TAG_LIST);
            if (!palettes.isEmpty()) {
                readPalette(palettes.getList(0), blockGetter, states);
            }
        } else if (tag.contains("palette", Tag.TAG_LIST)) {
            readPalette(tag.getList("palette", Tag.TAG_COMPOUND), blockGetter, states);
        }
        if (states.isEmpty()) {
            return null;
        }

        Map<String, Integer> materials = new LinkedHashMap<>();
        List<BlockPos> positions = new ArrayList<>();
        ListTag blocksTag = tag.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blocksTag.size(); i++) {
            CompoundTag blockTag = blocksTag.getCompound(i);
            int stateIndex = blockTag.getInt("state");
            if (stateIndex < 0 || stateIndex >= states.size()) {
                continue;
            }
            BlockState state = states.get(stateIndex);
            if (state.isAir()) {
                continue;   // 空气不参与材料统计（但会被原版放置时用来"挖空"内部）
            }
            ListTag posTag = blockTag.getList("pos", Tag.TAG_INT);
            if (posTag.size() >= 3) {
                positions.add(new BlockPos(posTag.getInt(0), posTag.getInt(1), posTag.getInt(2)));
            }
            Item item = state.getBlock().asItem();
            if (item == Items.AIR) {
                continue;   // 流体之类的没有对应物品，跳过
            }
            materials.merge(BuiltInRegistries.ITEM.getKey(item).toString(), 1, Integer::sum);
        }

        return new ParsedBlueprint(sizeX, sizeY, sizeZ, materials, positions, tag);
    }

    private static void readPalette(ListTag paletteTag, HolderGetter<Block> blockGetter, List<BlockState> out) {
        for (int i = 0; i < paletteTag.size(); i++) {
            out.add(NbtUtils.readBlockState(blockGetter, paletteTag.getCompound(i)));
        }
    }

    private BlueprintParser() {
    }
}
