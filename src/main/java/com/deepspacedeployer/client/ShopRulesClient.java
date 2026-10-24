package com.deepspacedeployer.client;

import com.deepspacedeployer.net.ShopRulesPayload;

/** 客户端缓存的 OP 商店规则（编辑器用）。 */
public final class ShopRulesClient {

    private static ShopRulesPayload data;
    private static int version;

    public static void accept(ShopRulesPayload payload) {
        data = payload;
        version++;
    }

    public static ShopRulesPayload get() {
        return data;
    }

    public static int version() {
        return version;
    }

    private ShopRulesClient() {
    }
}
