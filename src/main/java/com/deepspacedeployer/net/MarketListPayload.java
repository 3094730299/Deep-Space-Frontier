package com.deepspacedeployer.net;

import com.deepspacedeployer.Company;
import com.deepspacedeployer.DeepSpaceDeployer;
import com.deepspacedeployer.market.Listing;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 服务端 → 客户端：寄售市场的挂单列表（整份推，客户端整份替换）。
 *
 * <p>市场方块每 2 秒推一次、打开界面时也推一次，所以"别人挂了新东西"能自己出现在你打开的界面里。</p>
 */
public record MarketListPayload(List<Listing> listings) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<MarketListPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(DeepSpaceDeployer.MOD_ID, "market_list"));

    /** 一次最多推这么多条（防炸包） */
    private static final int MAX = 512;

    public static final StreamCodec<RegistryFriendlyByteBuf, MarketListPayload> STREAM_CODEC =
            StreamCodec.of(MarketListPayload::encode, MarketListPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buf, MarketListPayload payload) {
        List<Listing> list = payload.listings() == null ? List.of() : payload.listings();
        int size = Math.min(list.size(), MAX);
        ByteBufCodecs.VAR_INT.encode(buf, size);
        for (int i = 0; i < size; i++) {
            Listing listing = list.get(i);
            ByteBufCodecs.VAR_LONG.encode(buf, listing.id());
            ByteBufCodecs.STRING_UTF8.encode(buf, listing.sellerName() == null ? "" : listing.sellerName());
            ByteBufCodecs.STRING_UTF8.encode(buf, listing.seller() == null ? "" : listing.seller().toString());
            ByteBufCodecs.STRING_UTF8.encode(buf, listing.company() == null ? "" : listing.company().id());
            ByteBufCodecs.STRING_UTF8.encode(buf, listing.kind().name());
            ByteBufCodecs.STRING_UTF8.encode(buf, listing.vehicle() == null ? "" : listing.vehicle());
            ByteBufCodecs.VAR_LONG.encode(buf, listing.price());
            ByteBufCodecs.VAR_INT.encode(buf, listing.barterCount());
            // 物品走原版流 codec（数量 + 全部数据组件都能带过去）
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf,
                    listing.item() == null ? ItemStack.EMPTY : listing.item());
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf,
                    listing.barter() == null ? ItemStack.EMPTY : listing.barter());
        }
    }

    private static MarketListPayload decode(RegistryFriendlyByteBuf buf) {
        int size = Math.max(0, Math.min(ByteBufCodecs.VAR_INT.decode(buf), MAX));
        List<Listing> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            long id = ByteBufCodecs.VAR_LONG.decode(buf);
            String sellerName = ByteBufCodecs.STRING_UTF8.decode(buf);
            String sellerRaw = ByteBufCodecs.STRING_UTF8.decode(buf);
            String companyId = ByteBufCodecs.STRING_UTF8.decode(buf);
            String kindName = ByteBufCodecs.STRING_UTF8.decode(buf);
            String vehicle = ByteBufCodecs.STRING_UTF8.decode(buf);
            long price = ByteBufCodecs.VAR_LONG.decode(buf);
            int barterCount = ByteBufCodecs.VAR_INT.decode(buf);
            ItemStack item = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
            ItemStack barter = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);

            UUID seller = null;
            if (!sellerRaw.isEmpty()) {
                try {
                    seller = UUID.fromString(sellerRaw);
                } catch (IllegalArgumentException ignored) {
                    // 坏了就当无主
                }
            }
            Listing.Kind kind;
            try {
                kind = Listing.Kind.valueOf(kindName);
            } catch (IllegalArgumentException e) {
                kind = Listing.Kind.ITEM;
            }
            list.add(new Listing(id, seller, sellerName,
                    companyId.isEmpty() ? Company.VALUES[0] : Company.byId(companyId),
                    kind, item, vehicle, price, barter, barterCount));
        }
        return new MarketListPayload(List.copyOf(list));
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(MarketListPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.deepspacedeployer.client.ClientMarketCache.accept(payload.listings()));
    }
}
