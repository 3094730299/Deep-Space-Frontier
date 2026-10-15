package com.deepspacedeployer.net;

import com.deepspacedeployer.DeepSpaceDeployer;
import com.deepspacedeployer.blockentity.MachineBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：给某台工厂核心设置"投放点"（结构要落在哪）。
 *
 * @param x    方块 X
 * @param y    方块 Y
 * @param z    方块 Z
 * @param pickX 选中的落点 X
 * @param pickZ 选中的落点 Z
 */
public record SetAssemblyTargetPayload(int x, int y, int z, int pickX, int pickZ) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SetAssemblyTargetPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(DeepSpaceDeployer.MOD_ID, "set_assembly_target"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetAssemblyTargetPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, SetAssemblyTargetPayload::x,
                    ByteBufCodecs.VAR_INT, SetAssemblyTargetPayload::y,
                    ByteBufCodecs.VAR_INT, SetAssemblyTargetPayload::z,
                    ByteBufCodecs.VAR_INT, SetAssemblyTargetPayload::pickX,
                    ByteBufCodecs.VAR_INT, SetAssemblyTargetPayload::pickZ,
                    SetAssemblyTargetPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SetAssemblyTargetPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            BlockPos pos = new BlockPos(payload.x(), payload.y(), payload.z());
            if (!(player.level().getBlockEntity(pos) instanceof MachineBlockEntity machine)) {
                return;
            }
            if (machine.setAssemblyTarget(payload.pickX(), payload.pickZ())) {
                player.displayClientMessage(Component.translatable("msg.deepspacedeployer.target_set",
                        payload.pickX(), payload.pickZ()), false);
            } else {
                player.displayClientMessage(Component.translatable("msg.deepspacedeployer.target_too_far",
                        com.deepspacedeployer.Config.ASSEMBLY_MAX_RADIUS.get()), true);
            }
        });
    }
}
