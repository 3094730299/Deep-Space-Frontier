package com.deepspacedeployer.item;

import com.deepspacedeployer.Tier;
import com.deepspacedeployer.blockentity.MachineBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 机械级别升级插件（原名"等级升级套件"）：把一台机器升一级（低级 → 中级 → 高级）。
 *
 * <p>用法：对着机器右键。已在高级时会提示用不了（不消耗）。</p>
 *
 * <p><b>名字是橙红→黄的渐变色</b>（老板要求 "▽机械级别升级插件"，带渐变）：
 * 原版物品名的颜色是全名一色，做不到逐字渐变，所以这里把一个
 * {@link net.minecraft.network.chat.Component} 按<b>每个字一段样式</b>拼出来，
 * 作为物品的默认名字（{@code DataComponents.CUSTOM_NAME}），
 * 这样创造栏、背包、聊天栏、物品展示框里都是渐变的。
 * 颜色的插值同时做在 RGB 和 HSV 上 —— 橙红到黄直接线性插值会经过灰绿，
 * 走 HSV 才是干净的"橙→黄"。</p>
 */
public class TierUpgradeKitItem extends Item implements MachineTargetItem {

    /** 渐变的起点（橙红）与终点（黄） */
    private static final int GRADIENT_FROM = 0xFF4A1E;
    private static final int GRADIENT_TO = 0xFFE24A;
    /** 物品名字（▽ + 八个字） */
    private static final String DISPLAY_NAME = "▽机械级别升级插件";

    public TierUpgradeKitItem(Properties properties) {
        super(properties);
    }

    /** 渐变色名字组件（按字符逐个上色） */
    public static MutableComponent gradientName() {
        return gradient(DISPLAY_NAME, GRADIENT_FROM, GRADIENT_TO);
    }

    /**
     * 逐字渐变：第 i 个字取 from→to 之间 i/(n-1) 处的颜色。
     *
     * @param text 文字（按 UTF-16 单元切；这里都是 BMP 字符，安全）
     */
    public static MutableComponent gradient(String text, int from, int to) {
        MutableComponent out = Component.empty();
        int length = Math.max(1, text.length() - 1);
        for (int i = 0; i < text.length(); i++) {
            float ratio = (float) i / length;
            // HSV 插值：橙红(20°) → 黄(50°)，饱和度/明度同步过渡
            float[] fromHsv = rgbToHsv(from);
            float[] toHsv = rgbToHsv(to);
            float hue = fromHsv[0] + (toHsv[0] - fromHsv[0]) * ratio;
            float sat = fromHsv[1] + (toHsv[1] - fromHsv[1]) * ratio;
            float val = fromHsv[2] + (toHsv[2] - fromHsv[2]) * ratio;
            int color = hsvToRgb(hue, sat, val);
            out.append(Component.literal(String.valueOf(text.charAt(i)))
                    .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(color & 0xFFFFFF))));
        }
        return out;
    }

    private static float[] rgbToHsv(int rgb) {
        float r = ((rgb >> 16) & 0xFF) / 255.0F;
        float g = ((rgb >> 8) & 0xFF) / 255.0F;
        float b = (rgb & 0xFF) / 255.0F;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;
        float hue;
        if (delta == 0.0F) {
            hue = 0.0F;
        } else if (max == r) {
            hue = 60.0F * (((g - b) / delta) % 6.0F);
        } else if (max == g) {
            hue = 60.0F * (((b - r) / delta) + 2.0F);
        } else {
            hue = 60.0F * (((r - g) / delta) + 4.0F);
        }
        if (hue < 0.0F) {
            hue += 360.0F;
        }
        float sat = max == 0.0F ? 0.0F : delta / max;
        return new float[]{hue, sat, max};
    }

    private static int hsvToRgb(float hue, float sat, float val) {
        float c = val * sat;
        float x = c * (1.0F - Math.abs((hue / 60.0F) % 2.0F - 1.0F));
        float m = val - c;
        float r;
        float g;
        float b;
        if (hue < 60.0F) {
            r = c; g = x; b = 0.0F;
        } else if (hue < 120.0F) {
            r = x; g = c; b = 0.0F;
        } else if (hue < 180.0F) {
            r = 0.0F; g = c; b = x;
        } else if (hue < 240.0F) {
            r = 0.0F; g = x; b = c;
        } else if (hue < 300.0F) {
            r = x; g = 0.0F; b = c;
        } else {
            r = c; g = 0.0F; b = x;
        }
        int ri = Math.round((r + m) * 255.0F);
        int gi = Math.round((g + m) * 255.0F);
        int bi = Math.round((b + m) * 255.0F);
        return (ri << 16) | (gi << 8) | bi;
    }

    @Override
    public Component getName(ItemStack stack) {
        // 没被改名就用渐变名；玩家自己改了名就听玩家的
        if (stack.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)) {
            return super.getName(stack);
        }
        return gradientName();
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel().getBlockEntity(context.getClickedPos()) instanceof MachineBlockEntity machine)) {
            return InteractionResult.PASS;
        }
        if (context.getLevel().isClientSide || context.getPlayer() == null) {
            return InteractionResult.SUCCESS;
        }
        Tier current = machine.tier();
        if (current == Tier.HIGH) {
            context.getPlayer().displayClientMessage(
                    Component.translatable("msg.deepspacedeployer.kit_already_max"), true);
            return InteractionResult.CONSUME;
        }
        Tier next = Tier.byIndex(current.rank() + 1);
        machine.setTier(next);
        context.getItemInHand().shrink(1);
        context.getPlayer().displayClientMessage(
                Component.translatable("msg.deepspacedeployer.kit_used", current.displayName(), next.displayName()),
                false);
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.deepspacedeployer.kit_usage")
                .withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("tooltip.deepspacedeployer.kit_detail")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}

