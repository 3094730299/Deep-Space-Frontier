package com.deepspacedeployer.net;

import com.deepspacedeployer.DeepSpaceDeployer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 → 客户端：货币银行的共享状态（两个卡槽） + 查看者自己那张卡的账户数据。
 *
 * <p>每个玩家收到的 {@code slots} 里，{@code mine} 是按“这张卡是不是你的”算好的；
 * 两个玩家同时打开同一台银行时，都能看到两个卡槽，并知道哪张是自己的。</p>
 *
 * @param slots       固定 2 个卡槽（带卡物品图标）
 * @param balances    查看者卡内的 7 个账户余额（没卡/废卡为 0）
 * @param totalAssets 查看者卡的总资产（70% 折算）
 * @param accountName 查看者卡主名
 * @param cardName    查看者卡名
 * @param voided      查看者卡是不是废卡
 */
public record BankStatePayload(List<Slot> slots, long[] balances, long totalAssets,
                               String accountName, String cardName, boolean voided)
        implements CustomPacketPayload {

    public record Slot(boolean empty, ItemStack stack, String name, String ownerName,
                       String ownerUuid, boolean mine, boolean voided, long totalAssets,
                       long[] balances) {
    }

    public static final CustomPacketPayload.Type<BankStatePayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(DeepSpaceDeployer.MOD_ID, "bank_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BankStatePayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeVarInt(payload.slots().size());
                        for (Slot slot : payload.slots()) {
                            buf.writeBoolean(slot.empty());
                            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, slot.stack());
                            buf.writeUtf(slot.name() == null ? "" : slot.name(), 64);
                            buf.writeUtf(slot.ownerName() == null ? "" : slot.ownerName(), 64);
                            buf.writeUtf(slot.ownerUuid() == null ? "" : slot.ownerUuid(), 36);
                            buf.writeBoolean(slot.mine());
                            buf.writeBoolean(slot.voided());
                            buf.writeLong(slot.totalAssets());
                            for (long value : slot.balances()) {
                                buf.writeLong(value);
                            }
                        }
                        for (long value : payload.balances()) {
                            buf.writeLong(value);
                        }
                        buf.writeLong(payload.totalAssets());
                        buf.writeUtf(payload.accountName() == null ? "" : payload.accountName(), 64);
                        buf.writeUtf(payload.cardName() == null ? "" : payload.cardName(), 64);
                        buf.writeBoolean(payload.voided());
                    },
                    buf -> {
                        int size = Math.max(0, Math.min(4, buf.readVarInt()));
                        List<Slot> slots = new ArrayList<>(size);
                        for (int i = 0; i < size; i++) {
                            boolean empty = buf.readBoolean();
                            ItemStack stack = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                            String name = buf.readUtf(64);
                            String ownerName = buf.readUtf(64);
                            String ownerUuid = buf.readUtf(36);
                            boolean mine = buf.readBoolean();
                            boolean voided = buf.readBoolean();
                            long slotTotal = buf.readLong();
                            long[] slotBalances = new long[7];
                            for (int j = 0; j < slotBalances.length; j++) {
                                slotBalances[j] = buf.readLong();
                            }
                            slots.add(new Slot(empty, stack, name, ownerName, ownerUuid, mine, voided,
                                    slotTotal, slotBalances));
                        }
                        long[] balances = new long[7];
                        for (int i = 0; i < balances.length; i++) {
                            balances[i] = buf.readLong();
                        }
                        long total = buf.readLong();
                        String accountName = buf.readUtf(64);
                        String cardName = buf.readUtf(64);
                        boolean voided = buf.readBoolean();
                        return new BankStatePayload(List.copyOf(slots), balances, total,
                                accountName, cardName, voided);
                    });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BankStatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.deepspacedeployer.client.BankStateClient.accept(payload));
    }
}
