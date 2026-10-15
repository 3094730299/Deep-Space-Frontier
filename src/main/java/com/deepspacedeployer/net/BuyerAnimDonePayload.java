package com.deepspacedeployer.net;

import com.deepspacedeployer.DeepSpaceDeployer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：「投送」动画已经播完了，可以收下一轮了。
 *
 * <p>这是「收一次 = 播一次」的关键：服务端不再自己掐秒表（那会和客户端打架，
 * 现象就是"连着播两次、停一下"），而是<b>等客户端播完的回执</b>才收下一轮。</p>
 *
 * <p>服务端仍保留一个兜底超时（{@code BUYER_ANIM_FALLBACK_MS}），
 * 防止客户端丢包导致收购卡死。</p>
 */
public record BuyerAnimDonePayload(BlockPos pos) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BuyerAnimDonePayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(DeepSpaceDeployer.MOD_ID, "buyer_anim_done"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuyerAnimDonePayload> STREAM_CODEC =
            StreamCodec.composite(BlockPos.STREAM_CODEC, BuyerAnimDonePayload::pos,
                    BuyerAnimDonePayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuyerAnimDonePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player().level().getBlockEntity(payload.pos())
                    instanceof com.deepspacedeployer.blockentity.MachineBlockEntity machine) {
                // 两种回执共用这个包：
                //   · 放置动画播完 → 置位 PlaceAnimDone（服务端记下来，以后不再播）
                //   · 投送动画播完 → 允许收下一轮
                machine.onPlaceAnimDone();
                machine.onAnimDone();
            }
        });
    }
}
