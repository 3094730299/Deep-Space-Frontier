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
     * 5 → 6 加入寄售市场包（{@code MarketPayload}，双向都用它）；
     * 6 → 7 加入精构仪；7 → 8 加入主题/收购动画；8 → 9 加入卡钱包 7 账户包；
     * 9 → 10 绑定卡包带上手（主手/副手），避免绑错卡；
     * 10 → 11 加入货币银行操作包；
     * 11 → 12 银行挂失改成选卡：操作包带 cardId，并加玩家卡列表包；
     * 12 → 13 银行改成两个共享卡槽：加银行状态包，确认转账合并成一个动作；
     * 13 → 14 银行卡槽状态带卡物品图标。</p>
     */
    public static final String VERSION = "14";

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        // 客户端 → 服务端
        registrar.playToServer(BindCardPayload.TYPE, BindCardPayload.STREAM_CODEC, BindCardPayload::handle);
        registrar.playToServer(CurrencyBankPayload.TYPE, CurrencyBankPayload.STREAM_CODEC, CurrencyBankPayload::handle);
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
        // 银行卡界面的 7 个账户数据
        registrar.playToClient(CardWalletPayload.TYPE, CardWalletPayload.STREAM_CODEC,
                CardWalletPayload::handle);
        // 银行挂失选卡用的玩家卡列表
        registrar.playToClient(CardListPayload.TYPE, CardListPayload.STREAM_CODEC,
                CardListPayload::handle);
        // 银行两个共享卡槽 + 自己卡的账户数据
        registrar.playToClient(BankStatePayload.TYPE, BankStatePayload.STREAM_CODEC,
                BankStatePayload::handle);
    }

    private ModPayloads() {
    }
}
