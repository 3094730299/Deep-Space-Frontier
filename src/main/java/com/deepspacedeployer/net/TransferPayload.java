package com.deepspacedeployer.net;

import com.deepspacedeployer.DeepSpaceDeployer;
import com.deepspacedeployer.menu.TransferMenu;
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
 * 客户端 → 服务端：资金转化界面上的操作。
 *
 * <p>这个界面有<b>两张</b>银行卡（来源卡 / 目标卡），卡放在 {@link TransferMenu} 自己的容器里，
 * 所以服务端必须从<b>玩家当前打开的这个菜单</b>里取卡 ——
 * 客户端发过来的只是"动作编号 + 金额（或背包格子号 / 转给谁）"，改包也动不了别人的钱。</p>
 *
 * <p>字段有 7 个，原版 {@code StreamCodec.composite} 最多支持 6 个，所以这里手写编解码。</p>
 *
 * @param x      机器坐标（用来校验距离）
 * @param y      机器坐标
 * @param z      机器坐标
 * @param action 见 {@link #ACT_CARD_TO_CARD} 等
 * @param slot   只有 {@link #ACT_SLOT_SWAP} 用它：要装进卡槽的那张卡在玩家背包里的格子编号
 * @param amount 金额（服务端会裁剪到 [1, 10 亿]）；{@link #ACT_SLOT_SWAP} 时它放的是目标卡槽编号
 * @param name   只有 {@link #ACT_TRANSFER} 用它：转给谁（玩家名或 UUID）
 */
public record TransferPayload(int x, int y, int z, int action, int slot, long amount, String name)
        implements CustomPacketPayload {

    /** 卡1 → 卡2（跨卡转账，不收手续费） */
    public static final int ACT_CARD_TO_CARD = 1;
    /** 公司余额 → 目标卡 */
    public static final int ACT_TO_CARD = 3;
    /** 目标卡 → 公司余额 */
    public static final int ACT_FROM_CARD = 5;
    /** 兑换：目标卡上其它公司的余额 → 本公司（扣手续费） */
    public static final int ACT_EXCHANGE = 6;
    /** 报余额（只发一条聊天消息，不改钱） */
    public static final int ACT_REPORT = 7;
    /** 返回商店界面 */
    public static final int ACT_BACK = 8;
    /** 换卡：把玩家背包里的某张银行卡拎到鼠标上（右键空卡槽触发） */
    public static final int ACT_SLOT_SWAP = 9;
    /** 转账：把本公司余额转给另一个玩家（name = 玩家名/UUID） */
    public static final int ACT_TRANSFER = 10;

    public static final CustomPacketPayload.Type<TransferPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(DeepSpaceDeployer.MOD_ID, "transfer_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TransferPayload> STREAM_CODEC =
            StreamCodec.of(TransferPayload::encode, TransferPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buf, TransferPayload payload) {
        ByteBufCodecs.VAR_INT.encode(buf, payload.x());
        ByteBufCodecs.VAR_INT.encode(buf, payload.y());
        ByteBufCodecs.VAR_INT.encode(buf, payload.z());
        ByteBufCodecs.VAR_INT.encode(buf, payload.action());
        ByteBufCodecs.VAR_INT.encode(buf, payload.slot());
        ByteBufCodecs.VAR_LONG.encode(buf, payload.amount());
        ByteBufCodecs.STRING_UTF8.encode(buf, payload.name() == null ? "" : payload.name());
    }

    private static TransferPayload decode(RegistryFriendlyByteBuf buf) {
        int x = ByteBufCodecs.VAR_INT.decode(buf);
        int y = ByteBufCodecs.VAR_INT.decode(buf);
        int z = ByteBufCodecs.VAR_INT.decode(buf);
        int action = ByteBufCodecs.VAR_INT.decode(buf);
        int slot = ByteBufCodecs.VAR_INT.decode(buf);
        long amount = ByteBufCodecs.VAR_LONG.decode(buf);
        String name = ByteBufCodecs.STRING_UTF8.decode(buf);
        return new TransferPayload(x, y, z, action, slot, amount, name);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(TransferPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            BlockPos pos = new BlockPos(payload.x(), payload.y(), payload.z());
            // 距离校验：只有站在机器旁边才能操作（和界面一样是 8 格）
            if (player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) > 64.0D) {
                return;
            }
            if (!(player.containerMenu instanceof TransferMenu menu)) {
                // 界面已经关了：什么都不做，避免出现"看不见的界面还在花钱"
                return;
            }
            if (!menu.stillValid(player)) {
                player.closeContainer();
                return;
            }
            menu.handleTransfer(player, payload.action(), payload.slot(), payload.amount(), payload.name());
        });
    }
}
