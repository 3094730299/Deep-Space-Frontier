package com.deepspacedeployer.menu;

import com.deepspacedeployer.Company;
import com.deepspacedeployer.blockentity.MachineBlockEntity;
import com.deepspacedeployer.economy.CardData;
import com.deepspacedeployer.net.TransferPayload;
import com.deepspacedeployer.net.TransferSyncPayload;
import com.deepspacedeployer.registry.ModItems;
import com.deepspacedeployer.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * 资金转化界面的菜单：两个银行卡展示槽 + 金额操作。
 *
 * <p>两张卡放在菜单自带的容器里（<b>不写进世界</b>），界面关掉时由服务端还给玩家 ——
 * 这样"从一张卡转到另一张卡"才有地方放两张卡。</p>
 *
 * <p>两个卡槽是<b>只读</b>的：生存模式没有"客户端改槽位"的合法通道，
 * 做成只读之后卡的内容以服务端为准，每次变动都用
 * {@link TransferSyncPayload} 推给客户端显示，两边不可能不一致。</p>
 *
 * <p>钱的操作全部走 {@link TransferPayload} → {@link #handleTransfer}，服务端说了算。</p>
 */
public class TransferMenu extends AbstractContainerMenu {

    /** 卡槽格数（0 = 来源卡，1 = 目标卡） */
    public static final int CARD_SLOTS = 2;
    /** 玩家背包起点（和机器界面保持一致，底部对齐） */
    private static final int PLAYER_INV_Y = 186;
    private static final int PLAYER_INV_X = 63;

    /** 卡1（来源）在界面上的位置 */
    public static final int CARD1_X = 60;
    public static final int CARD1_Y = 60;
    /** 卡2（目标）在界面上的位置 */
    public static final int CARD2_X = 180;
    public static final int CARD2_Y = 60;

    private final SimpleContainer cards = new SimpleContainer(CARD_SLOTS);
    private final ContainerData data;

    /** 只有服务端有值：从哪台机器打开的（返回时要把卡放回去） */
    private final MachineBlockEntity machine;

    /** 服务端用：从方块实体打开 */
    public TransferMenu(int windowId, Inventory playerInventory, MachineBlockEntity machine) {
        super(ModMenus.TRANSFER.get(), windowId);
        this.machine = machine;
        this.data = machine.createDataAccess();
        addDataSlots(this.data);
        setupSlots(playerInventory);
    }

    /** 客户端用：只有数据槽（公司/余额/坐标都是同步过来的） */
    public TransferMenu(int windowId, Inventory playerInventory) {
        super(ModMenus.TRANSFER.get(), windowId);
        this.machine = null;
        this.data = new SimpleContainerData(MachineMenu.DATA_COUNT);
        addDataSlots(this.data);
        setupSlots(playerInventory);
    }

    private void setupSlots(Inventory playerInventory) {
        // 两张卡是"只读展示槽"：卡放在服务端的容器里，客户端不能拖动 / 拿出
        // （生存模式没有"客户端改槽位"的合法通道，做成只读就不会出现两边不一致）
        addCardSlot(0, CARD1_X, CARD1_Y);
        addCardSlot(1, CARD2_X, CARD2_Y);

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9,
                        PLAYER_INV_X + col * 18, PLAYER_INV_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, PLAYER_INV_X + col * 18, PLAYER_INV_Y + 58));
        }
    }

    private void addCardSlot(int index, int x, int y) {
        this.addSlot(new Slot(this.cards, index, x, y) {
            /**
             * 老板要求："资金转化不要右键放置，就用鼠标挪上去" —— 所以卡槽做成普通可交互槽：
             * 左键拿/放卡走原版 {@code doClick}（服务端校验），和原版箱子一模一样的手感。
             *
             * <p>只收银行卡；拿走的卡会回到背包（关界面时服务端会把两张卡都还给你）。</p>
             */
            @Override
            public boolean mayPlace(ItemStack stack) {
                return ModItems.isBankCard(stack);
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }
        });
    }

    /**
     * 关界面时把两张卡还给玩家（背包满了就掉在脚边，绝不凭空消失）。
     *
     * <p>「返回商店」走的是另一条路（第一张卡插回机器），那时卡槽已经是空的，这里什么都不会发生。</p>
     *
     * <p>光标上那张卡<b>不在这里处理</b>：原版 {@code AbstractContainerMenu#removed} 对服务端玩家
     * 已经做了"把光标上的东西放回背包 + 清空光标"，我们再做一次只会是死代码。</p>
     */
    @Override
    public void removed(Player player) {
        super.removed(player);
        if (player.level().isClientSide) {
            return;
        }
        for (int i = 0; i < CARD_SLOTS; i++) {
            ItemStack stack = this.cards.removeItemNoUpdate(i);
            if (!stack.isEmpty() && !player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
        }
    }

    public ItemStack card(int index) {
        return this.cards.getItem(index);
    }

    /**
     * 服务端：把某张卡放进卡槽（卡槽只读，客户端改不动；放完要发
     * {@link com.deepspacedeployer.net.TransferSyncPayload} 让界面显示出来）。
     */
    public void putCard(int index, ItemStack stack) {
        if (index < 0 || index >= CARD_SLOTS) {
            return;
        }
        this.cards.setItem(index, stack == null ? ItemStack.EMPTY : stack.copyWithCount(1));
        this.slots.get(index).set(this.cards.getItem(index));
    }

    /** 服务端：拿走某张卡（卡已经被搬到机器卡槽 / 玩家背包了） */
    public ItemStack takeCard(int index) {
        if (index < 0 || index >= CARD_SLOTS) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = this.cards.removeItemNoUpdate(index);
        this.slots.get(index).set(ItemStack.EMPTY);
        return stack;
    }

    /** 服务端：把两张卡的真实状态推给客户端（每次给卡 / 收卡之后都要调） */
    public void syncCards(ServerPlayer player) {
        List<ItemStack> cards = new ArrayList<>(CARD_SLOTS);
        for (int i = 0; i < CARD_SLOTS; i++) {
            cards.add(this.cards.getItem(i).copyWithCount(1));
        }
        PacketDistributor.sendToPlayer(player, new TransferSyncPayload(cards));
    }

    /** 服务端拿方块实体（客户端返回 null） */
    public MachineBlockEntity machine() {
        return this.machine;
    }

    /**
     * 服务端：右键卡槽时，把玩家背包里的银行卡拎到光标上（"我要用另一张卡"）。
     *
     * <p>只搬一张、而且只搬银行卡 —— 之后放哪个卡槽由玩家自己左键点，
     * 服务端不需要知道目标卡槽，也就少一处可以改包的地方。</p>
     *
     * <p><b>副卡要点</b>：右键的是"卡1"槽就优先拎<b>主卡</b>，右键"卡2"槽就优先拎<b>副卡</b>，
     * 这样"卡1 → 卡2 给副卡充钱"不用来回翻背包。找不到就先拎另一张（再点一下就能换）。</p>
     *
     * @param preferSlot 右键的那个卡槽编号（0=卡1，1=卡2；-1 表示无所谓）
     * @return 拎起来了没有
     */
    public boolean pickUpCardFrom(ServerPlayer player, int preferSlot) {
        if (!this.getCarried().isEmpty()) {
            // 手上已经拿着东西了：先放下再说（客户端也会拦，这里是服务端兜底）
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.transfer_hand_busy"), true);
            return false;
        }
        boolean wantSub = preferSlot == 1;
        // 第一优先：另一张卡槽里有没有正合适的卡（直接挪过来）
        int other = preferSlot == 0 ? 1 : 0;
        if (preferSlot >= 0 && other < CARD_SLOTS) {
            ItemStack there = this.cards.getItem(other);
            if (!there.isEmpty() && ModItems.isSubCard(there) == wantSub) {
                this.cards.setItem(other, ItemStack.EMPTY);
                this.setCarried(there);
                player.displayClientMessage(Component.translatable("msg.deepspacedeployer.transfer_card_picked",
                        there.getHoverName()), true);
                return true;
            }
        }
        // 第二优先：背包里匹配卡槽类型的卡；没有再退而求其次拿任意一张
        for (int pass = 0; pass < 2; pass++) {
            boolean requireMatch = pass == 0;
            int size = player.getInventory().getContainerSize();
            for (int i = 0; i < size; i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (stack.isEmpty() || !ModItems.isBankCard(stack)) {
                    continue;
                }
                if (requireMatch && preferSlot >= 0 && ModItems.isSubCard(stack) != wantSub) {
                    continue;
                }
                ItemStack one = stack.copyWithCount(1);
                // count==1 时不要往槽里写一个 count=0 的栈（原版某些分支会把它当"有东西"）
                player.getInventory().setItem(i,
                        stack.getCount() <= 1 ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - 1));
                this.setCarried(one);
                player.displayClientMessage(Component.translatable("msg.deepspacedeployer.transfer_card_picked",
                        one.getHoverName()), true);
                return true;
            }
        }
        player.displayClientMessage(Component.translatable("msg.deepspacedeployer.transfer_no_card_in_bag"), true);
        return false;
    }

    public int dataValue(int index) {
        return this.data.get(index);
    }

    /** 卡槽可以拖动了，所以 shift-click 也要能把卡槽里的卡挪回背包 */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        if (index < CARD_SLOTS) {
            // 卡槽 → 背包
            if (!this.moveItemStackTo(stack, CARD_SLOTS, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (ModItems.isBankCard(stack) && this.slots.get(0).getItem().isEmpty()) {
            // 背包里的银行卡 → 第一个空卡槽
            if (!this.moveItemStackTo(stack, 0, CARD_SLOTS, false)) {
                return ItemStack.EMPTY;
            }
        } else if (index < CARD_SLOTS + 27) {
            if (!this.moveItemStackTo(stack, CARD_SLOTS + 27, this.slots.size(), false)) {
                return ItemStack.EMPTY;
            }
        } else if (!this.moveItemStackTo(stack, CARD_SLOTS, CARD_SLOTS + 27, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }

    /** 距离校验：8 格以内（坐标从数据槽取，客户端也有） */
    @Override
    public boolean stillValid(Player player) {
        BlockPos pos = new BlockPos(this.data.get(MachineMenu.DATA_POS_X),
                this.data.get(MachineMenu.DATA_POS_Y), this.data.get(MachineMenu.DATA_POS_Z));
        return player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= 64.0D;
    }

    // ---------------- 服务端：界面上的资金动作 ----------------

    /**
     * 资金转化界面上的动作分发（<b>只在服务端调用</b>）。
     *
     * <p>钱只有两个去处（票据物品已经删除）：<b>公司账户（世界存档）↔ 卡上余额</b>。
     * 卡上余额可以在卡之间转，公司账户可以直接转给别的玩家，跨公司要兑换（扣手续费）。</p>
     *
     * <p>做完动作后一律把两张卡的最新内容推给客户端（卡上的余额标签要跟着变）。</p>
     *
     * @param slot 只有 {@link TransferPayload#ACT_SLOT_SWAP} 用它：要装进卡槽的那张卡在背包里的格子编号
     * @param name 只有 {@link TransferPayload#ACT_TRANSFER} 用它：转给谁
     */
    public boolean handleTransfer(ServerPlayer player, int action, int slot, long amount, String name) {
        MachineBlockEntity machine = this.machine;
        if (machine == null) {
            // 客户端那份菜单没有方块实体，永远走不到这里
            return false;
        }
        if (action == TransferPayload.ACT_BACK) {
            // 返回商店：卡会被插回机器 / 还给玩家，不需要再同步卡槽
            return machine.backToMachine(player);
        }
        // 卡槽可以拖动了，所以卡有可能是"没绑定"的状态（调试指令塞进来的卡、从别的容器拖来的卡）。
        // 钱要记到卡主头上，这里统一补一次绑定：没绑的就绑到操作者。
        for (int i = 0; i < CARD_SLOTS; i++) {
            CardData.bindIfMissing(this.cards.getItem(i), player);
        }
        long safe = Math.max(1L, Math.min(1_000_000_000L, amount));
        boolean changed = switch (action) {
            // 卡1 → 卡2：两张卡各自的钱互转（给副卡充值 / 副卡划回主卡都走这条）
            case TransferPayload.ACT_CARD_TO_CARD -> machine.transferBetweenCards(player, source(), target(), safe);
            case TransferPayload.ACT_TO_CARD -> toCard(player, safe);
            case TransferPayload.ACT_FROM_CARD -> fromCard(player, safe);
            case TransferPayload.ACT_EXCHANGE -> exchange(player);
            case TransferPayload.ACT_REPORT -> report(player);
            case TransferPayload.ACT_SLOT_SWAP -> pickUpCardFrom(player, slot);
            // 转账：把本公司余额转给别的玩家（票据删了之后这就是"给钱"的唯一方式）
            case TransferPayload.ACT_TRANSFER -> machine.transferTo(player, target(), name, safe);
            default -> false;
        };
        syncCards(player);
        return changed;
    }

    /** 目标卡（卡2）；没插就是空栈 */
    private ItemStack target() {
        return this.cards.getItem(1);
    }

    /** 来源卡（卡1）；没插就是空栈 */
    private ItemStack source() {
        return this.cards.getItem(0);
    }

    /** 公司账户 → 目标卡 */
    private boolean toCard(ServerPlayer player, long amount) {
        ItemStack to = target();
        if (to.isEmpty()) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.transfer_need_target"), true);
            return false;
        }
        return this.machine.moveAccountToCard(player, to, amount);
    }

    /** 目标卡 → 公司账户（记在卡主名下） */
    private boolean fromCard(ServerPlayer player, long amount) {
        ItemStack to = target();
        if (to.isEmpty()) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.transfer_need_target"), true);
            return false;
        }
        return this.machine.moveCardToAccount(player, to, amount);
    }

    /** 目标卡上其它公司的余额 → 本公司（扣手续费） */
    private boolean exchange(ServerPlayer player) {
        ItemStack to = target();
        if (to.isEmpty()) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.transfer_need_target"), true);
            return false;
        }
        return this.machine.exchangeOn(player, to, this.machine.company());
    }

    /** 报余额：公司账户 + 两张卡 */
    private boolean report(ServerPlayer player) {
        ItemStack to = target();
        long local = this.machine.activeLocalBalance();
        long onSource = CardData.getBalance(source(), this.machine.company());
        long onTarget = CardData.getBalance(to, this.machine.company());
        player.displayClientMessage(Component.translatable("msg.deepspacedeployer.transfer_report",
                this.machine.activeAccountName(), local, onSource, onTarget), false);
        return true;
    }
}
