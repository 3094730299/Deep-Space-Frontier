package com.deepspacedeployer.block;

import com.deepspacedeployer.blockentity.PrecisionFabricatorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 精构仪 —— <b>1 × 1 × 5 标准多方块</b>（老板 2026-10-19 新建模）。
 *
 * <p><b>结构</b>：正中间 1 格是主体（本方块），左右各 2 格是部件格
 * （{@link PrecisionFabricatorPartBlock}），见 {@code PrecisionFabricatorStructure}。
 * 部件格隐形、各自占满本格，所以整台机器的碰撞箱是连续的 5 格；
 * 主体自己只占<b>标准 1 格</b>。</p>
 *
 * <p><b>朝向</b>：老板要求"<b>跟着玩家东南西北转</b>" —— 所以有
 * {@link BlockStateProperties#HORIZONTAL_FACING} 四个朝向，放下时<b>正面对玩家</b>，
 * 并且支持结构旋转/镜像（{@link #rotate}/{@link #mirror}）。
 * 4 个部件格的位置也跟着这个朝向转（左右铺开）。
 * GeckoLib 的 {@code GeoBlockRenderer} 会自动读这个属性把模型转过来。</p>
 *
 * <p><b>画法</b>：模型全部交给 GeckoLib（{@code PrecisionFabricatorRenderer}），
 * 方块本体设成 {@link RenderShape#INVISIBLE}；没装 GeckoLib 时退回普通方块（不隐形）。</p>
 *
 * <p><b>放置/破坏</b>：和载具打印机同一套规矩 —— 放下时 5 格全有或全无；
 * 挖任意一格整组消失，并掉落本体。</p>
 */
public class PrecisionFabricatorBlock extends Block implements EntityBlock {

    /** 四个水平朝向（和别的机器一致：放下来正面对玩家） */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public PrecisionFabricatorBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /**
     * 放下来时<b>正面对玩家</b>（和收购投送机/商店一致），并且把整台 1×1×5 一次摆好。
     *
     * <p>★ 全有或全无（和载具打印机一致）★ —— 5 格里只要有一格放不下
     * （被别的方块/别的多方块占着），就<b>整台不放下</b>，连主体也不放。
     * 返回 {@code null} ⇒ 原版 {@code BlockItem.place} 直接 FAIL：
     * 什么都不放、也不消耗物品。</p>
     */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        if (!PrecisionFabricatorPartBlock.canPlaceAll(
                context.getLevel(), context.getClickedPos(), facing)) {
            return null;
        }
        return defaultBlockState().setValue(FACING, facing);
    }

    /**
     * 主体放好后，把左右各 2 个部件格补上（主体自己已经由原版摆好）。
     */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer,
                            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide) {
            return;
        }
        PrecisionFabricatorPartBlock.placeAll(level, pos, state.getValue(FACING));
    }

    /**
     * 挖主体 → 4 个部件格一起消失（主体自己由原版流程移除并按掉落表掉本体）。
     */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide) {
            PrecisionFabricatorPartBlock.removeAll(level, pos, state.getValue(FACING));
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /**
     * 非玩家途径（爆炸 / 指令 / 替换方块）拆掉主体时，也把 4 个部件格清掉，
     * 免得留下 4 个隐形、还占着碰撞箱的孤儿格。
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
                            boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide) {
            PrecisionFabricatorPartBlock.removeAll(level, pos, state.getValue(FACING));
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PrecisionFabricatorBlockEntity(pos, state);
    }

    /**
     * 主体碰撞：<b>标准 1 格</b>。
     *
     * <p>整台机器横跨 5 格，但碰撞由「主体 1 格 + 4 个部件格各 1 格」拼出来，
     * 主体自己不再画 5 格宽的大碰撞箱（那是旧版单方块时的临时做法）。</p>
     */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
                                  CollisionContext context) {
        return Shapes.block();
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                           CollisionContext context) {
        return Shapes.block();
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        // 只有 GeckoLib 渲染器真的注册上了，才隐藏本体 —— 否则会变成隐形方块
        if (com.deepspacedeployer.client.gecko.UniversalTerminalGeoSetup.isRendererRegistered()) {
            return RenderShape.INVISIBLE;
        }
        return RenderShape.MODEL;
    }
}
