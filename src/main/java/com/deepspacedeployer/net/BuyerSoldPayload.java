package com.deepspacedeployer.net;

import com.deepspacedeployer.DeepSpaceDeployer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：这台收购投送机刚把东西卖掉了，请播放「投送」动画。
 *
 * <p>播完之后客户端会回一个「{@link BuyerAnimDonePayload} 动画播完了」，
 * 服务端收到才收下一轮 —— 这样<b>收一次 = 播一次</b>，两边严格同步。</p>
 */
public record BuyerSoldPayload(BlockPos pos) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BuyerSoldPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(DeepSpaceDeployer.MOD_ID, "buyer_sold"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuyerSoldPayload> STREAM_CODEC =
            StreamCodec.composite(BlockPos.STREAM_CODEC, BuyerSoldPayload::pos, BuyerSoldPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuyerSoldPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player().level().getBlockEntity(payload.pos())
                    instanceof com.deepspacedeployer.blockentity.MachineBlockEntity machine) {
                machine.onBuyerSold();
            } else {
                com.deepspacedeployer.DeepSpaceDeployer.LOGGER.warn(
                        "[深空铸造体] 收到售卖播报但找不到方块实体 @{}（客户端未加载该区块？）", payload.pos());
            }
        });
    }
}
