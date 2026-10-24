package com.deepspacedeployer.shop;

import com.deepspacedeployer.Company;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.EnumMap;
import java.util.Map;

/**
 * OP 商店规则：每家公司基准种类/基准库存、两个种类波动开关、全局波动幅度。
 */
public final class ShopRulesData extends SavedData {

    public static final String FILE_NAME = "deepspacedeployer_shop_rules";
    public static final int DEFAULT_TYPES = 16;
    public static final int DEFAULT_STOCK = 3;
    public static final int DEFAULT_PERCENT = 20;

    public static final class Rule {
        public int baseTypes = DEFAULT_TYPES;
        public int baseStock = DEFAULT_STOCK;
        public boolean randomItems;
        public boolean varyTypes;
    }

    private final Map<Company, Rule> rules = new EnumMap<>(Company.class);
    private int typePercent = DEFAULT_PERCENT;
    private int stockPercent = DEFAULT_PERCENT;

    public static ShopRulesData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(ShopRulesData::new, ShopRulesData::load, null),
                FILE_NAME);
    }

    public static ShopRulesData load(CompoundTag tag, HolderLookup.Provider registries) {
        ShopRulesData data = new ShopRulesData();
        data.typePercent = clampPercent(tag.getInt("TypePercent"));
        data.stockPercent = clampPercent(tag.getInt("StockPercent"));
        CompoundTag all = tag.getCompound("Rules");
        for (Company company : Company.VALUES) {
            if (!all.contains(company.id())) {
                continue;
            }
            CompoundTag row = all.getCompound(company.id());
            Rule rule = data.rule(company);
            rule.baseTypes = clamp(row.getInt("BaseTypes"), DEFAULT_TYPES);
            rule.baseStock = clamp(row.getInt("BaseStock"), DEFAULT_STOCK);
            rule.randomItems = row.getBoolean("RandomItems");
            rule.varyTypes = row.getBoolean("VaryTypes");
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("TypePercent", typePercent);
        tag.putInt("StockPercent", stockPercent);
        CompoundTag all = new CompoundTag();
        for (Company company : Company.VALUES) {
            Rule rule = rule(company);
            CompoundTag row = new CompoundTag();
            row.putInt("BaseTypes", rule.baseTypes);
            row.putInt("BaseStock", rule.baseStock);
            row.putBoolean("RandomItems", rule.randomItems);
            row.putBoolean("VaryTypes", rule.varyTypes);
            all.put(company.id(), row);
        }
        tag.put("Rules", all);
        return tag;
    }

    public Rule rule(Company company) {
        return rules.computeIfAbsent(company, c -> new Rule());
    }

    public int typePercent() {
        return typePercent;
    }

    public int stockPercent() {
        return stockPercent;
    }

    public void adjustTypes(Company company, int delta, int currentOffers) {
        Rule rule = rule(company);
        rule.baseTypes = Math.max(Math.max(1, currentOffers), Math.min(64, rule.baseTypes + delta));
        setDirty();
    }

    public void adjustStock(Company company, int delta) {
        Rule rule = rule(company);
        rule.baseStock = Math.max(1, Math.min(64, rule.baseStock + delta));
        setDirty();
    }

    public void toggleRandomItems(Company company) {
        Rule rule = rule(company);
        rule.randomItems = !rule.randomItems;
        setDirty();
    }

    public void toggleVaryTypes(Company company) {
        Rule rule = rule(company);
        rule.varyTypes = !rule.varyTypes;
        setDirty();
    }

    public void adjustTypePercent(int delta) {
        typePercent = clampPercent(typePercent + delta);
        setDirty();
    }

    public void adjustStockPercent(int delta) {
        stockPercent = clampPercent(stockPercent + delta);
        setDirty();
    }

    private static int clamp(int value, int fallback) {
        return value <= 0 ? fallback : Math.min(64, value);
    }

    private static int clampPercent(int value) {
        return Math.max(0, Math.min(50, value));
    }

    private ShopRulesData() {
    }
}
