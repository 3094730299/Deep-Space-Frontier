package com.deepspacedeployer.net;

import com.deepspacedeployer.DeepSpaceDeployer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：货币银行的一次操作。
 *
 * <p>卡拿在手里（不插进方块），服务端按 {@code hand} 找到那张卡再校验；
 * 挂失则按 {@code cardId} 找到玩家选中的那张卡（不一定在手里）。</p>
 *
 * @param action     0~3 旧单动作（保留）/ 4 免费取卡 / 5 旧挂失 / 6 确认转账 /
 *                   7 按卡槽挂失 / 8 存实体币 / 9 取实体币
 * @param from       源槽位：0~5 六家公司，6 通用；卡槽挂失时=0/1；
 *                   存/取实体币时=公司序号
 * @param to         目标槽位：0~5 六家公司，6 通用；
 *                   存/取实体币时=面额序号（0=1, 1=5, 2=10, 3=20, 4=50, 5=100）
 * @param amount     金额；存/取实体币时=张数（存全部=0）
 * @param targetName 转账目标玩家名或 UUID（确认转账时填了=名字转账）
 * @param cardId     旧挂失选中的卡 id（其它操作空字符串）
 * @param hand       兼容旧字段；银行不再用
 * @param force      卡槽挂失时：true=没有接收卡也强行挂失并清空余额
 */
public record CurrencyBankPayload(int action, int from, int to, long amount,
                                  String targetName, String cardId, int hand,
                                  boolean force) implements CustomPacketPayload {

    public static final int ACTION_COMPANY_TO_COMPANY = 0;
    public static final int ACTION_COMPANY_TO_GENERIC = 1;
    public static final int ACTION_GENERIC_TO_COMPANY = 2;
    public static final int ACTION_TRANSFER = 3;
    public static final int ACTION_FREE_CARD = 4;
    public static final int ACTION_VOID = 5;
    public static final int ACTION_CONFIRM = 6;
    public static final int ACTION_VOID_SLOT = 7;
    public static final int ACTION_DEPOSIT_CASH = 8;
    public static final int ACTION_WITHDRAW_CASH = 9;

    public static final CustomPacketPayload.Type<CurrencyBankPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(DeepSpaceDeployer.MOD_ID, "currency_bank"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CurrencyBankPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeVarInt(p.action());
                        buf.writeVarInt(p.from());
                        buf.writeVarInt(p.to());
                        buf.writeLong(p.amount());
                        buf.writeUtf(p.targetName() == null ? "" : p.targetName(), 64);
                        buf.writeUtf(p.cardId() == null ? "" : p.cardId(), 36);
                        buf.writeVarInt(p.hand());
                        buf.writeBoolean(p.force());
                    },
                    buf -> new CurrencyBankPayload(
                            buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                            buf.readLong(), buf.readUtf(64), buf.readUtf(36), buf.readVarInt(),
                            buf.readBoolean()));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CurrencyBankPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                com.deepspacedeployer.economy.CurrencyBankService.handle(serverPlayer, payload);
            }
        });
    }
}
