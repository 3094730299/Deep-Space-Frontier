package com.deepspacedeployer.net;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** 网络包注册（在模组主类里挂到 mod 事件总线） */
public final class ModPayloads {

    /**
     * 协议版本：以后改了包结构就把它 +1，防止新旧客户端串味。
     *
     * <p>历史：1 → 2 加入资金转化包；2 → 3 删掉 {@code MachineActionPayload}；
     * 3 → 4 给 {@code TransferPayload} 加了 {@code slot} 字段（换卡用）；
     * 4 → 5 票据物品删除、{@code TransferPayload} 加 {@code name} 字段（玩家转账）；
     * 5 → 6 加入寄售市场包（{@code MarketPayload}，双向都用它）。</p>
     */
    public static final String VERSION = "8";

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        // 客户端 → 服务端
        registrar.playToServer(BindCardPayload.TYPE, BindCardPayload.STREAM_CODEC, BindCardPayload::handle);
        registrar.playToServer(ShopEditPayload.TYPE, ShopEditPayload.STREAM_CODEC, ShopEditPayload::handle);
        registrar.playToServer(SetAssemblyTargetPayload.TYPE, SetAssemblyTargetPayload.STREAM_CODEC,
                SetAssemblyTargetPayload::handle);
        registrar.playToServer(TransferPayload.TYPE, TransferPayload.STREAM_CODEC,
                TransferPayload::handle);
        registrar.playToServer(MarketPayload.TYPE, MarketPayload.STREAM_CODEC, MarketPayload::handle);
        // 客户端回报「投送动画播完了」→ 服务端才收下一轮（收一次 = 播一次）
        registrar.playToServer(BuyerAnimDonePayload.TYPE, BuyerAnimDonePayload.STREAM_CODEC,
                BuyerAnimDonePayload::handle);
        // 服务端 → 客户端
        registrar.playToClient(ShopDataPayload.TYPE, ShopDataPayload.STREAM_CODEC, ShopDataPayload::handle);
        registrar.playToClient(TransferSyncPayload.TYPE, TransferSyncPayload.STREAM_CODEC,
                TransferSyncPayload::handle);
        registrar.playToClient(BlueprintThumbnailsPayload.TYPE, BlueprintThumbnailsPayload.STREAM_CODEC,
                BlueprintThumbnailsPayload::handle);
        registrar.playToClient(MarketListPayload.TYPE, MarketListPayload.STREAM_CODEC,
                MarketListPayload::handle);
        registrar.playToClient(BuyerSoldPayload.TYPE, BuyerSoldPayload.STREAM_CODEC,
                BuyerSoldPayload::handle);
    }

    private ModPayloads() {
    }
}
