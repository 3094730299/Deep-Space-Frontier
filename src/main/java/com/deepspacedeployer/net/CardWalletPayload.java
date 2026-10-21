package com.deepspacedeployer.net;

import com.deepspacedeployer.DeepSpaceDeployer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：银行卡的 7 个账户数据（卡界面用）。
 *
 * @param hand        主手/副手（0=主手，1=副手）
 * @param balances    7 个槽位：0~5 六家公司，6 通用货币
 * @param totalAssets 总资产 = 六家公司 × 70% + 通用
 * @param voided      是不是废卡（挂失后只作装饰）
 * @param accountName 卡主显示名（未绑定为空）
 * @param cardName    卡名（物品自定义名称，可能为空）
 */
public record CardWalletPayload(int hand, long[] balances, long totalAssets, boolean voided,
                                String accountName, String cardName) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CardWalletPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(DeepSpaceDeployer.MOD_ID, "card_wallet"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CardWalletPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeVarInt(payload.hand());
                        for (long value : payload.balances()) {
                            buf.writeLong(value);
                        }
                        buf.writeLong(payload.totalAssets());
                        buf.writeBoolean(payload.voided());
                        buf.writeUtf(payload.accountName() == null ? "" : payload.accountName(), 64);
                        buf.writeUtf(payload.cardName() == null ? "" : payload.cardName(), 64);
                    },
                    buf -> {
                        int hand = buf.readVarInt();
                        long[] balances = new long[7];
                        for (int i = 0; i < balances.length; i++) {
                            balances[i] = buf.readLong();
                        }
                        long total = buf.readLong();
                        boolean voided = buf.readBoolean();
                        String accountName = buf.readUtf(64);
                        String cardName = buf.readUtf(64);
                        return new CardWalletPayload(hand, balances, total, voided, accountName, cardName);
                    });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CardWalletPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.deepspacedeployer.client.CardWalletClient.accept(payload));
    }
}
