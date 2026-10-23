package com.deepspacedeployer.economy;

import com.deepspacedeployer.Company;
import com.deepspacedeployer.blockentity.CurrencyBankBlockEntity;
import com.deepspacedeployer.item.CurrencyItem;
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
import net.minecraft.world.entity.player.Inventory;
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
            case CurrencyBankPayload.ACTION_VOID_SLOT -> voidSlot(player, p, bank);
            case CurrencyBankPayload.ACTION_CONFIRM -> confirm(player, bank, p);
            case CurrencyBankPayload.ACTION_DEPOSIT_CASH -> depositCash(player, p, bank);
            case CurrencyBankPayload.ACTION_WITHDRAW_CASH -> withdrawCash(player, p, bank);
            default -> error(player, "msg.deepspacedeployer.bank_bad_action");
        }
    }

    // ---------------- 一个按钮：转换 / 当面转 / 名字转账 ----------------

    private static void confirm(ServerPlayer player, CurrencyBankBlockEntity bank, CurrencyBankPayload p) {
        ItemStack sourceCard = bank.cardFor(player);
        if (sourceCard.isEmpty()) {
            // 卡已经放进槽里，但没绑定/是废卡时给明确原因，别只说“先插卡”
            for (int i = 0; i < CurrencyBankBlockEntity.SLOTS; i++) {
                ItemStack card = bank.getItem(i);
                if (card.isEmpty()) {
                    continue;
                }
                if (!CardData.hasAccount(card)) {
                    error(player, "msg.deepspacedeployer.bank_unbound_card");
                    return;
                }
                if (WalletService.isVoided(player.server, card)) {
                    error(player, "msg.deepspacedeployer.card_voided");
                    return;
                }
            }
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
        // 新挂失页只有一个卡槽：被挂失的卡必须真的在槽 0 里
        ItemStack slotCard = bank.getItem(0);
        if (slotCard.isEmpty() || !cardId.equals(CardData.cardId(slotCard))) {
            error(player, "msg.deepspacedeployer.bank_void_slot_empty");
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

    /**
     * 新挂失：卡槽里的卡是<b>接收余额的卡</b>；要挂失的是玩家选的“其他卡”（不在卡槽里）。
     *
     * <p>卡槽空 → 拒绝；要挂失的卡在卡槽里 → 拒绝；被挂失卡的 7 个账户全部转进卡槽卡。</p>
     */
    private static void voidSlot(ServerPlayer player, CurrencyBankPayload p, CurrencyBankBlockEntity bank) {
        int receiverSlot = p.from();
        if (receiverSlot < 0 || receiverSlot >= CurrencyBankBlockEntity.SLOTS) {
            error(player, "msg.deepspacedeployer.bank_bad_action");
            return;
        }
        ItemStack receiver = bank.getItem(receiverSlot);
        if (receiver.isEmpty()) {
            error(player, "msg.deepspacedeployer.bank_void_need_receiver");
            return;
        }
        if (WalletService.isVoided(player.server, receiver)) {
            error(player, "msg.deepspacedeployer.card_voided");
            return;
        }
        if (!CardData.isBoundTo(receiver, player)) {
            error(player, "msg.deepspacedeployer.card_not_yours");
            return;
        }
        UUID receiverAccount = CardData.accountId(receiver);
        UUID receiverCardId = CardData.cardId(receiver);
        if (receiverAccount == null || receiverCardId == null) {
            error(player, "msg.deepspacedeployer.no_account");
            return;
        }

        String raw = p.cardId() == null ? "" : p.cardId().trim();
        UUID lostId;
        try {
            lostId = raw.isEmpty() ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            lostId = null;
        }
        if (lostId == null) {
            error(player, "msg.deepspacedeployer.bank_no_card");
            return;
        }
        if (lostId.equals(receiverCardId)) {
            error(player, "msg.deepspacedeployer.bank_void_same_card");
            return;
        }
        for (int i = 0; i < CurrencyBankBlockEntity.SLOTS; i++) {
            if (lostId.equals(CardData.cardId(bank.getItem(i)))) {
                error(player, "msg.deepspacedeployer.bank_void_card_in_slot");
                return;
            }
        }

        CardRegistry registry = CardRegistry.get(player.server);
        UUID owner = registry.ownerOf(lostId);
        if (owner == null || !owner.equals(player.getUUID())) {
            error(player, "msg.deepspacedeployer.card_not_yours");
            return;
        }
        CardRegistry.CardRecord record = registry.find(lostId);
        if (record == null) {
            error(player, "msg.deepspacedeployer.bank_void_missing");
            return;
        }
        if (record.voided()) {
            error(player, "msg.deepspacedeployer.card_voided");
            return;
        }
        UUID lostAccount = record.account();
        if (lostAccount == null) {
            error(player, "msg.deepspacedeployer.no_account");
            return;
        }

        CompanyFunds funds = CompanyFunds.get(player.server);
        if (!lostAccount.equals(receiverAccount)) {
            long[] values = funds.snapshot(lostAccount);
            for (int i = 0; i < values.length; i++) {
                if (values[i] != 0L) {
                    funds.addSlot(receiverAccount, i, values[i]);
                }
            }
            funds.wipe(lostAccount);
        }

        if (registry.voidCard(lostId, player.server)) {
            success(player, "msg.deepspacedeployer.bank_void_to_slot_done");
            bank.sendNearby();
            sendCardList(player);
        } else {
            error(player, "msg.deepspacedeployer.bank_void_missing");
        }
    }

    // ---------------- 实体币存取 ----------------

    /** 存/取实体币用的账户卡：固定取 0 号卡槽，必须是本人有效卡。 */
    private static UUID cashAccount(ServerPlayer player, CurrencyBankBlockEntity bank) {
        ItemStack card = bank.getItem(0);
        if (card.isEmpty()) {
            error(player, "msg.deepspacedeployer.bank_cash_need_card");
            return null;
        }
        if (WalletService.isVoided(player.server, card)) {
            error(player, "msg.deepspacedeployer.card_voided");
            return null;
        }
        if (!CardData.isBoundTo(card, player)) {
            error(player, "msg.deepspacedeployer.card_not_yours");
            return null;
        }
        UUID account = CardData.accountId(card);
        if (account == null) {
            error(player, "msg.deepspacedeployer.no_account");
            return null;
        }
        return account;
    }

    /**
     * 存实体币：amount &gt; 0 = 只存选中的公司/面额；amount &lt;= 0 = 背包里所有实体币都存。
     */
    private static void depositCash(ServerPlayer player, CurrencyBankPayload p,
                                    CurrencyBankBlockEntity bank) {
        UUID account = cashAccount(player, bank);
        if (account == null) {
            return;
        }
        Inventory inventory = player.getInventory();
        CompanyFunds funds = CompanyFunds.get(player.server);

        if (p.amount() <= 0L) {
            int totalItems = 0;
            long totalValue = 0L;
            long[] perCompany = new long[Company.COUNT];
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                CurrencyItem currency = ModItems.currencyOf(stack);
                if (currency == null || stack.getCount() <= 0) {
                    continue;
                }
                int count = stack.getCount();
                long value = (long) count * currency.value();
                perCompany[currency.company().ordinal()] += value;
                totalItems += count;
                totalValue += value;
                inventory.removeItem(i, count);
            }
            if (totalItems <= 0) {
                error(player, "msg.deepspacedeployer.bank_cash_no_items");
                return;
            }
            for (Company company : Company.VALUES) {
                long value = perCompany[company.ordinal()];
                if (value != 0L) {
                    funds.add(account, company, value);
                }
            }
            success(player, "msg.deepspacedeployer.bank_cash_deposit_all", totalItems, totalValue);
        } else {
            int companyIndex = p.from();
            int denomIndex = p.to();
            if (companyIndex < 0 || companyIndex >= Company.COUNT
                    || denomIndex < 0 || denomIndex >= CurrencyDenomination.COUNT) {
                error(player, "msg.deepspacedeployer.bank_cash_bad_denom");
                return;
            }
            int count = (int) Math.min(100L, p.amount());
            if (count <= 0) {
                error(player, "msg.deepspacedeployer.bank_cash_invalid");
                return;
            }
            Company company = Company.byIndex(companyIndex);
            CurrencyDenomination denomination = CurrencyDenomination.byIndex(denomIndex);
            var item = ModItems.currencyItem(company, denomination);

            int remaining = count;
            for (int i = 0; i < inventory.getContainerSize() && remaining > 0; i++) {
                ItemStack stack = inventory.getItem(i);
                if (stack.isEmpty() || !stack.is(item)) {
                    continue;
                }
                int take = Math.min(remaining, stack.getCount());
                inventory.removeItem(i, take);
                remaining -= take;
            }
            int deposited = count - remaining;
            if (deposited <= 0) {
                error(player, "msg.deepspacedeployer.bank_cash_no_items");
                return;
            }
            long value = (long) deposited * denomination.value();
            funds.add(account, company, value);
            success(player, "msg.deepspacedeployer.bank_cash_deposit",
                    deposited, company.displayName(), denomination.value(), value);
        }

        bank.sendNearby();
        player.containerMenu.broadcastChanges();
        player.inventoryMenu.broadcastChanges();
    }

    /**
     * 取实体币：按选中的公司/面额发券；单次最多 100 张（一叠）。
     */
    private static void withdrawCash(ServerPlayer player, CurrencyBankPayload p,
                                     CurrencyBankBlockEntity bank) {
        UUID account = cashAccount(player, bank);
        if (account == null) {
            return;
        }
        int companyIndex = p.from();
        int denomIndex = p.to();
        if (companyIndex < 0 || companyIndex >= Company.COUNT
                || denomIndex < 0 || denomIndex >= CurrencyDenomination.COUNT) {
            error(player, "msg.deepspacedeployer.bank_cash_bad_denom");
            return;
        }
        int count = (int) Math.min(100L, p.amount());
        if (count <= 0) {
            error(player, "msg.deepspacedeployer.bank_cash_invalid");
            return;
        }
        Company company = Company.byIndex(companyIndex);
        CurrencyDenomination denomination = CurrencyDenomination.byIndex(denomIndex);
        long total = (long) count * denomination.value();
        CompanyFunds funds = CompanyFunds.get(player.server);
        if (funds.balanceSlot(account, company.ordinal()) < total) {
            error(player, "msg.deepspacedeployer.bank_cash_insufficient");
            return;
        }
        if (player.getInventory().getFreeSlot() < 0) {
            error(player, "msg.deepspacedeployer.bank_cash_no_space");
            return;
        }
        ItemStack cash = new ItemStack(ModItems.currencyItem(company, denomination), count);
        if (!player.getInventory().add(cash)) {
            error(player, "msg.deepspacedeployer.bank_cash_no_space");
            return;
        }
        funds.add(account, company, -total);
        success(player, "msg.deepspacedeployer.bank_cash_withdraw",
                count, company.displayName(), denomination.value());
        bank.sendNearby();
        player.containerMenu.broadcastChanges();
        player.inventoryMenu.broadcastChanges();
    }

    /** 把玩家名下的卡列表（带 7 账户/总资产）发给银行界面。 */
    public static void sendCardList(ServerPlayer player) {
        CardRegistry registry = CardRegistry.get(player.server);
        CompanyFunds funds = CompanyFunds.get(player.server);
        List<CardListPayload.CardEntry> list = new ArrayList<>();
        for (CardRegistry.CardRecord record : registry.cards(player.getUUID())) {
            long[] balances = new long[CompanyFunds.SLOT_COUNT];
            long total = 0L;
            if (!record.voided() && record.account() != null) {
                balances = funds.snapshot(record.account());
                total = funds.totalAssets(record.account());
            }
            list.add(new CardListPayload.CardEntry(
                    record.cardId().toString(), record.name(), record.voided(), balances, total));
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
