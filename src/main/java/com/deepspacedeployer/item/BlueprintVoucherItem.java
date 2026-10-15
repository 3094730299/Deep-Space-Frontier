package com.deepspacedeployer.item;

import com.deepspacedeployer.block.MachineBlock;
import com.deepspacedeployer.blockentity.MachineBlockEntity;
import com.deepspacedeployer.blockentity.MachineKind;
import com.deepspacedeployer.economy.VoucherData;
import com.deepspacedeployer.factory.BlueprintLibrary;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;

import java.util.List;

/**
 * 蓝图凭证：从商店买来的"某份物理结构的使用权"。
 *
 * <p>用法：拿着它对**工厂装配核心**右键 → 该核心立刻选中这张凭证对应的蓝图；
 * 之后照常投材料、一键组装。凭证会被消耗。</p>
 */
public class BlueprintVoucherItem extends Item implements MachineTargetItem {

    public BlueprintVoucherItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel().getBlockEntity(context.getClickedPos()) instanceof MachineBlockEntity machine)
                || machine.kind() != MachineKind.CORE) {
            return InteractionResult.PASS;
        }
        if (context.getLevel().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        ItemStack stack = context.getItemInHand();
        String blueprint = VoucherData.blueprint(stack);
        if (context.getPlayer() == null) {
            return InteractionResult.PASS;
        }
        if (blueprint.isEmpty()) {
            context.getPlayer().displayClientMessage(
                    Component.translatable("msg.deepspacedeployer.voucher_empty"), true);
            return InteractionResult.CONSUME;
        }
        if (BlueprintLibrary.get(blueprint) == null) {
            context.getPlayer().displayClientMessage(
                    Component.translatable("msg.deepspacedeployer.voucher_missing_blueprint", blueprint), true);
            return InteractionResult.CONSUME;
        }
        if (machine.applyBlueprint(blueprint)) {
            stack.shrink(1);
            context.getPlayer().displayClientMessage(
                    Component.translatable("msg.deepspacedeployer.voucher_used", blueprint), false);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        String blueprint = VoucherData.blueprint(stack);
        if (blueprint.isEmpty()) {
            tooltip.add(Component.translatable("tooltip.deepspacedeployer.voucher_empty")
                    .withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.translatable("tooltip.deepspacedeployer.voucher_blueprint", blueprint)
                    .withStyle(ChatFormatting.AQUA));
            tooltip.add(Component.translatable("tooltip.deepspacedeployer.voucher_usage")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
