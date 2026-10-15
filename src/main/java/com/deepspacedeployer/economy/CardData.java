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

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 银行卡数据。
 *
 * <p>一张卡 = 一个「账户指针」：绑定某个玩家（UUID + 名字）+ 卡上的便携余额（六家公司各一份）。</p>
 *
 * <p>几条关键规则（老板定的）：</p>
 * <ul>
 *   <li>右键银行卡可以填写玩家名/UUID 把卡绑到那个玩家的账户</li>
 *   <li>卡插进商店方块后，方块里那个账户的余额<b>优先</b>被花掉</li>
 *   <li>卡留在方块里没取走时，<b>其他玩家也能用</b>这张卡花卡主的钱买东西</li>
 * </ul>
 */
public final class CardData {

    private static final String ROOT = "DSD_Card";
    private static final String ACCOUNT = "Account";
    private static final String ACCOUNT_NAME = "AccountName";
    private static final String BALANCES = "Balances";
    /** 副卡自己的账户池编号（主卡不写这个字段） */
    private static final String ACCOUNT_ID = "AccountId";
    /** 副卡序号：同一张主卡名下可以有 副卡1 / 副卡2 …（决定 AccountId） */
    private static final String SUB_INDEX = "SubIndex";

    public static boolean isCard(ItemStack stack) {
        return ModItems.isBankCard(stack);
    }

    public static boolean isSubCard(ItemStack stack) {
        return ModItems.isSubCard(stack);
    }

    private static CompoundTag readRoot(ItemStack card) {
        CustomData data = card.get(DataComponents.CUSTOM_DATA);
        return data == null ? new CompoundTag() : data.copyTag();
    }

    private static void writeRoot(ItemStack card, CompoundTag tag) {
        card.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    private static CompoundTag balancesOf(CompoundTag root) {
        if (root.contains(BALANCES, Tag.TAG_COMPOUND)) {
            return root.getCompound(BALANCES);
        }
        CompoundTag created = new CompoundTag();
        root.put(BALANCES, created);
        return created;
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

    // ---------------- 账户池：主账户 / 副卡独立账户 ----------------

    /**
     * <b>主账户</b>（也就是"卡主在这家公司的钱"）：永远是卡主的 UUID。
     *
     * <p>花钱、收购收入、声望都走这个 —— 副卡也不例外，
     * 这样副卡插进机器时才有钱可花、也才收得到收购款。</p>
     */
    public static UUID ownerAccount(ItemStack card) {
        return accountUuid(card);
    }

    /**
     * <b>这张卡指向的账户池</b>：主卡 = 主账户；副卡 = 它自己那个独立账户。
     *
     * <p>副卡的独立账户用"卡主 UUID + 序号"合成一个确定性 UUID（见 {@link #subAccount}），
     * 所以不需要额外存表，存档里那套"UUID → 六家公司余额"直接复用。</p>
     */
    public static UUID accountId(ItemStack card) {
        if (!isSubCard(card)) {
            return accountUuid(card);
        }
        UUID owner = accountUuid(card);
        if (owner == null) {
            return null;
        }
        return subAccount(owner, subIndex(card));
    }

    /** 副卡的独立账户：由"卡主 UUID + 序号"确定性地合成一个 UUID */
    public static UUID subAccount(UUID owner, int index) {
        return UUID.nameUUIDFromBytes(("dsd-sub-card:" + owner + ":" + index).getBytes(StandardCharsets.UTF_8));
    }

    /** 副卡序号（没写过就是 1） */
    public static int subIndex(ItemStack card) {
        int raw = readRoot(card).getInt(SUB_INDEX);
        return raw <= 0 ? 1 : raw;
    }

    /** 给副卡分配一个序号（做成物品时/第一次绑定账户时用） */
    public static void setSubIndex(ItemStack card, int index) {
        if (!isCard(card) || !isSubCard(card)) {
            return;
        }
        CompoundTag root = readRoot(card);
        root.putInt(SUB_INDEX, Math.max(1, index));
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

    /** 没绑定就绑到当前玩家（第一次插卡时的默认行为）；副卡顺带分配一个账号序号 */
    public static void bindIfMissing(ItemStack card, Player player) {
        if (!isCard(card)) {
            return;
        }
        if (hasAccount(card)) {
            // 已经绑定过的副卡：保证序号存在（老版本做出来的副卡可能没有这个字段）
            if (isSubCard(card) && readRoot(card).getInt(SUB_INDEX) <= 0) {
                setSubIndex(card, 1);
            }
            return;
        }
        setAccount(card, player.getUUID(), player.getGameProfile().getName());
        if (isSubCard(card)) {
            // 第 N 张副卡 → 序号 N（决定它那个独立账户池是哪一个）
            setSubIndex(card, 1 + countSubCards(player));
        }
    }

    /** 这个玩家背包里已经有的副卡数量（不算手上这张还没绑的） */
    private static int countSubCards(Player player) {
        int count = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (isSubCard(stack) && hasAccount(stack) && player.getUUID().equals(accountUuid(stack))) {
                count++;
            }
        }
        return count;
    }

    public static boolean isBoundTo(ItemStack card, Player player) {
        UUID uuid = accountUuid(card);
        return uuid != null && uuid.equals(player.getUUID());
    }

    // ---------------- 卡名（给卡起个名字，多人时好用） ----------------

    /** 卡名（没起名返回空字符串） */
    public static String cardLabel(ItemStack card) {
        Component name = card.get(DataComponents.CUSTOM_NAME);
        return name == null ? "" : name.getString();
    }

    /** 设置卡名；传空字符串表示恢复默认名字 */
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

    // ---------------- 卡自己的余额池（六家公司各一份）----------------
    //
    // 注意：这里只读写"这张卡物品自己的"那份余额（副卡就是副卡独立的那份）。
    // 主卡在机器里花钱时用的是"卡主的主账户"（见 ownerAccount / accountId 的说明），
    // 所以这两套数据是分开的：卡上的那份是"便携余额"，世界存档里的是"公司账户"。

    public static long getBalance(ItemStack card, Company company) {
        if (!isCard(card)) {
            return 0L;
        }
        return balancesOf(readRoot(card)).getLong(company.id());
    }

    public static void setBalance(ItemStack card, Company company, long value) {
        if (!isCard(card)) {
            return;
        }
        CompoundTag root = readRoot(card);
        balancesOf(root).putLong(company.id(), Math.max(0L, value));
        writeRoot(card, root);
    }

    public static long addBalance(ItemStack card, Company company, long delta) {
        long now = getBalance(card, company) + delta;
        setBalance(card, company, now);
        return Math.max(0L, now);
    }

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
