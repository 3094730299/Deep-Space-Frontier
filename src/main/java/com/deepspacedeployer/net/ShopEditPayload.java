package com.deepspacedeployer.net;

import com.deepspacedeployer.DeepSpaceDeployer;
import com.deepspacedeployer.editor.ShopEditor;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：价目表编辑器的操作。
 *
 * @param companyId 公司代号
 * @param action    0=请求数据 1=加入手中物品 2=调价 3=改等级 4=删除 5=保存到世界数据包
 * @param listKind  0 = 公司卖(玩家买) 1 = 公司收(玩家卖)
 * @param index     条目下标
 * @param value     参数（调价用 ±数量）
 */
public record ShopEditPayload(String companyId, int action, int listKind, int index, int value)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ShopEditPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(DeepSpaceDeployer.MOD_ID, "shop_edit"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ShopEditPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, ShopEditPayload::companyId,
                    ByteBufCodecs.VAR_INT, ShopEditPayload::action,
                    ByteBufCodecs.VAR_INT, ShopEditPayload::listKind,
                    ByteBufCodecs.VAR_INT, ShopEditPayload::index,
                    ByteBufCodecs.VAR_INT, ShopEditPayload::value,
                    ShopEditPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ShopEditPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ShopEditor.handle(context.player(), payload));
    }
}
