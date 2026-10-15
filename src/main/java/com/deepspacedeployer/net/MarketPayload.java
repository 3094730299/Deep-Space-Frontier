package com.deepspacedeployer.net;

import com.deepspacedeployer.DeepSpaceDeployer;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：寄售市场上的操作。
 *
 * <p>服务端只认动作编号和几个数字/字符串，货和钱全部从<b>服务端自己那份</b>容器与存档里取 ——
 * 改包最多只能改到"自己想干什么"，改不了别人的钱和货。</p>
 *
 * @param x       市场方块坐标
 * @param y       市场方块坐标
 * @param z       市场方块坐标
 * @param action  见 {@link #ACT_POST} 等
 * @param listing 挂单编号（购买 / 撤单用）
 * @param mode    定价模式：0 = 以币，1 = 以物易物
 * @param isVehicle 挂的是不是载具（发单用）
 * @param index   图纸下标（发载具单时用；-1 表示没选）
 * @param amount  价钱（以币）或数量（以物易物）
 */
public record MarketPayload(int x, int y, int z, int action, long listing, int mode, boolean isVehicle,
                            int index, long amount) implements CustomPacketPayload {
    /** 发单：把"待售格"里的东西（或选中的图纸）挂上去 */
    public static final int ACT_POST = 1;
    /** 购买：按挂单编号买 */
    public static final int ACT_BUY = 2;
    /** 撤单：撤掉自己的挂单，货退回"取货格" */
    public static final int ACT_CANCEL = 3;

    public static final CustomPacketPayload.Type<MarketPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(DeepSpaceDeployer.MOD_ID, "market_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MarketPayload> STREAM_CODEC =
            StreamCodec.of(MarketPayload::encode, MarketPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buf, MarketPayload payload) {
        ByteBufCodecs.VAR_INT.encode(buf, payload.x());
        ByteBufCodecs.VAR_INT.encode(buf, payload.y());
        ByteBufCodecs.VAR_INT.encode(buf, payload.z());
        ByteBufCodecs.VAR_INT.encode(buf, payload.action());
        ByteBufCodecs.VAR_LONG.encode(buf, payload.listing());
        ByteBufCodecs.VAR_INT.encode(buf, payload.mode());
        ByteBufCodecs.BOOL.encode(buf, payload.isVehicle());
        ByteBufCodecs.VAR_INT.encode(buf, payload.index());
        ByteBufCodecs.VAR_LONG.encode(buf, payload.amount());
    }

    private static MarketPayload decode(RegistryFriendlyByteBuf buf) {
        int x = ByteBufCodecs.VAR_INT.decode(buf);
        int y = ByteBufCodecs.VAR_INT.decode(buf);
        int z = ByteBufCodecs.VAR_INT.decode(buf);
        int action = ByteBufCodecs.VAR_INT.decode(buf);
        long listing = ByteBufCodecs.VAR_LONG.decode(buf);
        int mode = ByteBufCodecs.VAR_INT.decode(buf);
        boolean isVehicle = ByteBufCodecs.BOOL.decode(buf);
        int index = ByteBufCodecs.VAR_INT.decode(buf);
        long amount = ByteBufCodecs.VAR_LONG.decode(buf);
        return new MarketPayload(x, y, z, action, listing, mode, isVehicle, index, amount);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(MarketPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            BlockPos pos = new BlockPos(payload.x(), payload.y(), payload.z());
            // 距离校验：和界面一样 8 格
            if (player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) > 64.0D) {
                return;
            }
            if (!(player.level().getBlockEntity(pos)
                    instanceof com.deepspacedeployer.blockentity.MachineBlockEntity machine)) {
                return;
            }
            if (!(player.containerMenu instanceof com.deepspacedeployer.menu.MachineMenu menu)) {
                return;
            }
            com.deepspacedeployer.market.MarketActions.handle(player, machine, menu, payload);
        });
    }
}
