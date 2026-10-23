package com.deepspacedeployer.item;

import com.deepspacedeployer.Company;
import com.deepspacedeployer.economy.CurrencyDenomination;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * 一张实体货币（公司券）。
 *
 * <p>面额 1 / 5 / 10 / 20 / 50 / 100，六家公司各一套，共 36 种。
 * 堆叠上限 100；可在深空货币银行的「取钱 / 存钱」页按面额存入/取出。</p>
 */
public class CurrencyItem extends Item {

    private final Company company;
    private final CurrencyDenomination denomination;

    public CurrencyItem(Company company, CurrencyDenomination denomination, Properties properties) {
        super(properties);
        this.company = company;
        this.denomination = denomination;
    }

    public Company company() {
        return company;
    }

    public CurrencyDenomination denomination() {
        return denomination;
    }

    /** 单张面额（元） */
    public int value() {
        return denomination.value();
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.deepspacedeployer.currency_company",
                company.displayName()).withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("tooltip.deepspacedeployer.currency_value",
                denomination.value()).withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("tooltip.deepspacedeployer.currency_deposit")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
