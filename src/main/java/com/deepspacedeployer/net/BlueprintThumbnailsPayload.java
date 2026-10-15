package com.deepspacedeployer.net;

import com.deepspacedeployer.DeepSpaceDeployer;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * 服务端 → 客户端：载具图纸的预览缩略图。
 *
 * <p>图纸文件（`Sable-Schematics/*.nbt`）里本来就带 512×512 的等距预览图，
 * 服务端把它缩成 64×64 的 PNG 再发过来，界面里就能在每个图纸行左边显示一张小图。
 * 每张 PNG 只有几 KB，十几份图纸一次也就几十 KB。</p>
 *
 * @param names 图纸名
 * @param pngs  对应的 PNG 字节（64×64）
 */
public record BlueprintThumbnailsPayload(List<String> names, List<byte[]> pngs) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BlueprintThumbnailsPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(DeepSpaceDeployer.MOD_ID, "blueprint_thumbs"));

    private static final StreamCodec<ByteBuf, List<String>> NAMES =
            ByteBufCodecs.<ByteBuf, String>list(256).apply(ByteBufCodecs.STRING_UTF8);
    private static final StreamCodec<ByteBuf, List<byte[]>> PNGS =
            ByteBufCodecs.<ByteBuf, byte[]>list(256).apply(ByteBufCodecs.BYTE_ARRAY);

    public static final StreamCodec<RegistryFriendlyByteBuf, BlueprintThumbnailsPayload> STREAM_CODEC =
            StreamCodec.composite(
                    NAMES, BlueprintThumbnailsPayload::names,
                    PNGS, BlueprintThumbnailsPayload::pngs,
                    BlueprintThumbnailsPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BlueprintThumbnailsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.deepspacedeployer.client.ClientBlueprintThumbs
                .accept(payload.names(), payload.pngs()));
    }
}
