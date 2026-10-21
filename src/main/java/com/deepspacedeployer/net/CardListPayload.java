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
 * @param cards 卡 id / 卡名 / 是否废卡（废卡只展示，不参与选择）
 */
public record CardListPayload(List<CardEntry> cards) implements CustomPacketPayload {

    public record CardEntry(String cardId, String name, boolean voided) {
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
                        }
                    },
                    buf -> {
                        int size = Math.max(0, Math.min(32, buf.readVarInt()));
                        List<CardEntry> list = new ArrayList<>(size);
                        for (int i = 0; i < size; i++) {
                            list.add(new CardEntry(buf.readUtf(36), buf.readUtf(64), buf.readBoolean()));
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
