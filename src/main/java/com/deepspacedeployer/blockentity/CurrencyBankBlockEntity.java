package com.deepspacedeployer.blockentity;

import com.deepspacedeployer.economy.CardData;
import com.deepspacedeployer.economy.CardRegistry;
import com.deepspacedeployer.economy.CompanyFunds;
import com.deepspacedeployer.economy.WalletService;
import com.deepspacedeployer.net.BankStatePayload;
import com.deepspacedeployer.registry.ModBlockEntities;
import com.deepspacedeployer.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 深空货币银行方块实体：<b>两个共享卡槽</b>。
 *
 * <p>两个玩家可以把各自的卡插进同一台银行，打开同一个界面；界面里的
 * “我的卡”由卡主 UUID 判断。转换/转账都从自己的卡出，转移到对方卡（或名字）进。</p>
 */
public class CurrencyBankBlockEntity extends BlockEntity implements Container {

    public static final int SLOTS = 2;

    private final NonNullList<ItemStack> cards = NonNullList.withSize(SLOTS, ItemStack.EMPTY);

    public CurrencyBankBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CURRENCY_BANK.get(), pos, state);
    }

    private MinecraftServer server() {
        return level == null ? null : level.getServer();
    }

    // ---------------- 卡槽操作 ----------------

    /** 插卡：找一个空槽；绑定/登记后放进去。成功时扣掉玩家手里 1 张。 */
    public boolean insertCard(ServerPlayer player, ItemStack held) {
        if (held.isEmpty() || !CardData.isCard(held)) {
            return false;
        }
        int slot = firstEmpty();
        if (slot < 0) {
            player.displayClientMessage(Component.translatable(
                    "msg.deepspacedeployer.bank_slots_full"), true);
            return false;
        }
        if (WalletService.isVoided(player.server, held)) {
            // 废卡也允许暂存/取回，只是不能作为转账来源；界面会标灰
            ItemStack stored = held.copyWithCount(1);
            cards.set(slot, stored);
            held.shrink(1);
            setChanged();
            sendNearby();
            return true;
        }
        ItemStack stored = held.copyWithCount(1);
        if (!CardData.hasAccount(stored)) {
            if (WalletService.canAdd(player)) {
                CardData.bindIfMissing(stored, player);
                WalletService.ensureRegistered(player, stored);
            } else {
                player.displayClientMessage(Component.translatable(
                        "msg.deepspacedeployer.bank_slot_unbound_limit"), true);
            }
        } else {
            WalletService.ensureRegistered(player, stored);
        }
        cards.set(slot, stored);
        held.shrink(1);
        setChanged();
        sendNearby();
        return true;
    }

    /** 取回自己插在银行里的卡（潜行右键银行）。 */
    public int removeCards(ServerPlayer player) {
        int moved = 0;
        for (int i = 0; i < SLOTS; i++) {
            ItemStack card = cards.get(i);
            if (card.isEmpty()) {
                continue;
            }
            // 未绑定的暂存卡谁都能取回；已绑定的只有卡主能取
            if (CardData.hasAccount(card) && !CardData.isBoundTo(card, player)) {
                continue;
            }
            if (!player.getInventory().add(card.copy())) {
                player.drop(card.copy(), false);
            }
            cards.set(i, ItemStack.EMPTY);
            moved++;
        }
        if (moved > 0) {
            setChanged();
            sendNearby();
        }
        return moved;
    }

    /** 银行里属于这个玩家的第一张卡（转换/转出用）。 */
    public ItemStack cardFor(ServerPlayer player) {
        for (ItemStack card : cards) {
            if (!card.isEmpty() && CardData.isBoundTo(card, player)) {
                return card;
            }
        }
        return ItemStack.EMPTY;
    }

    /** 银行里另一个玩家的卡（当面转给对方用；没有就空）。 */
    public ItemStack otherCardFor(ServerPlayer player) {
        for (ItemStack card : cards) {
            if (!card.isEmpty() && !CardData.isBoundTo(card, player)) {
                return card;
            }
        }
        return ItemStack.EMPTY;
    }

    private int firstEmpty() {
        for (int i = 0; i < SLOTS; i++) {
            if (cards.get(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    // ---------------- 状态同步 ----------------

    public void sendTo(ServerPlayer player) {
        CompanyFunds funds = CompanyFunds.get(player.server);
        List<BankStatePayload.Slot> slots = new ArrayList<>(SLOTS);
        for (ItemStack card : cards) {
            boolean empty = card.isEmpty();
            boolean mine = !empty && CardData.isBoundTo(card, player);
            boolean voided = !empty && WalletService.isVoided(player.server, card);
            UUID account = empty ? null : CardData.accountId(card);
            String ownerName = "";
            if (!empty) {
                ownerName = account == null
                        ? Component.translatable("gui.deepspacedeployer.bank_unbound").getString()
                        : CardData.accountName(card);
            }
            long slotTotal = account != null && !voided ? funds.totalAssets(account) : 0L;
            long[] slotBalances = account != null && !voided
                    ? funds.snapshot(account)
                    : new long[CompanyFunds.SLOT_COUNT];
            slots.add(new BankStatePayload.Slot(
                    empty,
                    empty ? ItemStack.EMPTY : card.copyWithCount(1),
                    empty ? "" : displayName(card),
                    ownerName,
                    account == null ? "" : account.toString(),
                    mine,
                    voided,
                    slotTotal,
                    slotBalances));
        }

        ItemStack mineCard = cardFor(player);
        long[] balances = new long[CompanyFunds.SLOT_COUNT];
        long total = 0L;
        String accountName = "";
        String cardName = "";
        boolean voided = false;
        if (!mineCard.isEmpty()) {
            accountName = CardData.accountName(mineCard);
            cardName = displayName(mineCard);
            UUID account = CardData.accountId(mineCard);
            voided = WalletService.isVoided(player.server, mineCard);
            if (account != null && !voided) {
                balances = funds.snapshot(account);
                total = funds.totalAssets(account);
            }
        }

        PacketDistributor.sendToPlayer(player, new BankStatePayload(
                List.copyOf(slots), balances, total, accountName, cardName, voided));
    }

    /** 银行里的卡变了：把新状态发给 16 格内的玩家（两边界面都会刷新）。 */
    public void sendNearby() {
        MinecraftServer server = server();
        if (server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() == level
                    && player.distanceToSqr(worldPosition.getX() + 0.5D,
                    worldPosition.getY() + 0.5D, worldPosition.getZ() + 0.5D) <= 256.0D) {
                sendTo(player);
            }
        }
    }

    private String displayName(ItemStack card) {
        String label = CardData.cardLabel(card);
        if (label.isEmpty()) {
            UUID cardId = CardData.cardId(card);
            MinecraftServer server = server();
            if (cardId != null && server != null) {
                CardRegistry.CardRecord record = CardRegistry.get(server).find(cardId);
                if (record != null) {
                    label = record.name();
                }
            }
        }
        if (label.isEmpty()) {
            label = Component.translatable("item.deepspacedeployer.bank_card").getString();
        }
        return label;
    }

    // ---------------- 掉落 / 存档 ----------------

    public void dropAll() {
        if (level == null || level.isClientSide) {
            return;
        }
        for (int i = 0; i < SLOTS; i++) {
            ItemStack card = cards.get(i);
            if (!card.isEmpty()) {
                Block.popResource(level, worldPosition, card.copy());
                cards.set(i, ItemStack.EMPTY);
            }
        }
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, cards, registries);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        ContainerHelper.loadAllItems(tag, cards, registries);
    }

    // ---------------- Container：给 CurrencyBankMenu 的两个卡槽用 ----------------

    @Override
    public int getContainerSize() {
        return SLOTS;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack card : cards) {
            if (!card.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot >= 0 && slot < SLOTS ? cards.get(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        return ContainerHelper.removeItem(cards, slot, amount);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(cards, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= SLOTS) {
            return;
        }
        cards.set(slot, stack);
        setChanged();
        // 菜单拖卡/Shift 快速移动不会经过 insertCard；这里补一次状态同步，
        // 否则刚放进去时槽位名/卡内总资产要等下一次操作才刷新。
        if (level != null && !level.isClientSide) {
            sendNearby();
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return level != null
                && level.getBlockEntity(worldPosition) == this
                && player.distanceToSqr(worldPosition.getX() + 0.5D,
                worldPosition.getY() + 0.5D, worldPosition.getZ() + 0.5D) <= 64.0D;
    }

    @Override
    public void clearContent() {
        cards.clear();
        setChanged();
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return ModItems.isBankCard(stack);
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }
}
