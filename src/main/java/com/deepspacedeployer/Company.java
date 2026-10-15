package com.deepspacedeployer;

import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;

import java.util.Locale;

/**
 * 六家公司。
 *
 * <p>名字和设定来自「星舰飞控」模组的世界观，这里只定义代号、中英文名和主题色，
 * 具体主题外观（贴图/建筑风格）在后续版本里按 {@link #themeColor()} 生成。</p>
 */
public enum Company implements StringRepresentable {

    THRESHOLD("threshold", "阈限实验集团", "Threshold Laboratories", 0xE8F4FF),
    WHISPERBELT("whisperbelt", "低语环带科技", "Whisperbelt Technologies", 0xB47CFF),
    BROKENEDGE("brokenedge", "断锷军械财团", "Brokenedge Ordnance", 0xFF5A5A),
    SKIMMER("skimmer", "掠影推进公司", "Skimmer Propulsion", 0xFFB347),
    BEDROCK("bedrock", "磐基重工工业", "Bedrock Heavy Industries", 0x9A8C78),
    ASTRALINK("astralink", "星联动力联盟", "Astralink Dynamics", 0x54C8FF);

    public static final Company[] VALUES = values();
    public static final int COUNT = VALUES.length;

    private final String id;
    private final String cnName;
    private final String enName;
    private final int themeColor;

    Company(String id, String cnName, String enName, int themeColor) {
        this.id = id;
        this.cnName = cnName;
        this.enName = enName;
        this.themeColor = themeColor;
    }

    /** 用于 id、数据包文件名的短代号，例如 threshold */
    public String id() {
        return id;
    }

    /** 中文名，例如 阈限实验集团 */
    public String cnName() {
        return cnName;
    }

    /** 英文名，例如 Threshold Laboratories */
    public String enName() {
        return enName;
    }

    /** 主题色（0xRRGGBB），用于生成贴图与 GUI 高亮 */
    public int themeColor() {
        return themeColor;
    }

    /** 本公司的货币物品 id，例如 deepspacedeployer:threshold_scrip */
    public String scripItemId() {
        return DeepSpaceDeployer.MOD_ID + ":" + id + "_scrip";
    }

    /** 本公司的票据物品注册名，例如 threshold_scrip */
    public String scripRegistryName() {
        return id + "_scrip";
    }

    public Component displayName() {
        return Component.translatable("company." + DeepSpaceDeployer.MOD_ID + "." + id);
    }

    public Company next() {
        return VALUES[(ordinal() + 1) % COUNT];
    }

    public static Company byId(String id) {
        for (Company c : VALUES) {
            if (c.id.equals(id)) {
                return c;
            }
        }
        return THRESHOLD;
    }

    /** 越界保护：数组索引 / 存档里的数字转公司 */
    public static Company byIndex(int index) {
        if (index < 0 || index >= COUNT) {
            return THRESHOLD;
        }
        return VALUES[index];
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    @Override
    public String toString() {
        return id.toLowerCase(Locale.ROOT);
    }
}
