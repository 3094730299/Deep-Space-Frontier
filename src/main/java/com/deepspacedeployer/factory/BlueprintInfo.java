package com.deepspacedeployer.factory;

import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一份蓝图的摘要信息（会同步给客户端，用来显示列表和材料清单）。
 *
 * @param name      蓝图名（文件名去掉 .nbt）
 * @param sizeX     尺寸
 * @param sizeY     尺寸
 * @param sizeZ     尺寸
 * @param materials 需要的材料：物品 id → 数量
 */
public record BlueprintInfo(String name, int sizeX, int sizeY, int sizeZ, Map<String, Integer> materials) {

    public Vec3i size() {
        return new Vec3i(sizeX, sizeY, sizeZ);
    }

    /** 方块总数（决定组装费和等级门槛） */
    public int totalBlocks() {
        int total = 0;
        for (int value : materials.values()) {
            total += value;
        }
        return total;
    }

    public String sizeText() {
        return sizeX + "×" + sizeY + "×" + sizeZ;
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", name);
        tag.putInt("SX", sizeX);
        tag.putInt("SY", sizeY);
        tag.putInt("SZ", sizeZ);
        ListTag list = new ListTag();
        for (Map.Entry<String, Integer> entry : materials.entrySet()) {
            CompoundTag row = new CompoundTag();
            row.putString("id", entry.getKey());
            row.putInt("n", entry.getValue());
            list.add(row);
        }
        tag.put("Materials", list);
        return tag;
    }

    public static BlueprintInfo fromNbt(CompoundTag tag) {
        Map<String, Integer> materials = new LinkedHashMap<>();
        ListTag list = tag.getList("Materials", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag row = list.getCompound(i);
            materials.put(row.getString("id"), row.getInt("n"));
        }
        return new BlueprintInfo(tag.getString("Name"), tag.getInt("SX"), tag.getInt("SY"), tag.getInt("SZ"),
                materials);
    }
}
