package com.deepspacedeployer.item;

import com.deepspacedeployer.Company;
import com.deepspacedeployer.client.ClientScreenBridge;
import com.deepspacedeployer.economy.CardData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 深空银行卡（主卡 / 副卡）。
 *
 * <p>右键打开"绑定账户"界面，可以填写玩家名/UUID；插进机器后代表该玩家的账户。</p>
 *
 * <p><b>主卡与副卡的区别</b>（老板要求）：副卡的余额与主卡<b>不通用</b> ——
 * 副卡有自己的账户池，只能靠"转账"从主卡或别的副卡收钱；
 * 而插卡花钱、收购收入都仍然走卡主的<b>主账户</b>（这样副卡才收得到钱）。</p>
 */
public class BankCardItem extends Item {

    private final boolean sub;

    public BankCardItem(Properties properties, boolean sub) {
        super(properties);
        this.sub = sub;
    }

    /** 是不是副卡 */
    public boolean isSub() {
        return sub;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) {
            // 只在客户端打开界面（服务器端不会加载到客户端类）
            ClientScreenBridge.openCardBindScreen(hand);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        if (sub) {
            tooltip.add(Component.translatable("tooltip.deepspacedeployer.card_sub_note")
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        String account = CardData.accountName(stack);
        if (account.isEmpty()) {
            tooltip.add(Component.translatable("tooltip.deepspacedeployer.card_unbound")
                    .withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.translatable("tooltip.deepspacedeployer.card_account", account)
                    .withStyle(ChatFormatting.AQUA));
            // 归属提示：卡可以借给别人用，但只有卡主能改名/改绑/解绑
            tooltip.add(Component.translatable("tooltip.deepspacedeployer.card_owner_only")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }

        long[] balances = CardData.getBalances(stack);
        for (Company company : Company.VALUES) {
            long value = balances[company.ordinal()];
            if (value > 0L) {
                tooltip.add(Component.translatable("tooltip.deepspacedeployer.card_balance",
                        company.displayName(), value).withStyle(ChatFormatting.DARK_GRAY));
            }
        }

        tooltip.add(Component.translatable(sub
                        ? "tooltip.deepspacedeployer.card_sub_hint"
                        : "tooltip.deepspacedeployer.card_hint")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
