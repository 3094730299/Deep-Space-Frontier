package com.deepspacedeployer.net;

import com.deepspacedeployer.DeepSpaceDeployer;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** 服务端 → OP 编辑器：当前公司商店规则。 */
public record ShopRulesPayload(String companyId, int baseTypes, int baseStock,
                               boolean randomItems, boolean varyTypes,
                               int typePercent, int stockPercent)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ShopRulesPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(DeepSpaceDeployer.MOD_ID, "shop_rules"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ShopRulesPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeUtf(payload.companyId());
                        buf.writeVarInt(payload.baseTypes());
                        buf.writeVarInt(payload.baseStock());
                        buf.writeBoolean(payload.randomItems());
                        buf.writeBoolean(payload.varyTypes());
                        buf.writeVarInt(payload.typePercent());
                        buf.writeVarInt(payload.stockPercent());
                    },
                    buf -> new ShopRulesPayload(
                            buf.readUtf(), buf.readVarInt(), buf.readVarInt(),
                            buf.readBoolean(), buf.readBoolean(),
                            buf.readVarInt(), buf.readVarInt()));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ShopRulesPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.deepspacedeployer.client.ShopRulesClient.accept(payload));
    }
}
