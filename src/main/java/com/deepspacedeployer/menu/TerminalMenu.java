package com.deepspacedeployer.menu;

import com.deepspacedeployer.blockentity.UniversalTerminalBlockEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 通用终端的菜单：6 个卡槽（= 6 家公司）+ 玩家背包。
 *
 * <p>按钮全部走原版 {@code clickMenuButton} 通道：购买 {@code 100 + 商品下标}，
 * 翻页 {@code 200/201} —— 和现有商店一致。</p>
 */
public class TerminalMenu extends AbstractContainerMenu {

    public static final int SLOT_COUNT = UniversalTerminalBlockEntity.SLOT_COUNT;
    public static final int ACTION_BUY_BASE = 100;
    public static final int ACTION_PAGE_PREV = 200;
    public static final int ACTION_PAGE_NEXT = 201;
    public static final int DATA_COUNT = 3;
    public static final int DATA_COMPANY = 0;
    public static final int DATA_HAS_COMPANY = 1;
    public static final int DATA_PAGE = 2;

    private final UniversalTerminalBlockEntity terminal;
    private final Container container;

    /** 客户端构造（网络同步用） */
    public TerminalMenu(int windowId, Inventory playerInventory, FriendlyByteBuf data) {
        this(windowId, playerInventory, resolve(playerInventory, data));
    }

    private static UniversalTerminalBlockEntity resolve(Inventory inventory, FriendlyByteBuf data) {
        var pos = data.readBlockPos();
        if (inventory.player.level().getBlockEntity(pos) instanceof UniversalTerminalBlockEntity be) {
            return be;
        }
        return new UniversalTerminalBlockEntity(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
    }

    public TerminalMenu(int windowId, Inventory playerInventory, UniversalTerminalBlockEntity terminal) {
        super(com.deepspacedeployer.registry.ModMenus.TERMINAL_UI.get(), windowId);
        this.terminal = terminal;
        this.container = terminal;

        // 6 个卡槽：2 行 × 3 列
        for (int i = 0; i < SLOT_COUNT; i++) {
            int col = i % 3;
            int row = i / 3;
            this.addSlot(new Slot(container, i, 26 + col * 22, 20 + row * 22) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return com.deepspacedeployer.economy.CardData.isCard(stack);
                }
            });
        }
        // 玩家背包
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 142));
        }
    }

    public UniversalTerminalBlockEntity terminal() {
        return terminal;
    }

    /** 数据槽：公司 / 有无所属 / 页 */
    public net.minecraft.world.inventory.ContainerData dataAccess() {
        return new net.minecraft.world.inventory.ContainerData() {
            @Override
            public int get(int index) {
                return switch (index) {
                    case DATA_COMPANY -> terminal.company().ordinal();
                    case DATA_HAS_COMPANY -> terminal.hasCompany() ? 1 : 0;
                    case DATA_PAGE -> terminal.page();
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) {
                if (index == DATA_PAGE) {
                    terminal.setPage(value);
                }
            }

            @Override
            public int getCount() {
                return DATA_COUNT;
            }
        };
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (player.level().isClientSide) {
            return true;
        }
        if (id == ACTION_PAGE_PREV) {
            terminal.setPage(Math.max(0, terminal.page() - 1));
            return true;
        }
        if (id == ACTION_PAGE_NEXT) {
            terminal.setPage(terminal.page() + 1);
            return true;
        }
        if (id >= ACTION_BUY_BASE) {
            int index = terminal.page() * UniversalTerminalBlockEntity.PAGE_SIZE + (id - ACTION_BUY_BASE);
            if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                return terminal.buy(sp, index);
            }
        }
        return false;
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        super.clicked(slotId, button, clickType, player);
        terminal.refreshCompany();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        if (index < SLOT_COUNT) {
            // 卡槽 → 背包
            if (!this.moveItemStackTo(stack, SLOT_COUNT, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // 背包 → 卡槽（只搬"卡"）
            if (!com.deepspacedeployer.economy.CardData.isCard(stack)
                    || !this.moveItemStackTo(stack, 0, SLOT_COUNT, false)) {
                return ItemStack.EMPTY;
            }
        }
        slot.setChanged();
        terminal.refreshCompany();
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        return container.stillValid(player);
    }
}
