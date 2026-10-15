package com.deepspacedeployer.net;

import com.deepspacedeployer.DeepSpaceDeployer;
import com.deepspacedeployer.shop.TradeEntry;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * 服务端 → 客户端：把这家公司的价目表发给打开界面的玩家（界面才能显示商品和价格）。
 */
public record ShopDataPayload(String companyId, int tier, List<TradeEntry> buyOffers, List<TradeEntry> sellOffers)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ShopDataPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(DeepSpaceDeployer.MOD_ID, "shop_data"));

    private static final StreamCodec<ByteBuf, List<TradeEntry>> ENTRY_LIST =
            ByteBufCodecs.<ByteBuf, TradeEntry>list(256).apply(TradeEntry.STREAM_CODEC);

    public static final StreamCodec<RegistryFriendlyByteBuf, ShopDataPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, ShopDataPayload::companyId,
                    ByteBufCodecs.VAR_INT, ShopDataPayload::tier,
                    ENTRY_LIST, ShopDataPayload::buyOffers,
                    ENTRY_LIST, ShopDataPayload::sellOffers,
                    ShopDataPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ShopDataPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.deepspacedeployer.client.ClientShopCache.accept(payload));
    }
}
