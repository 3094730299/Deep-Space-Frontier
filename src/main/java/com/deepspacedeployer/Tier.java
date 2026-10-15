package com.deepspacedeployer;

import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;

/**
 * 工厂等级：低级 / 中级 / 高级。
 *
 * <ul>
 *   <li>外观规模：低级最小，高级最大（世界生成结构尺寸 9 / 13 / 17）</li>
 *   <li>功能区：低级只有商店+收购，中级加工厂方块，高级再加焊接方块</li>
 *   <li>焊接方块的"开关碰撞"功能：<b>只有高级有</b></li>
 * </ul>
 */
public enum Tier implements StringRepresentable {

    LOW("low", "低级", "Low", 0, 9),
    MID("mid", "中级", "Mid", 1, 13),
    HIGH("high", "高级", "High", 2, 17);

    public static final Tier[] VALUES = values();
    public static final int COUNT = VALUES.length;

    private final String id;
    private final String cnName;
    private final String enName;
    private final int rank;
    /** 世界生成时该等级工厂的地基边长（格） */
    private final int footprint;

    Tier(String id, String cnName, String enName, int rank, int footprint) {
        this.id = id;
        this.cnName = cnName;
        this.enName = enName;
        this.rank = rank;
        this.footprint = footprint;
    }

    public String id() {
        return id;
    }

    public String cnName() {
        return cnName;
    }

    public String enName() {
        return enName;
    }

    /** 0=低 1=中 2=高 */
    public int rank() {
        return rank;
    }

    public int footprint() {
        return footprint;
    }

    /** 高级解锁焊接方块的开关碰撞 */
    public boolean hasCollisionToggle() {
        return this == HIGH;
    }

    /** 中级起解锁工厂方块 */
    public boolean hasFactoryBlock() {
        return rank >= MID.rank;
    }

    /**
     * 收购价倍率：低级厂压价（0.8），中级正常（1.0），高级溢价（1.2）。
     * 这就是"按公司 / 等级差异化定价"里等级的那一半。
     */
    public double sellMultiplier() {
        return switch (this) {
            case LOW -> 0.8D;
            case MID -> 1.0D;
            case HIGH -> 1.2D;
        };
    }

    /** 该等级工厂最多能组装多少方块的蓝图 */
    public int maxBlueprintBlocks() {
        return switch (this) {
            case LOW -> 200;
            case MID -> 800;
            case HIGH -> 4000;
        };
    }

    public Component displayName() {
        return Component.translatable("tier." + DeepSpaceDeployer.MOD_ID + "." + id);
    }

    public static Tier byId(String id) {
        for (Tier t : VALUES) {
            if (t.id.equals(id)) {
                return t;
            }
        }
        return LOW;
    }

    public static Tier byIndex(int index) {
        if (index < 0 || index >= COUNT) {
            return LOW;
        }
        return VALUES[index];
    }

    @Override
    public String getSerializedName() {
        return id;
    }
}
