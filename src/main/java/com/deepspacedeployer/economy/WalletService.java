package com.deepspacedeployer.economy;

import com.deepspacedeployer.net.CardWalletPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

/**
 * 钱包服务端逻辑（卡登记 / 旧余额迁移 / 把 7 个账户数据发给客户端）。
 *
 * <p>这里只做“卡的把手”相关的事；真正的余额在 {@link CompanyFunds}。</p>
 */
public final class WalletService {

    /**
     * 右键卡时服务端要做的事：老卡补登记、旧便携余额迁进账户、发钱包数据。
     * <p>未绑定的卡不会自动绑定（绑定是界面里的动作），所以这里只发 0 余额。</p>
     */
    public static void onCardUse(ServerPlayer player, ItemStack card, InteractionHand hand) {
        if (!CardData.isCard(card)) {
            return;
        }
        if (CardData.accountId(card) != null) {
            ensureRegistered(player, card);
        }
        sendWallet(player, card, hand);
    }

    /**
     * 已绑定的卡补登记（老存档没有 CardRegistry 记录），并把卡上的旧便携余额搬进账户。
     *
     * @return 卡现在是否已经在注册表里（未绑定 / 废卡 / 超过 2 张都返回 false）
     */
    public static boolean ensureRegistered(ServerPlayer player, ItemStack card) {
        if (!CardData.isCard(card)) {
            return false;
        }
        UUID account = CardData.accountId(card);
        if (account == null) {
            return false;
        }
        UUID cardId = CardData.ensureCardId(card);
        if (cardId == null) {
            return false;
        }

        CompanyFunds funds = CompanyFunds.get(player.server);
        CardRegistry registry = CardRegistry.get(player.server);
        CardRegistry.CardRecord record = registry.find(cardId);
        // 废卡只作装饰：先挡住，绝不能把旧卡余额迁进已清空的账户
        if (record != null && record.voided()) {
            return false;
        }

        // 有效卡：先把旧版卡上的便携余额迁进账户（即使下面因 2 张上限登记失败，
        // 钱也已经安全地在账户里，卡本身还能照常插机器用）
        CardData.migrateToAccount(card, funds, account);

        UUID oldOwner = registry.ownerOf(cardId);
        if (oldOwner != null && !oldOwner.equals(account)) {
            // 换主：先确认目标名额，再摘旧记录
            if (!registry.canAdd(account)) {
                limitMessage(player);
                return false;
            }
            registry.remove(cardId);
            record = null;
        }
        if (record != null) {
            return true;
        }
        if (!registry.canAdd(account)) {
            limitMessage(player);
            return false;
        }

        String label = CardData.cardLabel(card);
        String name = label.isEmpty() ? registry.uniqueName(account, "银行卡") : label;
        if (registry.nameTaken(account, name)) {
            name = registry.uniqueName(account, "银行卡");
        }
        if (!registry.add(account, cardId, account, name)) {
            limitMessage(player);
            return false;
        }
        // 旧卡自定义名和别的卡撞了 → 统一成注册表里那个不重名的名字
        if (!name.equals(label)) {
            CardData.setCardLabel(card, name);
        }
        return true;
    }

    /** 把 7 个账户 + 总资产 + 废卡状态发给客户端（卡界面用） */
    public static void sendWallet(ServerPlayer player, ItemStack card, InteractionHand hand) {
        UUID account = CardData.accountId(card);
        boolean voided = false;
        String cardName = CardData.cardLabel(card);
        UUID cardId = CardData.cardId(card);
        if (cardId != null) {
            CardRegistry.CardRecord record = CardRegistry.get(player.server).find(cardId);
            if (record != null) {
                voided = record.voided();
                cardName = record.name();       // 卡名以注册表为准（清空自定义名后仍能看到名字）
            }
        }

        // 废卡只是装饰：不再显示卡上余额；钱还在卡主账户里（挂失时没有清空）
        long[] balances = new long[CompanyFunds.SLOT_COUNT];
        long totalAssets = 0L;
        if (account != null && !voided) {
            CompanyFunds funds = CompanyFunds.get(player.server);
            balances = funds.snapshot(account);
            totalAssets = funds.totalAssets(account);
        }

        PacketDistributor.sendToPlayer(player, new CardWalletPayload(
                hand.ordinal(), balances, totalAssets, voided,
                CardData.accountName(card), cardName));
    }

    /** 这张卡是不是已经挂失（废卡只作装饰，不能再插机器用） */
    public static boolean isVoided(MinecraftServer server, ItemStack card) {
        UUID cardId = CardData.cardId(card);
        if (cardId == null) {
            return false;
        }
        CardRegistry.CardRecord record = CardRegistry.get(server).find(cardId);
        return record != null && record.voided();
    }

    /** 这个玩家还能不能再绑一张卡（2 张上限；挂失才减） */
    public static boolean canAdd(ServerPlayer player) {
        return CardRegistry.get(player.server).canAdd(player.getUUID());
    }

    /** 给机器/终端用的超限提示（橙色斜体） */
    public static void limitMessage(ServerPlayer player) {
        player.displayClientMessage(Component.translatable("msg.deepspacedeployer.card_limit")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.ITALIC), true);
    }

    /** 购买时卡里对应公司的钱不够：倾斜浅红提示 */
    public static void companyInsufficientMessage(ServerPlayer player) {
        player.displayClientMessage(Component.translatable("msg.deepspacedeployer.company_insufficient")
                .withStyle(ChatFormatting.RED, ChatFormatting.ITALIC), true);
    }

    private WalletService() {
    }
}
