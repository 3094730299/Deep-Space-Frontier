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
 * 公司账户（世界级存档）：<b>每个玩家 × 每家公司一个余额，全图通用</b>。
 *
 * <p>为什么改成这样：以前余额存在方块实体的 NBT 里，导致"同一个公司在 A 厂的存款和 B 厂不通用"，
 * 玩家把机器一拆/一搬就得重新存钱，很不合理。现在改成世界级存档：
 * 只要是<b>同一家公司</b>的方块（商店 / 工厂 / 焊接 / 收购），余额都通用。</p>
 *
 * <p>注意：钱存在这个世界里，和"银行卡"是两套：
 * 公司账户 = 你在这家公司的存款（可存可取自该公司任何方块），卡上余额 = 跟着卡走的便携余额。</p>
 */
public class CompanyFunds extends SavedData {

    public static final String FILE_NAME = "deepspacedeployer_funds";

    /** 玩家 UUID → 六家公司各自的余额 */
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
                long[] values = new long[Company.COUNT];
                CompoundTag entry = players.getCompound(key);
                for (Company company : Company.VALUES) {
                    values[company.ordinal()] = Math.max(0L, entry.getLong(company.id()));
                }
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
            if (any) {
                players.put(entry.getKey().toString(), row);
            }
        }
        tag.put("Players", players);
        return tag;
    }

    public long balance(UUID player, Company company) {
        if (player == null) {
            return 0L;
        }
        long[] values = balances.get(player);
        return values == null ? 0L : values[company.ordinal()];
    }

    /** 加减余额（不会低于 0）；返回加完之后的余额 */
    public long add(UUID player, Company company, long delta) {
        if (player == null || delta == 0L) {
            return balance(player, company);
        }
        long[] values = balances.computeIfAbsent(player, ignored -> new long[Company.COUNT]);
        long now = Math.max(0L, values[company.ordinal()] + delta);
        values[company.ordinal()] = now;
        setDirty();
        return now;
    }

    /** 六个公司的余额之和（目前没有 UI 用，留着方便以后做总览） */
    public long total(UUID player) {
        long[] values = balances.get(player);
        if (values == null) {
            return 0L;
        }
        long sum = 0L;
        for (long value : values) {
            sum += value;
        }
        return sum;
    }

    /** 声望换算用的"累计消费"仍然记在 {@link ReputationData} 里，这里只放余额 */
    public static int reputationDivisor() {
        return Math.max(1, Config.REPUTATION_PER_1000_SPENT.get());
    }
}
