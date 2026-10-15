package com.deepspacedeployer.client;

import com.deepspacedeployer.market.Listing;

import java.util.List;

/**
 * 客户端：寄售市场的挂单缓存（服务端推过来的那份）。
 *
 * <p>界面在客户端重建按钮时读它；服务端只负责在挂单变化时推一次。</p>
 */
public final class ClientMarketCache {

    private static volatile List<Listing> listings = List.of();
    /** 每次收到新数据 +1，界面靠它判断"要不要重建控件" */
    private static volatile int version;

    public static void accept(List<Listing> incoming) {
        listings = incoming == null ? List.of() : List.copyOf(incoming);
        version++;
    }

    public static List<Listing> listings() {
        return listings;
    }

    public static int version() {
        return version;
    }

    private ClientMarketCache() {
    }
}
