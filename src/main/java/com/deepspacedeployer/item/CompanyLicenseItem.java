package com.deepspacedeployer.item;

import com.deepspacedeployer.Company;
import com.deepspacedeployer.blockentity.MachineBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;

import java.util.List;

/**
 * 公司授权铭牌：从该公司商店买来的"经营许可"。
 *
 * <p>用法：对着自己放的机器（商店/工厂/焊接/收购）右键 → 这台机器变成该公司的，
 * 货币、外观、商品池都跟着变。铭牌会被消耗。</p>
 *
 * <p>安全限制：机器里还存着钱的时候不允许改公司（否则等于用低价值公司的存款
 * 变成高价值公司的钱）。</p>
 */
public class CompanyLicenseItem extends Item implements MachineTargetItem {

    private final Company company;

    public CompanyLicenseItem(Company company, Properties properties) {
        super(properties);
        this.company = company;
    }

    public Company company() {
        return company;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel().getBlockEntity(context.getClickedPos()) instanceof MachineBlockEntity machine)) {
            return InteractionResult.PASS;
        }
        if (context.getLevel().isClientSide || context.getPlayer() == null) {
            return InteractionResult.SUCCESS;
        }
        if (machine.company() == company) {
            context.getPlayer().displayClientMessage(
                    Component.translatable("msg.deepspacedeployer.license_same", company.displayName()), true);
            return InteractionResult.CONSUME;
        }
        machine.setCompany(company);
        context.getItemInHand().shrink(1);
        context.getPlayer().displayClientMessage(
                Component.translatable("msg.deepspacedeployer.license_used", company.displayName()), false);
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.deepspacedeployer.license_company", company.displayName())
                .withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("tooltip.deepspacedeployer.license_usage")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
