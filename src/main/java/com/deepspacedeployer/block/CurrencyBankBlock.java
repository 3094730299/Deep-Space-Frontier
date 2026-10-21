package com.deepspacedeployer.block;

import com.deepspacedeployer.blockentity.CurrencyBankBlockEntity;
import com.deepspacedeployer.client.ClientScreenBridge;
import com.deepspacedeployer.economy.CardData;
import com.deepspacedeployer.economy.CurrencyBankService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
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
 * <p><b>两个共享卡槽</b>：手持银行卡右键插卡；空手右键打开；潜行右键取回自己的卡。
 * 两个玩家可以分别插一张卡，打开同一台银行做当面转账。</p>
 *
 * <p>实体币存取要等 P0-3 的 36 种币，这里先做数字货币。</p>
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
        if (level.isClientSide()) {
            if (!player.isShiftKeyDown()) {
                ClientScreenBridge.openCurrencyBankScreen();
            }
        } else if (player instanceof ServerPlayer serverPlayer) {
            if (player.isShiftKeyDown()) {
                int moved = bank.removeCards(serverPlayer);
                serverPlayer.displayClientMessage(Component.translatable(
                        moved > 0 ? "msg.deepspacedeployer.bank_cards_removed" : "msg.deepspacedeployer.bank_no_cards",
                        moved), true);
            } else {
                bank.insertCard(serverPlayer, stack);
                bank.sendTo(serverPlayer);
            }
            CurrencyBankService.sendCardList(serverPlayer);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof CurrencyBankBlockEntity bank)) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            if (!player.isShiftKeyDown()) {
                ClientScreenBridge.openCurrencyBankScreen();
            }
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            if (player.isShiftKeyDown()) {
                int moved = bank.removeCards(serverPlayer);
                serverPlayer.displayClientMessage(Component.translatable(
                        moved > 0 ? "msg.deepspacedeployer.bank_cards_removed" : "msg.deepspacedeployer.bank_no_cards",
                        moved), true);
            } else {
                bank.sendTo(serverPlayer);
            }
            CurrencyBankService.sendCardList(serverPlayer);
        }
        return InteractionResult.SUCCESS;
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
