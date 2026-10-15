package com.deepspacedeployer.shop;

import com.deepspacedeployer.Company;
import com.deepspacedeployer.Tier;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 读取数据包里的价目表：{@code data/<命名空间>/shop/<任意名>.json}
 *
 * <p>格式（可以只写一部分，没写的部分用内置默认表）：</p>
 * <pre>
 * {
 *   "company": "threshold",
 *   "buy": [
 *     { "item": "simulated:gimbal_sensor", "count": 1, "price": 180, "tier": "mid", "reputation": 0 }
 *   ],
 *   "sell": [
 *     { "item": "minecraft:iron_ingot", "count": 1, "price": 8 }
 *   ]
 * }
 * </pre>
 *
 * <p>改完用 {@code /reload} 就能生效，多人服务器不用重开。</p>
 */
public class ShopReloadListener extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setLenient().create();
    private static final String DIRECTORY = "shop";

    public ShopReloadListener() {
        super(GSON, DIRECTORY);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> objects, ResourceManager resourceManager,
                         ProfilerFiller profiler) {
        ShopData defaults = DefaultShops.build();
        Map<Company, List<TradeEntry>> buy = new EnumMap<>(Company.class);
        Map<Company, List<TradeEntry>> sell = new EnumMap<>(Company.class);

        int files = 0;
        for (Map.Entry<ResourceLocation, JsonElement> object : objects.entrySet()) {
            try {
                JsonObject json = object.getValue().getAsJsonObject();
                if (!json.has("company")) {
                    LOGGER.warn("[深空铸造体] 价目表 {} 缺少 company 字段，已跳过", object.getKey());
                    continue;
                }
                Company company = Company.byId(json.get("company").getAsString());
                files++;
                if (json.has("buy")) {
                    buy.computeIfAbsent(company, c -> new ArrayList<>())
                            .addAll(readEntries(json.getAsJsonArray("buy"), true));
                }
                if (json.has("sell")) {
                    sell.computeIfAbsent(company, c -> new ArrayList<>())
                            .addAll(readEntries(json.getAsJsonArray("sell"), false));
                }
            } catch (Exception e) {
                LOGGER.warn("[深空铸造体] 价目表 {} 解析失败：{}", object.getKey(), e.toString());
            }
        }

        // 数据包里没写的公司，用内置默认表补上
        for (Company company : Company.VALUES) {
            buy.putIfAbsent(company, defaults.buyOffers(company));
            sell.putIfAbsent(company, defaults.sellOffers(company));
        }

        ShopData.set(new ShopData(buy, sell));
        LOGGER.info("[深空铸造体] 价目表已加载：{} 个文件，六家公司就绪", files);
    }

    private static List<TradeEntry> readEntries(JsonArray array, boolean isBuy) {
        List<TradeEntry> out = new ArrayList<>();
        for (JsonElement element : array) {
            try {
                JsonObject row = element.getAsJsonObject();
                String item = row.get("item").getAsString();
                int count = row.has("count") ? row.get("count").getAsInt() : 1;
                int price = row.has("price") ? row.get("price").getAsInt() : 1;
                Tier tier = row.has("tier") ? Tier.byId(row.get("tier").getAsString()) : Tier.LOW;
                int reputation = row.has("reputation") ? row.get("reputation").getAsInt() : 0;
                String blueprint = row.has("blueprint") ? row.get("blueprint").getAsString() : "";
                TradeEntry entry = new TradeEntry(item, count, price, tier, reputation, blueprint);
                if (!entry.isValid()) {
                    LOGGER.warn("[深空铸造体] 价目表里有不存在的物品 id：{}（已跳过）", item);
                    continue;
                }
                out.add(entry);
            } catch (Exception e) {
                LOGGER.warn("[深空铸造体] 价目表有一行格式不对（已跳过）：{}", e.toString());
            }
        }
        return out;
    }
}
