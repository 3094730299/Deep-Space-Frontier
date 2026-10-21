package com.deepspacedeployer.economy;

import com.deepspacedeployer.Company;
import com.deepspacedeployer.registry.ModItems;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.UUID;

/**
 * 银行卡数据（<b>卡的元数据</b>，不是余额本身）。
 *
 * <p>老板 2026-10-19 的定稿：<b>一张卡 = 一个账户指针</b>。
 * 卡上只记：</p>
 * <ul>
 *   <li>{@link #ACCOUNT}：账户 UUID（余额在 {@link CompanyFunds} 里按它存 7 个槽位）；</li>
 *   <li>{@link #CARD_ID}：这张卡自己的唯一 id（{@link CardRegistry} 用）；</li>
 *   <li>{@link #ACCOUNT_NAME}：卡主显示名；</li>
 *   <li>物品的 {@code CUSTOM_NAME}：卡名（给玩家区分多张卡）。</li>
 * </ul>
 *
 * <p>旧版卡上直接存了"便携余额"（6 家公司各一份）；新系统里这些钱在
 * {@link #migrateToAccount} 里一次性搬进账户，之后卡上不再放钱。
 * 老的 {@link #getBalance} / {@link #addBalance} 只留给还没删掉的旧「资金转化」界面，
 * <b>新代码不要再用</b>。</p>
 */
public final class CardData {

    private static final String ROOT = "DSD_Card";
    private static final String ACCOUNT = "Account";
    private static final String ACCOUNT_NAME = "AccountName";
    private static final String CARD_ID = "CardId";
    /** 旧版卡上的便携余额（6 家公司各一份）；只用于迁移/旧界面 */
    private static final String BALANCES = "Balances";

    public static boolean isCard(ItemStack stack) {
        return ModItems.isBankCard(stack);
    }

    private static CompoundTag readRoot(ItemStack card) {
        CustomData data = card.get(DataComponents.CUSTOM_DATA);
        return data == null ? new CompoundTag() : data.copyTag();
    }

    private static void writeRoot(ItemStack card, CompoundTag tag) {
        card.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    // ---------------- 账户绑定 ----------------

    public static boolean hasAccount(ItemStack card) {
        return !readRoot(card).getString(ACCOUNT).isEmpty();
    }

    /** 卡主名字，没绑定时返回空字符串 */
    public static String accountName(ItemStack card) {
        return readRoot(card).getString(ACCOUNT_NAME);
    }

    /** 卡主 UUID，没绑定时返回 null */
    public static UUID accountUuid(ItemStack card) {
        String raw = readRoot(card).getString(ACCOUNT);
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static void setAccount(ItemStack card, UUID uuid, String name) {
        if (!isCard(card)) {
            return;
        }
        CompoundTag root = readRoot(card);
        root.putString(ACCOUNT, uuid.toString());
        root.putString(ACCOUNT_NAME, name == null ? "" : name);
        writeRoot(card, root);
    }

    public static void clearAccount(ItemStack card) {
        if (!isCard(card)) {
            return;
        }
        CompoundTag root = readRoot(card);
        root.remove(ACCOUNT);
        root.remove(ACCOUNT_NAME);
        writeRoot(card, root);
    }

    /** 一张卡 = 一个账户；现在不再有"副卡独立账户" */
    public static UUID ownerAccount(ItemStack card) {
        return accountUuid(card);
    }

    public static UUID accountId(ItemStack card) {
        return accountUuid(card);
    }

    /** 没绑定就绑到当前玩家（第一次插卡时的默认行为） */
    public static void bindIfMissing(ItemStack card, Player player) {
        if (!isCard(card) || hasAccount(card)) {
            return;
        }
        setAccount(card, player.getUUID(), player.getGameProfile().getName());
    }

    public static boolean isBoundTo(ItemStack card, Player player) {
        UUID uuid = accountUuid(card);
        return uuid != null && uuid.equals(player.getUUID());
    }

    // ---------------- 卡 id（CardRegistry 用） ----------------

    /** 这张卡的唯一 id；老卡还没分配时返回 null */
    public static UUID cardId(ItemStack card) {
        if (!isCard(card)) {
            return null;
        }
        String raw = readRoot(card).getString(CARD_ID);
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 老卡第一次进系统时分配一个卡 id；已经有的直接返回 */
    public static UUID ensureCardId(ItemStack card) {
        UUID id = cardId(card);
        if (id != null) {
            return id;
        }
        if (!isCard(card)) {
            return null;
        }
        id = UUID.randomUUID();
        CompoundTag root = readRoot(card);
        root.putString(CARD_ID, id.toString());
        writeRoot(card, root);
        return id;
    }

    // ---------------- 卡名（给卡起个名字，多人时好用） ----------------

    public static String cardLabel(ItemStack card) {
        Component name = card.get(DataComponents.CUSTOM_NAME);
        return name == null ? "" : name.getString();
    }

    public static void setCardLabel(ItemStack card, String label) {
        if (!isCard(card)) {
            return;
        }
        if (label == null || label.isEmpty()) {
            card.remove(DataComponents.CUSTOM_NAME);
        } else {
            card.set(DataComponents.CUSTOM_NAME, Component.literal(label));
        }
    }

    // ---------------- 旧卡余额 → 账户（一次性迁移） ----------------

    /**
     * 把旧版卡物品 NBT 里的 6 家公司余额搬进 {@link CompanyFunds} 账户，然后清掉。
     * 幂等：没有余额字段时什么也不做。
     */
    public static void migrateToAccount(ItemStack card, CompanyFunds funds, UUID account) {
        if (!isCard(card) || funds == null || account == null) {
            return;
        }
        CompoundTag root = readRoot(card);
        if (!root.contains(BALANCES, Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag balances = root.getCompound(BALANCES);
        for (Company company : Company.VALUES) {
            long value = balances.getLong(company.id());
            if (value > 0L) {
                funds.add(account, company, value);
            }
        }
        root.remove(BALANCES);
        writeRoot(card, root);
    }

    // ---------------- 兼容：旧「资金转化」界面读的卡上便携余额 ----------------

    private static CompoundTag balancesOf(CompoundTag root) {
        if (root.contains(BALANCES, Tag.TAG_COMPOUND)) {
            return root.getCompound(BALANCES);
        }
        CompoundTag created = new CompoundTag();
        root.put(BALANCES, created);
        return created;
    }

    /** @deprecated 新代码用 {@link CompanyFunds#balance}；这里只给未删的旧界面用 */
    @Deprecated
    public static long getBalance(ItemStack card, Company company) {
        if (!isCard(card)) {
            return 0L;
        }
        return balancesOf(readRoot(card)).getLong(company.id());
    }

    /** @deprecated 新代码用 {@link CompanyFunds#add} */
    @Deprecated
    public static void setBalance(ItemStack card, Company company, long value) {
        if (!isCard(card)) {
            return;
        }
        CompoundTag root = readRoot(card);
        balancesOf(root).putLong(company.id(), Math.max(0L, value));
        writeRoot(card, root);
    }

    /** @deprecated 新代码用 {@link CompanyFunds#add} */
    @Deprecated
    public static long addBalance(ItemStack card, Company company, long delta) {
        long now = getBalance(card, company) + delta;
        setBalance(card, company, now);
        return Math.max(0L, now);
    }

    /** @deprecated 新代码用 {@link CompanyFunds#snapshot} */
    @Deprecated
    public static long[] getBalances(ItemStack card) {
        long[] out = new long[Company.COUNT];
        if (!isCard(card)) {
            return out;
        }
        CompoundTag balances = balancesOf(readRoot(card));
        for (Company company : Company.VALUES) {
            out[company.ordinal()] = balances.getLong(company.id());
        }
        return out;
    }

    /** @deprecated 新代码用 {@link CompanyFunds#addSlot} */
    @Deprecated
    public static void setBalances(ItemStack card, long[] values) {
        if (!isCard(card)) {
            return;
        }
        CompoundTag root = readRoot(card);
        CompoundTag balances = balancesOf(root);
        for (Company company : Company.VALUES) {
            balances.putLong(company.id(), Math.max(0L, values[company.ordinal()]));
        }
        writeRoot(card, root);
    }

    private CardData() {
    }
}
