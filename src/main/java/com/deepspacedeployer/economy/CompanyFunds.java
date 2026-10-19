package com.deepspacedeployer.economy;

import com.deepspacedeployer.Company;
import com.deepspacedeployer.Config;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 账户余额（世界级存档）：<b>每个账户 UUID × 7 个币种一个余额，全图通用</b>。
 *
 * <p><b>7 个槽位</b>（老板 2026-10-19 的钱包设计）：</p>
 * <ol>
 *   <li>槽 0~5 = 六家公司（顺序就是 {@link Company#ordinal()}）；</li>
 *   <li>槽 {@link #GENERIC_SLOT} = <b>通用货币</b>（没有实体形态、只有数字）。</li>
 * </ol>
 *
 * <p><b>键是什么</b>：以前这里是"玩家 UUID"，现在按老板的设计改成 <b>账户 UUID</b>
 * （{@link CardData#accountId} 那个）—— 这样一张卡 = 一个账户 = 7 个余额，
 * 卡被销毁/挂失时账户可以独立存在或一并清掉。</p>
 *
 * <p>⚠️ 兼容性：老的存档里键是玩家 UUID、只有 6 个槽位。
 * {@link #load} 会把缺失的通用槽补 0，所以<b>老存档能直接读</b>。</p>
 */
public class CompanyFunds extends SavedData {

    public static final String FILE_NAME = "deepspacedeployer_funds";

    /** 通用货币的槽位号（= 六家公司之后的那一格） */
    public static final int GENERIC_SLOT = Company.COUNT;

    /** 槽位总数 = 6 家公司 + 1 通用 */
    public static final int SLOT_COUNT = Company.COUNT + 1;

    /** 通用货币在存档里的键名（不要用 Company.id()，避免撞名） */
    private static final String KEY_GENERIC = "general";

    /** 账户 UUID → 7 个币种的余额 */
    private final Map<UUID, long[]> balances = new HashMap<>();

    public static CompanyFunds get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(CompanyFunds::new, CompanyFunds::load, null),
                FILE_NAME);
    }

    public static CompanyFunds load(CompoundTag tag, HolderLookup.Provider registries) {
        CompanyFunds data = new CompanyFunds();
        CompoundTag players = tag.getCompound("Players");
        for (String key : players.getAllKeys()) {
            try {
                UUID uuid = UUID.fromString(key);
                long[] values = new long[SLOT_COUNT];
                CompoundTag entry = players.getCompound(key);
                for (Company company : Company.VALUES) {
                    values[company.ordinal()] = Math.max(0L, entry.getLong(company.id()));
                }
                // 老存档没有这一项 → 读成 0，等于"通用钱包是空的"
                values[GENERIC_SLOT] = Math.max(0L, entry.getLong(KEY_GENERIC));
                data.balances.put(uuid, values);
            } catch (IllegalArgumentException ignored) {
                // 坏掉的键直接跳过
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag players = new CompoundTag();
        for (Map.Entry<UUID, long[]> entry : balances.entrySet()) {
            CompoundTag row = new CompoundTag();
            boolean any = false;
            for (Company company : Company.VALUES) {
                long value = entry.getValue()[company.ordinal()];
                if (value != 0L) {
                    row.putLong(company.id(), value);
                    any = true;
                }
            }
            long generic = entry.getValue()[GENERIC_SLOT];
            if (generic != 0L) {
                row.putLong(KEY_GENERIC, generic);
                any = true;
            }
            if (any) {
                players.put(entry.getKey().toString(), row);
            }
        }
        tag.put("Players", players);
        return tag;
    }

    // ---------------- 读 ----------------

    /** 某个账户在某家公司的余额 */
    public long balance(UUID account, Company company) {
        return balanceSlot(account, company.ordinal());
    }

    /** 某个账户的通用货币余额 */
    public long generic(UUID account) {
        return balanceSlot(account, GENERIC_SLOT);
    }

    /** 按槽位取余额（0~5 = 公司，6 = 通用） */
    public long balanceSlot(UUID account, int slot) {
        if (account == null || slot < 0 || slot >= SLOT_COUNT) {
            return 0L;
        }
        long[] values = balances.get(account);
        return values == null ? 0L : values[slot];
    }

    /** 7 个槽位全取出来（给界面用；返回的是副本，改它不影响存档） */
    public long[] snapshot(UUID account) {
        long[] values = balances.get(account);
        return values == null ? new long[SLOT_COUNT] : values.clone();
    }

    /** 7 个槽位之和（不再按税率折算，纯粹"名义上共有多少钱"） */
    public long total(UUID account) {
        long[] values = balances.get(account);
        if (values == null) {
            return 0L;
        }
        long sum = 0L;
        for (long value : values) {
            sum += value;
        }
        return sum;
    }

    /**
     * <b>总资产</b>（老板 2026-10-19 的定义）：
     * <b>六家公司余额各扣 30% 税后 + 通用货币</b>。
     *
     * <p>含义：公司货币想变成能自由用的通用货币要交 30%，所以"折算成通用"时
     * 公司余额只值七成。界面上的"总资产"显示的就是这个数。</p>
     */
    public long totalAssets(UUID account) {
        long[] values = balances.get(account);
        if (values == null) {
            return 0L;
        }
        long sum = 0L;
        for (Company company : Company.VALUES) {
            sum += values[company.ordinal()] * (100 - TAX_COMPANY_TO_GENERIC) / 100;
        }
        sum += values[GENERIC_SLOT];
        return sum;
    }

    /** 公司货币 → 通用货币的税率（%）—— 与货币银行一致，改这里就全改 */
    public static final int TAX_COMPANY_TO_GENERIC = 30;

    // ---------------- 写 ----------------

    /** 加减某家公司的余额（不会低于 0）；返回加完之后的余额 */
    public long add(UUID account, Company company, long delta) {
        return addSlot(account, company.ordinal(), delta);
    }

    /** 加减通用货币；返回加完之后的余额 */
    public long addGeneric(UUID account, long delta) {
        return addSlot(account, GENERIC_SLOT, delta);
    }

    /** 按槽位加减（不会低于 0）；返回加完之后的余额 */
    public long addSlot(UUID account, int slot, long delta) {
        if (account == null || slot < 0 || slot >= SLOT_COUNT) {
            return 0L;
        }
        if (delta == 0L) {
            return balanceSlot(account, slot);
        }
        long[] values = balances.computeIfAbsent(account, ignored -> new long[SLOT_COUNT]);
        long now = Math.max(0L, values[slot] + delta);
        values[slot] = now;
        setDirty();
        return now;
    }

    /** 这个账户是不是 7 个槽位全空（卡的空卡判定用得到） */
    public boolean isEmpty(UUID account) {
        long[] values = balances.get(account);
        if (values == null) {
            return true;
        }
        for (long value : values) {
            if (value != 0L) {
                return false;
            }
        }
        return true;
    }

    /** 清空某个账户的全部余额（挂失用） */
    public void wipe(UUID account) {
        if (account != null && balances.remove(account) != null) {
            setDirty();
        }
    }

    /** 声望换算用的"累计消费"仍然记在 {@link ReputationData} 里，这里只放余额 */
    public static int reputationDivisor() {
        return Math.max(1, Config.REPUTATION_PER_1000_SPENT.get());
    }
}
