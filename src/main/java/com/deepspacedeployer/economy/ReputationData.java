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
 * 声望数据：每个玩家在每家公司的累计消费，存在世界存档里（多人服务器共用）。
 *
 * <p>声望 = 累计消费 ÷ 配置里的 {@code scrip_per_reputation_point}（默认 1000）。</p>
 */
public class ReputationData extends SavedData {

    public static final String FILE_NAME = "deepspacedeployer_reputation";

    /** 玩家 UUID → 六家公司各自的累计消费 */
    private final Map<UUID, long[]> spent = new HashMap<>();

    public static ReputationData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(ReputationData::new, ReputationData::load, null),
                FILE_NAME);
    }

    public static ReputationData load(CompoundTag tag, HolderLookup.Provider registries) {
        ReputationData data = new ReputationData();
        CompoundTag players = tag.getCompound("Players");
        for (String key : players.getAllKeys()) {
            try {
                UUID uuid = UUID.fromString(key);
                long[] values = new long[Company.COUNT];
                CompoundTag entry = players.getCompound(key);
                for (Company company : Company.VALUES) {
                    values[company.ordinal()] = entry.getLong(company.id());
                }
                data.spent.put(uuid, values);
            } catch (IllegalArgumentException ignored) {
                // 坏数据跳过
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag players = new CompoundTag();
        for (Map.Entry<UUID, long[]> entry : spent.entrySet()) {
            CompoundTag row = new CompoundTag();
            for (Company company : Company.VALUES) {
                long value = entry.getValue()[company.ordinal()];
                if (value != 0L) {
                    row.putLong(company.id(), value);
                }
            }
            players.put(entry.getKey().toString(), row);
        }
        tag.put("Players", players);
        return tag;
    }

    public long spent(UUID player, Company company) {
        long[] values = spent.get(player);
        return values == null ? 0L : values[company.ordinal()];
    }

    /** 声望点数（累计消费换来的） */
    public int reputation(UUID player, Company company) {
        int perPoint = Math.max(1, Config.REPUTATION_PER_1000_SPENT.get());
        return (int) (spent(player, company) / perPoint);
    }

    public void addSpent(UUID player, Company company, long amount) {
        if (amount <= 0L) {
            return;
        }
        long[] values = spent.computeIfAbsent(player, ignored -> new long[Company.COUNT]);
        values[company.ordinal()] += amount;
        setDirty();
    }
}
