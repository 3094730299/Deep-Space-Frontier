package com.deepspacedeployer.net;

import com.deepspacedeployer.DeepSpaceDeployer;
import com.deepspacedeployer.economy.CardBinding;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：银行卡的绑定 / 改名 / 解绑。
 *
 * @param targetName 玩家名或 UUID（绑定用）
 * @param label      卡名（可选，给卡起个好记的名字）
 * @param action     0=绑定 1=改名 2=解除绑定
 */
public record BindCardPayload(String targetName, String label, int action) implements CustomPacketPayload {

    public static final int ACTION_BIND = 0;
    public static final int ACTION_RENAME = 1;
    public static final int ACTION_UNBIND = 2;

    public static final CustomPacketPayload.Type<BindCardPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(DeepSpaceDeployer.MOD_ID, "bind_card"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BindCardPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, BindCardPayload::targetName,
                    ByteBufCodecs.STRING_UTF8, BindCardPayload::label,
                    ByteBufCodecs.VAR_INT, BindCardPayload::action,
                    BindCardPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BindCardPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> CardBinding.handle(context.player(), payload));
    }
}
