package com.deepspacedeployer.net;

import com.deepspacedeployer.DeepSpaceDeployer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * 服务端 → 客户端：资金转化界面里那两张卡的<b>真实状态</b>。
 *
 * <p>为什么要专门发这一包：卡放在服务端的菜单容器里（钱的操作全在服务端算），
 * 而原版在生存模式下<b>没有</b>"客户端把槽位内容告诉服务端"的通道
 * （{@code ServerboundSetCreativeModeSlotPacket} 只对创造模式玩家生效）。</p>
 *
 * <p>所以两张卡在界面上是<b>只读</b>的（不能拖动、不能拿出来），
 * 服务端每次给了卡 / 收了卡都发这一包刷新显示 —— 从根上就没有"两边不一致"的可能，
 * 也不会出现"卡被复制一份"的问题。</p>
 *
 * @param cards 两张卡的内容（固定两个元素，空位用空栈表示）
 */
public record TransferSyncPayload(List<ItemStack> cards) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<TransferSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(DeepSpaceDeployer.MOD_ID, "transfer_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TransferSyncPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ItemStack.OPTIONAL_LIST_STREAM_CODEC, TransferSyncPayload::cards,
                    TransferSyncPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(TransferSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.deepspacedeployer.client.ClientTransferBridge.acceptCards(payload.cards()));
    }
}
