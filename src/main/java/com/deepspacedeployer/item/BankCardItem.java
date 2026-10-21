package com.deepspacedeployer.item;

import com.deepspacedeployer.client.ClientScreenBridge;
import com.deepspacedeployer.economy.CardData;
import com.deepspacedeployer.economy.CompanyFunds;
import com.deepspacedeployer.economy.WalletService;
import com.deepspacedeployer.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.UUID;

/**
 * 深空银行卡（<b>唯一一种</b>）。
 *
 * <p>右键打开钱包界面：7 个账户余额（6 家公司 + 通用）+ 总资产 + 绑定/改名。</p>
 *
 * <p>卡只是<b>账户指针</b>：余额在世界存档的 {@code CompanyFunds} 里，卡上不存钱。
 * 老存档里的 {@code bank_card_sub} 仍注册着（防掉物品），但不在创造栏发放，
 * 右键时自动换成普通卡，并把旧卡上的便携余额迁进账户。</p>
 */
public class BankCardItem extends Item {

    /** 旧副卡：只用于迁移，不在创造栏发放 */
    private final boolean legacy;

    public BankCardItem(Properties properties) {
        this(properties, false);
    }

    public BankCardItem(Properties properties, boolean legacy) {
        super(properties);
        this.legacy = legacy;
    }

    public boolean isLegacy() {
        return legacy;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        // 旧副卡：先换成唯一卡，组件（账户/卡 id/旧余额）原样带过去
        if (legacy && stack.is(ModItems.BANK_CARD_SUB.get())) {
            // 旧副卡的独立账户是合成 UUID；如果持有人就是卡主，把余额并回本人账户
            if (level instanceof ServerLevel serverLevel) {
                UUID oldAccount = CardData.accountId(stack);
                String ownerName = CardData.accountName(stack);
                if (oldAccount != null && !oldAccount.equals(player.getUUID())
                        && ownerName.equalsIgnoreCase(player.getGameProfile().getName())) {
                    CompanyFunds funds = CompanyFunds.get(serverLevel.getServer());
                    long[] snapshot = funds.snapshot(oldAccount);
                    for (int slot = 0; slot < snapshot.length; slot++) {
                        if (snapshot[slot] != 0L) {
                            funds.addSlot(player.getUUID(), slot, snapshot[slot]);
                        }
                    }
                    funds.wipe(oldAccount);
                    CardData.setAccount(stack, player.getUUID(), ownerName);
                }
            }
            ItemStack converted = new ItemStack(ModItems.BANK_CARD.get(), stack.getCount());
            converted.applyComponents(stack.getComponents());
            player.setItemInHand(hand, converted);
            stack = converted;
        }

        if (level.isClientSide()) {
            // 只在客户端打开界面（服务器端不会加载到客户端类）
            ClientScreenBridge.openCardBindScreen(hand);
        } else if (player instanceof ServerPlayer serverPlayer) {
            // 服务端：登记卡表、迁移旧余额、把 7 个账户数据发给客户端
            WalletService.onCardUse(serverPlayer, stack, hand);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        if (legacy) {
            tooltip.add(Component.translatable("tooltip.deepspacedeployer.card_legacy_note")
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        }

        String account = CardData.accountName(stack);
        if (account.isEmpty()) {
            tooltip.add(Component.translatable("tooltip.deepspacedeployer.card_unbound")
                    .withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.translatable("tooltip.deepspacedeployer.card_account", account)
                    .withStyle(ChatFormatting.AQUA));
            tooltip.add(Component.translatable("tooltip.deepspacedeployer.card_owner_only")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }

        tooltip.add(Component.translatable("tooltip.deepspacedeployer.card_wallet_hint")
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("tooltip.deepspacedeployer.card_hint")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
