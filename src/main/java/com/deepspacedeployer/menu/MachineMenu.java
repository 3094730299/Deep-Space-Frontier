package com.deepspacedeployer.menu;

import com.deepspacedeployer.blockentity.MachineBlockEntity;
import com.deepspacedeployer.blockentity.MachineKind;
import com.deepspacedeployer.economy.CardData;
import com.deepspacedeployer.registry.ModItems;
import com.deepspacedeployer.registry.ModMenus;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 四种功能方块共用的菜单：银行卡槽（+ 收购站的 9 个输入格）+ 玩家背包 + 一组数据槽。
 *
 * <p>按钮全部走原版 clickMenuButton 通道：购买商品用 {@code 100 + 商品下标}，翻页用 200/201。</p>
 */
public class MachineMenu extends AbstractContainerMenu {

    public static final int DATA_COUNT = 16;
    public static final int DATA_POS_X = 0;
    public static final int DATA_POS_Y = 1;
    public static final int DATA_POS_Z = 2;
    public static final int DATA_COMPANY = 3;
    public static final int DATA_ONLINE = 4;
    public static final int DATA_LOCAL_LOW = 5;
    public static final int DATA_LOCAL_HIGH = 6;
    public static final int DATA_HAS_ACCOUNT = 7;
    public static final int DATA_TIER = 8;
    public static final int DATA_REPUTATION = 9;
    /** 客户端数据同步完成的标记（防止用 (0,0,0) 去查方块实体） */
    public static final int DATA_SYNCED = 10;
    /** 有没有在地图上选过投放点 */
    public static final int DATA_TARGET_SET = 11;
    /** 收购站是不是自动收购（0 = 点击收购） */
    public static final int DATA_AUTO_SELL = 12;
    /** 工厂核心旁边有没有创造板条箱（= 材料无限 / 免费 / 秒装） */
    public static final int DATA_CREATIVE = 13;
    /** 收购站的头顶被方块挡住了（挡着就不工作，界面要提示玩家） */
    public static final int DATA_BLOCKED_ABOVE = 14;

    /**
     * 收购投送机为什么不工作的原因码（0=正常）。
     * 见 {@code MachineBlockEntity#buyerIdleReason()} —— 5 道关卡里卡在哪一道。
     */
    public static final int DATA_IDLE_REASON = 15;

    /**
     * 购买商品 / 选择蓝图：两种"列表下标"动作。
     *
     * <p>⚠️ 两个区间必须和 {@link #ACTION_UI_BASE} 分开，而且要在它<b>下面</b>：
     * 终端商品最多 100 条，蓝图数量不限 —— 早先把"选蓝图"也塞进 100+ ，
     * 结果蓝图超过 50 份时下标 ≥150 会被当成客户端动作吞掉（列表第 51 项点不动）。
     * 现在"选蓝图"单独用 1000+ 区间，并在服务端优先判断。</p>
     */
    public static final int ACTION_BUY_BASE = 100;
    /** 选择蓝图：1000 + 下标（数量不限，不受界面动作区间影响） */
    public static final int ACTION_BLUEPRINT_BASE = 1000;
    /**
     * 一键组装（id = 2）。
     *
     * <p>工厂核心<b>按公司分成 6 种方块</b>，组装费直接按"这台核心所属公司"结算，
     * 所以这里不需要带公司序号。</p>
     */
    public static final int ACTION_ASSEMBLE = 2;
    /** 打开「资金转化界面」（商店里的资金按钮都搬进那个界面了） */
    public static final int ACTION_OPEN_TRANSFER = 202;
    /**
     * 纯客户端界面动作的下界（服务端一律忽略）。
     *
     * <p>放在 100 段（购买）之上、200 段之下：购买下标最多到 100+99，（新的）选蓝图走 1000+，
     * 都不会撞进这个区间。</p>
     */
    public static final int ACTION_UI_BASE = 150;

    /** 玩家背包起点（界面从 166 加高到 186，给商店的金额输入框和按钮腾地方） */
    private static final int PLAYER_INV_Y = 186;
    /** 玩家背包左边距：界面宽 288，162 宽的背包居中 */
    private static final int PLAYER_INV_X = 63;

    private final MachineKind kind;
    private final ContainerData data;
    private final int machineSlotCount;

    /** 只有服务端有值 */
    private final MachineBlockEntity blockEntity;
    /** "这张卡不是你的"提示的节流（游戏刻），免得每帧刷屏 */
    private long lastCardHintTick = -100L;

    public MachineMenu(MenuType<MachineMenu> type, int windowId, Inventory playerInventory,
                       MachineBlockEntity blockEntity) {
        super(type, windowId);
        this.kind = blockEntity.kind();
        this.blockEntity = blockEntity;
        this.data = blockEntity.createDataAccess();
        this.machineSlotCount = slotCountFor(this.kind);
        setupSlots(playerInventory, blockEntity);
    }

    private MachineMenu(MenuType<MachineMenu> type, int windowId, Inventory playerInventory, MachineKind kind,
                        Container container, ContainerData data) {
        super(type, windowId);
        this.kind = kind;
        this.blockEntity = null;
        this.data = data;
        this.machineSlotCount = slotCountFor(kind);
        setupSlots(playerInventory, container);
    }

    /** 客户端：方块实体还没同步过来，先给个空容器，数据槽等服务器推送 */
    public static MachineMenu forClient(int windowId, Inventory playerInventory, MachineKind kind) {
        return new MachineMenu(ModMenus.get(kind), windowId, playerInventory, kind,
                new SimpleContainer(slotCountFor(kind)), new SimpleContainerData(DATA_COUNT));
    }

    /** 机器内部格数：终端只有 1 个卡槽；收购站 = 卡 + 9 输入；核心 = 卡 + 9 输入 + 剪贴板格；
     *  寄售市场 = 卡 + 待售格 + 以物易物需求格 + 取货格 */
    private static int slotCountFor(MachineKind kind) {
        return switch (kind) {
            case TERMINAL -> 1;
            case BUYER -> MachineBlockEntity.SLOT_INPUT_START + MachineBlockEntity.INPUT_SLOTS;
            case CORE, MARKET -> MachineBlockEntity.SLOT_COUNT;
        };
    }

    private void setupSlots(Inventory playerInventory, Container container) {
        // 银行卡槽
        this.addSlot(new Slot(container, MachineBlockEntity.SLOT_CARD, 8, 20) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return ModItems.isBankCard(stack);
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }

            /**
             * 卡可以被别人借去用，但只有卡主本人能把它从机器里拿走
             * （创造模式例外，方便调试/回收）。服务端也有一份同样的判断
             * （{@code MachineBlockEntity#canPickUpCard}），客户端这份只是让点击提前被拦下、
             * 顺便给一句解释。
             */
            @Override
            public boolean mayPickup(Player player) {
                if (MachineMenu.this.blockEntity == null) {
                    return true; // 客户端：真正的判定在服务端
                }
                boolean allowed = MachineMenu.this.blockEntity.canPickUpCard(player);
                if (!allowed) {
                    long now = player.level().getGameTime();
                    if (now - MachineMenu.this.lastCardHintTick > 20L) {
                        MachineMenu.this.lastCardHintTick = now;
                        player.displayClientMessage(Component.translatable(
                                "msg.deepspacedeployer.card_locked",
                                CardData.accountName(getItem())), true);
                    }
                }
                return allowed;
            }
        });

        // 收购站 / 工厂核心：9 个输入格（3×3）
        if (this.kind == MachineKind.BUYER || this.kind == MachineKind.CORE) {
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) {
                    int index = MachineBlockEntity.SLOT_INPUT_START + col + row * 3;
                    this.addSlot(new Slot(container, index, 8 + col * 18, 44 + row * 18));
                }
            }
        }

        // 工厂核心：剪贴板格（放一块机械动力剪贴板，缺料清单会被写进去）
        if (this.kind == MachineKind.CORE) {
            this.addSlot(new Slot(container, MachineBlockEntity.SLOT_CLIPBOARD, 245, 44) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return com.deepspacedeployer.integration.CreateClipboardBridge.isClipboard(stack);
                }

                @Override
                public int getMaxStackSize() {
                    return 1;
                }
            });
        }

        // 寄售市场：待售格 / 以物易物需求格 / 取货格
        if (this.kind == MachineKind.MARKET) {
            this.addSlot(new Slot(container, MachineBlockEntity.SLOT_MARKET_SELL, 200, 26));
            this.addSlot(new Slot(container, MachineBlockEntity.SLOT_MARKET_BARTER, 200, 46));
            this.addSlot(new Slot(container, MachineBlockEntity.SLOT_MARKET_COLLECT, 222, 66) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    // 取货格只出不进（原版 Slot 默认就是"能放"，所以这里明确挡掉）
                    return false;
                }
            });
        }

        // 玩家背包 3×9
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, PLAYER_INV_X + col * 18, PLAYER_INV_Y + row * 18));
            }
        }
        // 快捷栏
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, PLAYER_INV_X + col * 18, PLAYER_INV_Y + 58));
        }

        this.addDataSlots(this.data);
    }

    public MachineKind kind() {
        return kind;
    }

    public int machineSlotCount() {
        return machineSlotCount;
    }

    public int dataValue(int index) {
        return this.data.get(index);
    }

    /** 方块里"当前账户"的本地余额（钱存在方块里、按玩家分账） */
    public long localBalance() {
        long low = this.data.get(DATA_LOCAL_LOW) & 0xFFFFFFFFL;
        long high = (long) this.data.get(DATA_LOCAL_HIGH) << 32;
        return high | low;
    }

    public boolean hasAccount() {
        return this.data.get(DATA_HAS_ACCOUNT) != 0;
    }

    /** 方块有没有开机（插了卡） */
    public boolean online() {
        return this.data.get(DATA_ONLINE) != 0;
    }

    public int tierRank() {
        return this.data.get(DATA_TIER);
    }

    public int reputation() {
        return this.data.get(DATA_REPUTATION);
    }

    /** 有没有选过投放点 */
    public boolean hasAssemblyTarget() {
        return this.data.get(DATA_TARGET_SET) != 0;
    }

    /** 收购站是不是自动收购 */
    public boolean autoSell() {
        return this.data.get(DATA_AUTO_SELL) != 0;
    }

    /** 工厂核心旁边有没有创造板条箱（材料无限 / 免费 / 秒装） */
    public boolean creative() {
        return this.data.get(DATA_CREATIVE) != 0;
    }

    /**
     * 收购站的头顶被方块挡住了吗。
     *
     * <p>被挡住时这台机器<b>不工作</b>（不收购），界面要提示玩家
     * "上方被阻挡无法工作"。见 {@code MachineBlockEntity#blockedAbove()}。</p>
     */
    public boolean blockedAbove() {
        return this.data.get(DATA_BLOCKED_ABOVE) != 0;
    }

    /** 收购投送机为什么不工作的原因码（0 = 正常） */
    public int idleReason() {
        return this.data.get(DATA_IDLE_REASON);
    }

    /** 界面上的按钮：服务端在这里分派 */
    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (this.blockEntity != null) {
            return this.blockEntity.handleButton(player, id);
        }
        return false;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();

        if (index < this.machineSlotCount) {
            // 方块内部 → 背包
            if (!this.moveItemStackTo(stack, this.machineSlotCount, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (this.kind == MachineKind.CORE
                && com.deepspacedeployer.integration.CreateClipboardBridge.isClipboard(stack)
                && this.machineSlotCount > MachineBlockEntity.SLOT_CLIPBOARD
                && this.slots.get(MachineBlockEntity.SLOT_CLIPBOARD).getItem().isEmpty()) {
            // 核心 + 手里是剪贴板 + 剪贴板格空着 → 直接放进剪贴板格
            // （不这么写的话 shift 点剪贴板会先试卡槽，卡槽满了就整个失败）
            if (!this.moveItemStackTo(stack, MachineBlockEntity.SLOT_CLIPBOARD,
                    MachineBlockEntity.SLOT_CLIPBOARD + 1, false)) {
                return ItemStack.EMPTY;
            }
        } else if (ModItems.isBankCard(stack)) {
            // 背包里的卡 → 卡槽；顺便自动绑定到操作者（和右键插卡保持一致，
            // 否则会插进一张"没绑定"的卡，存钱时钱不知道记给谁）
            if (!this.moveItemStackTo(stack, 0, 1, false)) {
                return ItemStack.EMPTY;
            }
            if (!player.level().isClientSide) {
                ItemStack inSlot = this.slots.get(0).getItem();
                if (!inSlot.isEmpty()) {
                    CardData.bindIfMissing(inSlot, player);
                }
            }
        } else if (this.machineSlotCount > 1) {
            // 收购站：背包 → 输入格
            if (!this.moveItemStackTo(stack, 1, this.machineSlotCount, false)) {
                return ItemStack.EMPTY;
            }
        } else if (index < this.machineSlotCount + 27) {
            if (!this.moveItemStackTo(stack, this.machineSlotCount + 27, this.slots.size(), false)) {
                return ItemStack.EMPTY;
            }
        } else if (!this.moveItemStackTo(stack, this.machineSlotCount, this.machineSlotCount + 27, false)) {
            return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }

    /** 距离校验：8 格以内（位置从数据槽取，客户端也有） */
    @Override
    public boolean stillValid(Player player) {
        double x = this.data.get(DATA_POS_X) + 0.5D;
        double y = this.data.get(DATA_POS_Y) + 0.5D;
        double z = this.data.get(DATA_POS_Z) + 0.5D;
        double dx = player.getX() - x;
        double dy = player.getY() - y;
        double dz = player.getZ() - z;
        return dx * dx + dy * dy + dz * dz <= 64.0D;
    }
}
