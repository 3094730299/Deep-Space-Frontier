package com.deepspacedeployer.client;

import com.deepspacedeployer.Company;
import com.deepspacedeployer.net.ShopDataPayload;
import com.deepspacedeployer.shop.TradeEntry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 客户端缓存的价目表（服务端打开界面时发过来） */
public final class ClientShopCache {

    private static final Map<String, ShopDataPayload> CACHE = new HashMap<>();
    /** 数据版本号：界面靠它知道"服务器刚推了新数据"，好重建按钮 */
    private static int version;

    public static void accept(ShopDataPayload payload) {
        CACHE.put(payload.companyId(), payload);
        version++;
    }

    public static int version() {
        return version;
    }

    public static void clear() {
        CACHE.clear();
        version++;
    }

    public static List<TradeEntry> buyOffers(Company company) {
        ShopDataPayload payload = CACHE.get(company.id());
        return payload == null ? List.of() : payload.buyOffers();
    }

    public static List<TradeEntry> sellOffers(Company company) {
        ShopDataPayload payload = CACHE.get(company.id());
        return payload == null ? List.of() : payload.sellOffers();
    }

    private ClientShopCache() {
    }
}
