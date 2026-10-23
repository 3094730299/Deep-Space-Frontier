package com.deepspacedeployer.menu;

import com.deepspacedeployer.blockentity.CurrencyBankBlockEntity;
import com.deepspacedeployer.economy.CardData;
import com.deepspacedeployer.economy.CurrencyBankService;
import com.deepspacedeployer.economy.WalletService;
import com.deepspacedeployer.registry.ModItems;
import com.deepspacedeployer.registry.ModMenus;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 深空货币银行菜单：<b>两个共享卡槽 + 玩家背包</b>。
 *
 * <p>老板 2026-10-21 的要求：银行卡要能从界面下方物品栏拖进卡槽，不能只靠右键。
 * 所以银行从纯自定义界面改成了真正的 {@link AbstractContainerMenu}：</p>
 * <ul>
 *   <li>槽 0/1 = 银行的两个共享卡槽（实际存在方块实体里，破坏方块会掉）；</li>
 *   <li>槽 2~37 = 玩家背包 3×9 + 快捷栏；</li>
 *   <li>银行卡放进卡槽时服务端绑定/登记；只有卡主能把卡拿走。</li>
 * </ul>
 */
public class CurrencyBankMenu extends AbstractContainerMenu {

    public static final int BANK_SLOTS = 2;
    private static final int PLAYER_INV_START = BANK_SLOTS;
    private static final int PLAYER_MAIN_START = BANK_SLOTS;          // 2..28
    private static final int PLAYER_HOTBAR_START = BANK_SLOTS + 27;   // 29..37

    /** 银行卡槽的界面坐标（相对 image 左上角） */
    public static final int BANK_SLOT_1_X = 20;
    public static final int BANK_SLOT_1_Y = 24;
    public static final int BANK_SLOT_2_X = 170;
    public static final int BANK_SLOT_2_Y = 24;

    /** 玩家背包坐标：3×9 + 快捷栏，底对齐 */
    public static final int PLAYER_INV_X = 79;
    public static final int PLAYER_MAIN_Y = 160;
    public static final int PLAYER_HOTBAR_Y = 214;

    private final Container bank;
    private final Player owner;

    /** 服务端：接方块实体。 */
    public CurrencyBankMenu(int windowId, Inventory playerInventory, CurrencyBankBlockEntity bank) {
        super(ModMenus.CURRENCY_BANK.get(), windowId);
        this.bank = bank;
        this.owner = playerInventory.player;
        setupSlots(playerInventory);
    }

    /** 客户端：只用于同步显示，两个卡槽用临时容器。 */
    public CurrencyBankMenu(int windowId, Inventory playerInventory) {
        super(ModMenus.CURRENCY_BANK.get(), windowId);
        this.bank = new SimpleContainer(BANK_SLOTS);
        this.owner = playerInventory.player;
        setupSlots(playerInventory);
    }

    private void setupSlots(Inventory playerInventory) {
        this.addSlot(new BankCardSlot(this.bank, 0, BANK_SLOT_1_X, BANK_SLOT_1_Y, this.owner));
        this.addSlot(new BankCardSlot(this.bank, 1, BANK_SLOT_2_X, BANK_SLOT_2_Y, this.owner));

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9,
                        PLAYER_INV_X + col * 18, PLAYER_MAIN_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col,
                    PLAYER_INV_X + col * 18, PLAYER_HOTBAR_Y));
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        if (index < BANK_SLOTS && !slot.mayPickup(player)) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        if (index < BANK_SLOTS) {
            // 银行卡槽 → 背包
            if (!this.moveItemStackTo(stack, PLAYER_INV_START, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (ModItems.isBankCard(stack)) {
            // 背包里的银行卡优先塞进银行空卡槽
            if (this.moveItemStackTo(stack, 0, BANK_SLOTS, false)) {
                if (stack.isEmpty()) {
                    slot.set(ItemStack.EMPTY);
                } else {
                    slot.setChanged();
                }
                return copy;
            }
            // 塞不进去就照常在背包/快捷栏之间换位
            if (index < PLAYER_HOTBAR_START) {
                if (!this.moveItemStackTo(stack, PLAYER_HOTBAR_START, this.slots.size(), false)) {
                    return ItemStack.EMPTY;
                }
            } else if (!this.moveItemStackTo(stack, PLAYER_MAIN_START, PLAYER_HOTBAR_START, false)) {
                return ItemStack.EMPTY;
            }
        } else if (index < PLAYER_HOTBAR_START) {
            if (!this.moveItemStackTo(stack, PLAYER_HOTBAR_START, this.slots.size(), false)) {
                return ItemStack.EMPTY;
            }
        } else if (!this.moveItemStackTo(stack, PLAYER_MAIN_START, PLAYER_HOTBAR_START, false)) {
            return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        return this.bank.stillValid(player);
    }

    /** 非「转账/挂失」页面隐藏并禁用两个卡槽（客户端表现；服务端仍以实际操作为准）。 */
    public void setCardSlotsActive(boolean active) {
        for (int i = 0; i < BANK_SLOTS; i++) {
            if (this.slots.get(i) instanceof BankCardSlot cardSlot) {
                cardSlot.active = active;
            }
        }
    }

    /** 挂失页只需要一个卡槽：0 显示，1 隐藏；转账页两个都显示。 */
    public void setCardSlotActive(int index, boolean active) {
        if (index >= 0 && index < BANK_SLOTS
                && this.slots.get(index) instanceof BankCardSlot cardSlot) {
            cardSlot.active = active;
        }
    }

    @Override
    public void slotsChanged(Container container) {
        super.slotsChanged(container);
        if (this.bank instanceof CurrencyBankBlockEntity be
                && be.getLevel() != null && !be.getLevel().isClientSide) {
            be.sendNearby();
        }
    }

    /**
     * 银行卡槽：只收银行卡；放进去时绑定/登记；只有卡主能拿走。
     */
    private static final class BankCardSlot extends Slot {

        private final Player player;
        private boolean active = true;

        private BankCardSlot(Container container, int slot, int x, int y, Player player) {
            super(container, slot, x, y);
            this.player = player;
        }

        @Override
        public boolean isActive() {
            return this.active;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            // 卡槽只负责暂存；绑定/2 张上限在 set() 里处理，避免“怎么都放不进去”
            return ModItems.isBankCard(stack);
        }

        @Override
        public boolean mayPickup(Player player) {
            ItemStack stack = this.getItem();
            return stack.isEmpty()
                    || !CardData.hasAccount(stack)
                    || CardData.isBoundTo(stack, player);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public void set(ItemStack stack) {
            if (this.player instanceof ServerPlayer serverPlayer
                    && !serverPlayer.level().isClientSide && !stack.isEmpty()) {
                ItemStack one = stack.copyWithCount(1);
                if (!CardData.hasAccount(one)) {
                    // 未绑定：还有名额就顺手绑定/登记；没名额就只暂存，不占用银行转账
                    if (WalletService.canAdd(serverPlayer)) {
                        CardData.bindIfMissing(one, serverPlayer);
                        WalletService.ensureRegistered(serverPlayer, one);
                        CurrencyBankService.sendCardList(serverPlayer);
                    } else {
                        serverPlayer.displayClientMessage(Component.translatable(
                                "msg.deepspacedeployer.bank_slot_unbound_limit")
                                .withStyle(ChatFormatting.RED), true);
                    }
                    super.set(one);
                    return;
                }
                // 已绑定：能登记就登记；废卡/登记失败也留在槽里，至少能取回
                if (!WalletService.isVoided(serverPlayer.server, one)) {
                    WalletService.ensureRegistered(serverPlayer, one);
                }
                super.set(one);
                CurrencyBankService.sendCardList(serverPlayer);
                return;
            }
            super.set(stack);
        }
    }
}
