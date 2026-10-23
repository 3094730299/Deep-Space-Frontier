package com.deepspacedeployer.net;

import com.deepspacedeployer.DeepSpaceDeployer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 → 客户端：玩家名下的银行卡列表（挂失选卡用）。
 *
 * @param cards 卡 id / 卡名 / 是否废卡 / 7 账户 / 总资产
 */
public record CardListPayload(List<CardEntry> cards) implements CustomPacketPayload {

    public record CardEntry(String cardId, String name, boolean voided,
                            long[] balances, long totalAssets) {
    }

    public static final CustomPacketPayload.Type<CardListPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(DeepSpaceDeployer.MOD_ID, "card_list"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CardListPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeVarInt(payload.cards().size());
                        for (CardEntry entry : payload.cards()) {
                            buf.writeUtf(entry.cardId(), 36);
                            buf.writeUtf(entry.name() == null ? "" : entry.name(), 64);
                            buf.writeBoolean(entry.voided());
                            for (long value : entry.balances()) {
                                buf.writeLong(value);
                            }
                            buf.writeLong(entry.totalAssets());
                        }
                    },
                    buf -> {
                        int size = Math.max(0, Math.min(32, buf.readVarInt()));
                        List<CardEntry> list = new ArrayList<>(size);
                        for (int i = 0; i < size; i++) {
                            String cardId = buf.readUtf(36);
                            String name = buf.readUtf(64);
                            boolean voided = buf.readBoolean();
                            long[] balances = new long[7];
                            for (int j = 0; j < balances.length; j++) {
                                balances[j] = buf.readLong();
                            }
                            long total = buf.readLong();
                            list.add(new CardEntry(cardId, name, voided, balances, total));
                        }
                        return new CardListPayload(List.copyOf(list));
                    });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CardListPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.deepspacedeployer.client.CardListClient.accept(payload));
    }
}
