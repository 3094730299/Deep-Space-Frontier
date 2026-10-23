package com.deepspacedeployer.block;

import com.deepspacedeployer.blockentity.CurrencyBankBlockEntity;
import com.deepspacedeployer.economy.CardData;
import com.deepspacedeployer.economy.CurrencyBankService;
import com.deepspacedeployer.menu.CurrencyBankMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 深空货币银行（全局方块，不绑公司、无等级）。
 *
 * <p><b>两个共享卡槽</b>：手持银行卡右键插卡并打开；空手右键打开；潜行右键取回自己的卡。
 * 界面是真正的 {@link CurrencyBankMenu}，下方有玩家背包，卡可以直接拖进两个卡槽。</p>
 */
public class CurrencyBankBlock extends Block implements EntityBlock {

    public CurrencyBankBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CurrencyBankBlockEntity(pos, state);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof CurrencyBankBlockEntity bank)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!CardData.isCard(stack)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            if (player.isShiftKeyDown()) {
                int moved = bank.removeCards(serverPlayer);
                serverPlayer.displayClientMessage(Component.translatable(
                        moved > 0 ? "msg.deepspacedeployer.bank_cards_removed" : "msg.deepspacedeployer.bank_no_cards",
                        moved), true);
                CurrencyBankService.sendCardList(serverPlayer);
            } else {
                bank.insertCard(serverPlayer, stack);
                openBank(serverPlayer, bank);
            }
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof CurrencyBankBlockEntity bank)) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            if (player.isShiftKeyDown()) {
                int moved = bank.removeCards(serverPlayer);
                serverPlayer.displayClientMessage(Component.translatable(
                        moved > 0 ? "msg.deepspacedeployer.bank_cards_removed" : "msg.deepspacedeployer.bank_no_cards",
                        moved), true);
                CurrencyBankService.sendCardList(serverPlayer);
            } else {
                openBank(serverPlayer, bank);
            }
        }
        return InteractionResult.SUCCESS;
    }

    private static void openBank(ServerPlayer player, CurrencyBankBlockEntity bank) {
        player.openMenu(new SimpleMenuProvider(
                (windowId, inventory, p) -> new CurrencyBankMenu(windowId, inventory, bank),
                Component.translatable("gui.deepspacedeployer.bank_title")));
        bank.sendTo(player);
        CurrencyBankService.sendCardList(player);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof CurrencyBankBlockEntity bank) {
            bank.dropAll();
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof CurrencyBankBlockEntity bank) {
            bank.dropAll();
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
