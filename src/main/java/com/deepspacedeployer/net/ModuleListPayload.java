package com.deepspacedeployer.net;

import com.deepspacedeployer.DeepSpaceDeployer;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * 服务端 → 客户端：模块打印机可用的预设模块列表（只含元数据，不含蓝图本体）。
 */
public record ModuleListPayload(List<ModuleEntry> modules) implements CustomPacketPayload {

    /**
     * @param name    预设 key（文件名）
     * @param display 显示名
     * @param price   价格（本机器所属公司货币）
     * @param tier    最低等级序号
     * @param company 限定公司 id；空 = 所有公司
     */
    public record ModuleEntry(String name, String display, long price, int tier, String company,
                              int sizeX, int sizeY, int sizeZ, int blocks) {
    }

    public static final CustomPacketPayload.Type<ModuleListPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(DeepSpaceDeployer.MOD_ID, "module_list"));

    private static final StreamCodec<FriendlyByteBuf, ModuleEntry> ENTRY_CODEC = StreamCodec.of(
            (buf, entry) -> {
                buf.writeUtf(entry.name(), 96);
                buf.writeUtf(entry.display(), 128);
                buf.writeLong(entry.price());
                buf.writeVarInt(entry.tier());
                buf.writeUtf(entry.company() == null ? "" : entry.company(), 64);
                buf.writeVarInt(entry.sizeX());
                buf.writeVarInt(entry.sizeY());
                buf.writeVarInt(entry.sizeZ());
                buf.writeVarInt(entry.blocks());
            },
            buf -> new ModuleEntry(
                    buf.readUtf(96), buf.readUtf(128), buf.readLong(), buf.readVarInt(),
                    buf.readUtf(64), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt()));

    private static final StreamCodec<FriendlyByteBuf, List<ModuleEntry>> LIST_CODEC =
            ByteBufCodecs.<FriendlyByteBuf, ModuleEntry>list(256).apply(ENTRY_CODEC);

    public static final StreamCodec<RegistryFriendlyByteBuf, ModuleListPayload> STREAM_CODEC =
            StreamCodec.composite(LIST_CODEC, ModuleListPayload::modules, ModuleListPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ModuleListPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.deepspacedeployer.client.ModulePresetClient.accept(payload));
    }
}
