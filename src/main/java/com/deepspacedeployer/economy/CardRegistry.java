package com.deepspacedeployer.economy;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 银行卡注册表（世界级存档）—— <b>"谁有几张卡、叫什么名、是不是废卡、空卡多久了"</b>。
 *
 * <p>老板 2026-10-19 定的规则，这个类就是它们的落点：</p>
 * <ol>
 *   <li><b>一个玩家最多 2 张卡</b>（{@link #MAX_CARDS_PER_PLAYER}）；
 *       <b>只有挂失才会减少数量</b> —— 所以"数卡"只数<b>没被挂失</b>的；</li>
 *   <li><b>同一个玩家不能有两张同名卡</b>（不同玩家可以同名）；</li>
 *   <li><b>空卡在游戏内 30 天后自动按"第三张卡"处理</b>（即自动挂失 →
 *       账户清空、卡变废卡 {@code §7§l废卡}），见 {@link #EMPTY_DAYS_BEFORE_VOID}；</li>
 *   <li>卡就算被放进箱子，也记在这里（所以"数量上限"是跟玩家走的，不是跟背包走的）。</li>
 * </ol>
 *
 * <p>⚠️ <b>为什么要有这个类</b>：光靠卡物品本身记不住"这个玩家已经有两张了"
 * （卡可能在别人的箱子里、可能掉在地上）。有了注册表，判定和自动挂失都不用去搜世界。</p>
 *
 * <p>⚠️ 卡物品只是"把手"：真正的余额在 {@link CompanyFunds}（按账户 UUID 存），
 * 这里的 {@code account} 就是那个 UUID。挂失 = 清账户 + 把记录标成废卡。</p>
 */
public class CardRegistry extends SavedData {

    public static final String FILE_NAME = "deepspacedeployer_cards";

    /** 一个玩家最多持有几张（老板：2 张，只有挂失能减） */
    public static final int MAX_CARDS_PER_PLAYER = 2;

    /** 空卡多少游戏日后自动挂失（老板：30） */
    public static final int EMPTY_DAYS_BEFORE_VOID = 30;

    /** 一个游戏日 = 24000 tick */
    public static final long TICKS_PER_DAY = 24000L;

    /**
     * 一张卡的记录。
     *
     * @param cardId        卡的唯一 id（卡物品自己带着，换手也不会变）
     * @param account       账户 UUID（余额在 {@link CompanyFunds} 里按它存）
     * @param name          卡名（同一玩家内唯一，最多 15 字）
     * @param voided        是否已被挂失/自动挂失（废卡：清空数据、只作装饰）
     * @param emptySinceDay 从哪个游戏日开始"7 个账户全空"（-1 = 现在不空）
     */
    public record CardRecord(UUID cardId, UUID account, String name, boolean voided, long emptySinceDay) {
        public CardRecord withName(String newName) {
            return new CardRecord(cardId, account, newName, voided, emptySinceDay);
        }

        public CardRecord withVoided(boolean v) {
            return new CardRecord(cardId, account, name, v, emptySinceDay);
        }

        public CardRecord withEmptySince(long day) {
            return new CardRecord(cardId, account, name, voided, day);
        }
    }

    /** 卡主 UUID → 他的卡（含废卡，废卡只作展示/占位） */
    private final Map<UUID, List<CardRecord>> byOwner = new HashMap<>();
    /** 卡 id → 卡主 UUID（反查：手持卡打开界面时快速找到记录） */
    private final Map<UUID, UUID> ownerOf = new HashMap<>();

    public static CardRegistry get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(CardRegistry::new, CardRegistry::load, null),
                FILE_NAME);
    }

    /** 当前游戏日（天数，从世界创建算起） */
    public static long gameDay(MinecraftServer server) {
        return server.overworld().getGameTime() / TICKS_PER_DAY;
    }

    // ---------------- 存档 ----------------

    public static CardRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        CardRegistry data = new CardRegistry();
        ListTag list = tag.getList("Cards", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            try {
                UUID owner = UUID.fromString(e.getString("Owner"));
                CardRecord rec = new CardRecord(
                        UUID.fromString(e.getString("CardId")),
                        UUID.fromString(e.getString("Account")),
                        e.getString("Name"),
                        e.getBoolean("Voided"),
                        e.contains("EmptySince") ? e.getLong("EmptySince") : -1L);
                data.byOwner.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(rec);
                data.ownerOf.put(rec.cardId(), owner);
            } catch (IllegalArgumentException ignored) {
                // 坏记录跳过（不让一条脏数据毁掉整个存档）
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, List<CardRecord>> entry : byOwner.entrySet()) {
            for (CardRecord rec : entry.getValue()) {
                CompoundTag e = new CompoundTag();
                e.putString("Owner", entry.getKey().toString());
                e.putString("CardId", rec.cardId().toString());
                e.putString("Account", rec.account().toString());
                e.putString("Name", rec.name());
                e.putBoolean("Voided", rec.voided());
                if (rec.emptySinceDay() >= 0) {
                    e.putLong("EmptySince", rec.emptySinceDay());
                }
                list.add(e);
            }
        }
        tag.put("Cards", list);
        return tag;
    }

    // ---------------- 查询 ----------------

    public List<CardRecord> cards(UUID owner) {
        return byOwner.getOrDefault(owner, List.of());
    }

    /** 还没挂失的卡（= 占名额的那些） */
    public List<CardRecord> activeCards(UUID owner) {
        List<CardRecord> out = new ArrayList<>();
        for (CardRecord rec : cards(owner)) {
            if (!rec.voided()) {
                out.add(rec);
            }
        }
        return out;
    }

    /** 还能不能再领一张（老板：最多 2 张，只有挂失能减） */
    public boolean canAdd(UUID owner) {
        return activeCards(owner).size() < MAX_CARDS_PER_PLAYER;
    }

    /** 同一玩家内卡名是否已被占用（废卡不算，因为它已经没数据了） */
    public boolean nameTaken(UUID owner, String name) {
        for (CardRecord rec : cards(owner)) {
            if (!rec.voided() && rec.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** 按卡 id 找记录（手持卡开界面用） */
    public CardRecord find(UUID cardId) {
        UUID owner = ownerOf.get(cardId);
        if (owner == null) {
            return null;
        }
        for (CardRecord rec : cards(owner)) {
            if (rec.cardId().equals(cardId)) {
                return rec;
            }
        }
        return null;
    }

    public UUID ownerOf(UUID cardId) {
        return ownerOf.get(cardId);
    }

    // ---------------- 变更 ----------------

    /** 登记一张新卡；超过上限或重名返回 false */
    public boolean add(UUID owner, UUID cardId, UUID account, String name) {
        if (owner == null || cardId == null || account == null) {
            return false;
        }
        if (find(cardId) != null) {
            return false;                       // 已经登记过了
        }
        if (!canAdd(owner) || nameTaken(owner, name)) {
            return false;
        }
        byOwner.computeIfAbsent(owner, ignored -> new ArrayList<>())
                .add(new CardRecord(cardId, account, name, false, -1L));
        ownerOf.put(cardId, owner);
        setDirty();
        return true;
    }

    /** 改名（重名/废卡/找不到都会失败） */
    public boolean rename(UUID cardId, String newName) {
        UUID owner = ownerOf.get(cardId);
        CardRecord rec = find(cardId);
        if (owner == null || rec == null || rec.voided()) {
            return false;
        }
        if (!rec.name().equals(newName) && nameTaken(owner, newName)) {
            return false;
        }
        replace(owner, rec.withName(newName));
        return true;
    }

    /**
     * 挂失：清掉账户余额、把记录标成废卡（卡物品还在，变成装饰品）。
     *
     * @return 是否真的挂失了（已经是废卡/找不到 → false）
     */
    public boolean voidCard(UUID cardId, MinecraftServer server) {
        UUID owner = ownerOf.get(cardId);
        CardRecord rec = find(cardId);
        if (owner == null || rec == null || rec.voided()) {
            return false;
        }
        CompanyFunds.get(server).wipe(rec.account());     // 余额一并清掉（老板：挂失清数据）
        replace(owner, rec.withVoided(true).withEmptySince(-1L));
        return true;
    }

    /** 记录"从今天起它是空卡"（已经是空卡就不重置计时） */
    public void markEmpty(UUID cardId, long day) {
        UUID owner = ownerOf.get(cardId);
        CardRecord rec = find(cardId);
        if (owner == null || rec == null || rec.voided() || rec.emptySinceDay() >= 0) {
            return;
        }
        replace(owner, rec.withEmptySince(day));
    }

    /** 卡里有钱了 → 清掉空卡计时 */
    public void markFunded(UUID cardId) {
        UUID owner = ownerOf.get(cardId);
        CardRecord rec = find(cardId);
        if (owner == null || rec == null || rec.emptySinceDay() < 0) {
            return;
        }
        replace(owner, rec.withEmptySince(-1L));
    }

    /**
     * 扫一遍：把"空满 30 游戏日"的卡自动挂失。
     *
     * <p>由 {@code DeepSpaceDeployer} 的服务器 tick 每 200 tick 调一次
     * （≈10 秒，30 天这种量级根本不需要每 tick 扫）。</p>
     *
     * @return 这次自动挂失了几张
     */
    public int sweepExpired(MinecraftServer server, long day) {
        int voided = 0;
        for (UUID owner : new ArrayList<>(byOwner.keySet())) {
            for (CardRecord rec : new ArrayList<>(cards(owner))) {
                if (rec.voided() || rec.emptySinceDay() < 0) {
                    continue;
                }
                if (day - rec.emptySinceDay() >= EMPTY_DAYS_BEFORE_VOID) {
                    if (voidCard(rec.cardId(), server)) {
                        voided++;
                    }
                }
            }
        }
        return voided;
    }

    private void replace(UUID owner, CardRecord updated) {
        List<CardRecord> list = byOwner.get(owner);
        if (list == null) {
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).cardId().equals(updated.cardId())) {
                list.set(i, updated);
                setDirty();
                return;
            }
        }
    }
}
