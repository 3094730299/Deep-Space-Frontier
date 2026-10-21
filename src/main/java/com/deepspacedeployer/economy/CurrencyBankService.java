package com.deepspacedeployer.economy;

import com.deepspacedeployer.Company;
import com.deepspacedeployer.blockentity.CurrencyBankBlockEntity;
import com.deepspacedeployer.net.CardListPayload;
import com.deepspacedeployer.net.CurrencyBankPayload;
import com.deepspacedeployer.registry.ModBlocks;
import com.deepspacedeployer.registry.ModItems;
import com.mojang.authlib.GameProfile;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 深空货币银行（服务端逻辑）。
 *
 * <p>银行有两个共享卡槽：转换/转账都从“自己插在银行里的那张卡”出；
 * 对面卡槽里是别人的卡时，确认转账就是当面转给对方；填了目标玩家名则按名字转账。
 * 两个卡槽都没有别人的卡、也没填名字时，按上方两个源/目标选择做自己的货币转换。</p>
 */
public final class CurrencyBankService {

    private static final long FREE_CARD_COOLDOWN = 6000L;
    private static final String FREE_CARD_TAG = "DSD_BankFreeCard";

    public static void handle(ServerPlayer player, CurrencyBankPayload p) {
        CurrencyBankBlockEntity bank = findBank(player);
        if (bank == null) {
            error(player, "msg.deepspacedeployer.bank_not_near");
            return;
        }
        switch (p.action()) {
            case CurrencyBankPayload.ACTION_FREE_CARD -> freeCard(player);
            case CurrencyBankPayload.ACTION_VOID -> voidSelected(player, p, bank);
            case CurrencyBankPayload.ACTION_CONFIRM -> confirm(player, bank, p);
            default -> error(player, "msg.deepspacedeployer.bank_bad_action");
        }
    }

    // ---------------- 一个按钮：转换 / 当面转 / 名字转账 ----------------

    private static void confirm(ServerPlayer player, CurrencyBankBlockEntity bank, CurrencyBankPayload p) {
        ItemStack sourceCard = bank.cardFor(player);
        if (sourceCard.isEmpty()) {
            error(player, "msg.deepspacedeployer.bank_insert_card_first");
            return;
        }
        if (WalletService.isVoided(player.server, sourceCard)) {
            error(player, "msg.deepspacedeployer.card_voided");
            return;
        }
        UUID account = CardData.accountId(sourceCard);
        if (account == null) {
            error(player, "msg.deepspacedeployer.no_account");
            return;
        }
        long amount = p.amount();
        if (amount <= 0L) {
            error(player, "msg.deepspacedeployer.bank_amount_invalid");
            return;
        }

        CompanyFunds funds = CompanyFunds.get(player.server);
        String targetName = p.targetName() == null ? "" : p.targetName().trim();
        ItemStack targetCard = bank.otherCardFor(player);
        boolean ok;

        if (!targetName.isEmpty()) {
            UUID target = resolveTarget(player.server, targetName);
            if (target == null) {
                error(player, "msg.deepspacedeployer.bank_target_missing", targetName);
                return;
            }
            if (target.equals(player.getUUID())) {
                error(player, "msg.deepspacedeployer.bank_self_transfer");
                return;
            }
            ok = transferToAccount(player, funds, account, target, p.from(), amount, targetName);
        } else if (!targetCard.isEmpty()) {
            if (WalletService.isVoided(player.server, targetCard)) {
                error(player, "msg.deepspacedeployer.card_voided");
                return;
            }
            UUID target = CardData.accountId(targetCard);
            if (target == null) {
                error(player, "msg.deepspacedeployer.no_account");
                return;
            }
            if (target.equals(account)) {
                error(player, "msg.deepspacedeployer.bank_same_account");
                return;
            }
            String label = CardData.accountName(targetCard);
            if (label.isEmpty()) {
                label = Component.translatable("gui.deepspacedeployer.bank_other_player").getString();
            }
            ok = transferToAccount(player, funds, account, target, p.from(), amount, label);
        } else {
            ok = selfConvert(player, funds, account, p, amount);
        }

        if (ok) {
            bank.sendNearby();
            sendCardList(player);
        }
    }

    /** 自己的账户内：源公司/通用 → 目标公司/通用。 */
    private static boolean selfConvert(ServerPlayer player, CompanyFunds funds, UUID account,
                                       CurrencyBankPayload p, long amount) {
        int from = p.from();
        int to = p.to();
        if (from < 0 || from > CompanyFunds.GENERIC_SLOT || to < 0 || to > CompanyFunds.GENERIC_SLOT) {
            error(player, "msg.deepspacedeployer.bank_pick_company");
            return false;
        }
        if (from == to) {
            error(player, "msg.deepspacedeployer.bank_same_company");
            return false;
        }
        if (from < Company.COUNT && to < Company.COUNT) {
            return companyToCompany(player, funds, account, p, amount);
        }
        if (from < Company.COUNT) {
            return companyToGeneric(player, funds, account, p, amount);
        }
        if (to < Company.COUNT) {
            return genericToCompany(player, funds, account, p, amount);
        }
        error(player, "msg.deepspacedeployer.bank_bad_action");
        return false;
    }

    // ---------------- 转换到自己的另一个币种 ----------------

    private static boolean companyToCompany(ServerPlayer player, CompanyFunds funds, UUID account,
                                            CurrencyBankPayload p, long amount) {
        Company from = Company.VALUES[p.from()];
        Company to = Company.VALUES[p.to()];
        if (funds.balance(account, from) < amount) {
            error(player, "msg.deepspacedeployer.bank_insufficient");
            return false;
        }
        long net = amount - amount / 5L;           // 20% tax
        funds.add(account, from, -amount);
        funds.add(account, to, net);
        success(player, "msg.deepspacedeployer.bank_converted",
                from.displayName(), to.displayName(), net);
        return true;
    }

    private static boolean companyToGeneric(ServerPlayer player, CompanyFunds funds, UUID account,
                                            CurrencyBankPayload p, long amount) {
        Company from = Company.VALUES[p.from()];
        if (funds.balance(account, from) < amount) {
            error(player, "msg.deepspacedeployer.bank_insufficient");
            return false;
        }
        long net = amount * 70L / 100L;            // 30% tax
        funds.add(account, from, -amount);
        funds.addGeneric(account, net);
        success(player, "msg.deepspacedeployer.bank_to_generic", from.displayName(), net);
        return true;
    }

    private static boolean genericToCompany(ServerPlayer player, CompanyFunds funds, UUID account,
                                            CurrencyBankPayload p, long amount) {
        Company to = Company.VALUES[p.to()];
        if (funds.generic(account) < amount) {
            error(player, "msg.deepspacedeployer.bank_insufficient");
            return false;
        }
        funds.addGeneric(account, -amount);
        funds.add(account, to, amount);            // 0% tax
        success(player, "msg.deepspacedeployer.bank_from_generic", to.displayName(), amount);
        return true;
    }

    // ---------------- 转给另一个账户（玩家名字 / 当面卡） ----------------

    private static boolean transferToAccount(ServerPlayer player, CompanyFunds funds,
                                             UUID fromAccount, UUID targetAccount,
                                             int slot, long amount, String targetLabel) {
        if (slot < 0 || slot > CompanyFunds.GENERIC_SLOT) {
            error(player, "msg.deepspacedeployer.bank_pick_company");
            return false;
        }
        long balance = slot == CompanyFunds.GENERIC_SLOT
                ? funds.generic(fromAccount)
                : funds.balance(fromAccount, Company.VALUES[slot]);
        if (balance < amount) {
            error(player, "msg.deepspacedeployer.bank_insufficient");
            return false;
        }
        long net = amount - amount / 20L;          // 5% tax
        if (slot == CompanyFunds.GENERIC_SLOT) {
            funds.addGeneric(fromAccount, -amount);
            funds.addGeneric(targetAccount, net);
        } else {
            Company company = Company.VALUES[slot];
            funds.add(fromAccount, company, -amount);
            funds.add(targetAccount, company, net);
        }
        success(player, "msg.deepspacedeployer.bank_transferred", amount, net, targetLabel);
        return true;
    }

    // ---------------- 免费取卡 / 挂失 ----------------

    private static void freeCard(ServerPlayer player) {
        CompoundTag data = player.getPersistentData();
        long now = player.level().getGameTime();
        long last = data.getLong(FREE_CARD_TAG);
        if (last > 0L && now - last < FREE_CARD_COOLDOWN) {
            long left = (FREE_CARD_COOLDOWN - (now - last) + 19L) / 20L;
            error(player, "msg.deepspacedeployer.bank_free_card_cooldown", left);
            return;
        }
        ItemStack card = new ItemStack(ModItems.BANK_CARD.get());
        if (!player.getInventory().add(card)) {
            player.drop(card, false);
        }
        data.putLong(FREE_CARD_TAG, now);
        success(player, "msg.deepspacedeployer.bank_free_card");
    }

    private static void voidSelected(ServerPlayer player, CurrencyBankPayload p,
                                     CurrencyBankBlockEntity bank) {
        String raw = p.cardId() == null ? "" : p.cardId().trim();
        UUID cardId = null;
        if (!raw.isEmpty()) {
            try {
                cardId = UUID.fromString(raw);
            } catch (IllegalArgumentException ignored) {
                cardId = null;
            }
        }
        if (cardId == null) {
            // 兼容旧界面/空选择：优先挂失银行里自己那张
            cardId = CardData.cardId(bank.cardFor(player));
        }
        if (cardId == null) {
            error(player, "msg.deepspacedeployer.bank_no_card");
            return;
        }
        CardRegistry registry = CardRegistry.get(player.server);
        UUID owner = registry.ownerOf(cardId);
        if (owner == null || !owner.equals(player.getUUID())) {
            error(player, "msg.deepspacedeployer.card_not_yours");
            return;
        }
        CardRegistry.CardRecord record = registry.find(cardId);
        if (record == null) {
            error(player, "msg.deepspacedeployer.bank_void_missing");
            return;
        }
        if (record.voided()) {
            error(player, "msg.deepspacedeployer.card_voided");
            return;
        }
        if (registry.voidCard(cardId, player.server)) {
            success(player, "msg.deepspacedeployer.bank_void_done");
            bank.sendNearby();
            sendCardList(player);
        } else {
            error(player, "msg.deepspacedeployer.bank_void_missing");
        }
    }

    /** 把玩家名下的卡列表发给银行界面（挂失选卡） */
    public static void sendCardList(ServerPlayer player) {
        CardRegistry registry = CardRegistry.get(player.server);
        List<CardListPayload.CardEntry> list = new ArrayList<>();
        for (CardRegistry.CardRecord record : registry.cards(player.getUUID())) {
            list.add(new CardListPayload.CardEntry(
                    record.cardId().toString(), record.name(), record.voided()));
        }
        PacketDistributor.sendToPlayer(player, new CardListPayload(List.copyOf(list)));
    }

    // ---------------- 查找 / 目标 ----------------

    /** 玩家 8 格内第一台货币银行；找不到返回 null。 */
    private static CurrencyBankBlockEntity findBank(ServerPlayer player) {
        BlockPos center = player.blockPosition();
        int r = 8;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-r, -r, -r), center.offset(r, r, r))) {
            if (!player.level().getBlockState(pos).is(ModBlocks.CURRENCY_BANK.get())) {
                continue;
            }
            if (player.level().getBlockEntity(pos) instanceof CurrencyBankBlockEntity bank) {
                return bank;
            }
        }
        return null;
    }

    private static UUID resolveTarget(MinecraftServer server, String name) {
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            return online.getUUID();
        }
        try {
            return UUID.fromString(name);
        } catch (IllegalArgumentException ignored) {
            // 继续查缓存
        }
        GameProfileCache cache = server.getProfileCache();
        if (cache != null) {
            Optional<GameProfile> profile = cache.get(name);
            if (profile.isPresent()) {
                return profile.get().getId();
            }
        }
        return null;
    }

    // ---------------- 提示 ----------------

    private static void success(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable(key, args)
                .withStyle(ChatFormatting.GREEN), false);
    }

    private static void error(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable(key, args)
                .withStyle(ChatFormatting.RED, ChatFormatting.ITALIC), true);
    }

    private CurrencyBankService() {
    }
}
