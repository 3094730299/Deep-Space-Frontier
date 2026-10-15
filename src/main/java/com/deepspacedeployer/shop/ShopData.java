package com.deepspacedeployer.shop;

import com.deepspacedeployer.Company;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 全服商店数据（从数据包 {@code data/<命名空间>/shop/*.json} 读进来）。
 *
 * <p>两组表：</p>
 * <ul>
 *   <li>{@code buyOffers}：公司<b>卖</b>给玩家的东西（玩家花钱买）</li>
 *   <li>{@code sellOffers}：公司<b>收</b>的东西（玩家卖材料换钱）</li>
 * </ul>
 *
 * <p>用 {@link #get()} 拿到当前生效的表；{@code /reload} 后会自动换成新的。</p>
 */
public final class ShopData {

    private static volatile ShopData instance;

    private final Map<Company, List<TradeEntry>> buyOffers = new EnumMap<>(Company.class);
    private final Map<Company, List<TradeEntry>> sellOffers = new EnumMap<>(Company.class);

    public ShopData(Map<Company, List<TradeEntry>> buyOffers, Map<Company, List<TradeEntry>> sellOffers) {
        for (Company company : Company.VALUES) {
            this.buyOffers.put(company, List.copyOf(buyOffers.getOrDefault(company, List.of())));
            this.sellOffers.put(company, List.copyOf(sellOffers.getOrDefault(company, List.of())));
        }
    }

    public static ShopData get() {
        ShopData current = instance;
        if (current == null) {
            current = DefaultShops.build();
            instance = current;
        }
        return current;
    }

    public static void set(ShopData data) {
        instance = data;
    }

    /** 公司卖给玩家的商品 */
    public List<TradeEntry> buyOffers(Company company) {
        return buyOffers.getOrDefault(company, List.of());
    }

    /** 公司收购的物品 */
    public List<TradeEntry> sellOffers(Company company) {
        return sellOffers.getOrDefault(company, List.of());
    }

    /**
     * 玩家卖一个物品能换多少钱（按单个物品算）。
     *
     * @return 单个物品的价格，不收购返回 0
     */
    public int sellPriceFor(Company company, net.minecraft.world.item.ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        for (TradeEntry entry : sellOffers(company)) {
            if (!entry.isValid()) {
                continue;
            }
            if (stack.is(entry.resolveItem())) {
                return Math.max(0, entry.price() / Math.max(1, entry.count()));
            }
        }
        return 0;
    }

    /** 找收购条目（用于取整组数量） */
    public TradeEntry findSellEntry(Company company, net.minecraft.world.item.ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        for (TradeEntry entry : sellOffers(company)) {
            if (entry.isValid() && stack.is(entry.resolveItem())) {
                return entry;
            }
        }
        return null;
    }

    /** 把某公司某等级能买的商品列出来（不过滤声望，声望由界面显示为"未解锁"） */
    public List<TradeEntry> visibleBuyOffers(Company company, int tierRank) {
        List<TradeEntry> out = new ArrayList<>();
        for (TradeEntry entry : buyOffers(company)) {
            if (entry.isValid() && entry.tier().rank() <= tierRank) {
                out.add(entry);
            }
        }
        return out;
    }
}
