package com.deepspacedeployer.market;

import com.deepspacedeployer.DeepSpaceDeployer;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;

/**
 * 寄售市场的挂单存档（<b>整个世界一份</b>，所有市场方块共享）。
 *
 * <p>挂在世界存档里（SavedData），所以拆掉市场方块、换个地方再放，挂单都还在，
 * 也不会因为哪台方块被挖了就丢单。</p>
 */
public final class MarketData extends SavedData {

    private static final String FILE_ID = DeepSpaceDeployer.MOD_ID + "_market";

    private final List<Listing> listings = new ArrayList<>();
    private long nextId = 1L;

    /**
     * ⚠️ 这里**故意用匿名类**，不要写成 {@code new SavedData.Factory<>(MarketData::new, MarketData::load)}。
     *
     * <p>踩过的坑：写成方法引用时，{@code MarketData::new} / {@code MarketData::load} 会在
     * <b>MarketData 自己的常量池里</b>生成一个 {@code invokedynamic} 调用点；
     * 被 NeoForge 的 class transformer 改写之后解析失败，表现为
     * {@code NoClassDefFoundError: com/deepspacedeployer/market/MarketData}
     * （jar 里明明有这个类，字节码也是好的）—— 一打开市场界面就把服务器线程打崩。
     * 换成匿名类之后常量池里只剩普通的 new/invokestatic，就没事了。
     * 对照：{@code CompanyFunds} / {@code ReputationData} 一直是匿名类写法，从来没出过这个问题。</p>
     */
    private static SavedData.Factory<MarketData> factory() {
        return new SavedData.Factory<>(
                new java.util.function.Supplier<MarketData>() {
                    @Override
                    public MarketData get() {
                        return new MarketData();
                    }
                },
                new java.util.function.BiFunction<CompoundTag, HolderLookup.Provider, MarketData>() {
                    @Override
                    public MarketData apply(CompoundTag tag, HolderLookup.Provider registries) {
                        return load(tag, registries);
                    }
                });
    }

    public static MarketData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(factory(), FILE_ID);
    }

    /** 全部挂单（只读快照，界面和网络包都用它） */
    public List<Listing> listings() {
        return List.copyOf(listings);
    }

    public Listing byId(long id) {
        for (Listing listing : listings) {
            if (listing.id() == id) {
                return listing;
            }
        }
        return null;
    }

    /** 新挂一单，返回挂单编号 */
    public long add(Listing listing) {
        long id = nextId++;
        listings.add(new Listing(id, listing.seller(), listing.sellerName(), listing.company(),
                listing.kind(), listing.item(), listing.vehicle(), listing.price(),
                listing.barter(), listing.barterCount()));
        setDirty();
        return id;
    }

    /** 撤单：只有卖家的单能被撤掉 */
    public boolean remove(long id, java.util.UUID seller) {
        for (int i = 0; i < listings.size(); i++) {
            Listing listing = listings.get(i);
            if (listing.id() == id && listing.seller() != null && listing.seller().equals(seller)) {
                listings.remove(i);
                setDirty();
                return true;
            }
        }
        return false;
    }

    /** 成交后把这单移走（货已经被买家取走 / 已经生成） */
    public boolean consume(long id) {
        for (int i = 0; i < listings.size(); i++) {
            if (listings.get(i).id() == id) {
                listings.remove(i);
                setDirty();
                return true;
            }
        }
        return false;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Listing listing : listings) {
            list.add(listing.save());
        }
        tag.put("Listings", list);
        tag.putLong("NextId", nextId);
        return tag;
    }

    public static MarketData load(CompoundTag tag, HolderLookup.Provider registries) {
        MarketData data = new MarketData();
        ListTag list = tag.getList("Listings", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            try {
                data.listings.add(Listing.load(list.getCompound(i)));
            } catch (Throwable t) {
                DeepSpaceDeployer.LOGGER.warn("[深空铸造体] 跳过一条读不出来的挂单：{}", t.toString());
            }
        }
        data.nextId = Math.max(1L, tag.getLong("NextId"));
        return data;
    }
}
