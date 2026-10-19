package com.deepspacedeployer.block;

import com.deepspacedeployer.multiblock.PrecisionFabricatorStructure;
import com.deepspacedeployer.multiblock.PrecisionFabricatorStructure.PartSlot;
import com.deepspacedeployer.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 「精构仪」多方块结构里的<b>部件格</b>（左右各 2 格，共 4 格）。
 *
 * <p>精构仪在世界里占 1 × 1 × 5 = 5 格，只有正中间 1 格是主体
 * （{@link PrecisionFabricatorBlock}，带方块实体、负责画整个模型），
 * 其余 4 格都是本方块 —— <b>它自己不带方块实体</b>，只记两样东西：</p>
 * <ul>
 *   <li>{@link #SLOT}：它在结构里的位置（左右第几格，见
 *       {@link PrecisionFabricatorStructure.PartSlot}）；</li>
 *   <li>{@link #FACING}：整台机器的朝向（和主体一致，用来把偏移旋转回去）。</li>
 * </ul>
 *
 * <p>这样"挖哪里整台都掉"就不用额外存数据，
 * 也不需要给部件注册方块实体类型（少一个崩溃点）。
 * 整套思路照抄载具打印机的 {@code PrinterPartBlock}，只是格数从 12 变成 4。</p>
 */
public class PrecisionFabricatorPartBlock extends Block {

    /** 部件格在结构里的位置（左右各 2 格） */
    public static final EnumProperty<PartSlot> SLOT =
            EnumProperty.create("slot", PartSlot.class);
    /** 整台机器的朝向（和主体一致） */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** 部件格碰撞：自己那一格给一个整方块（5 格连起来就是完整的 1×1×5） */
    private static final VoxelShape SHAPE = Shapes.block();

    public PrecisionFabricatorPartBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(SLOT, PartSlot.LEFT_2)
                .setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(SLOT, FACING);
    }

    /** 部件格不画自己（整个模型由主体那一格的 GeckoLib 渲染器统一画） */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
                                  CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                           CollisionContext context) {
        return SHAPE;
    }

    // ---------- 放置：由主体负责摆好整台机器，部件格自己不允许单独放置 ----------

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // 放出来的是主体物品，部件格只是结构填充
        return null;
    }

    // ---------- 位置换算 ----------

    /**
     * 由部件格的位置 + 它在结构里的偏移，反推<b>主体</b>的位置。
     *
     * <p>主体就是偏移 (0,0,0) 那一格。</p>
     */
    public static BlockPos mainPos(BlockState state, BlockPos pos) {
        Direction facing = state.getValue(FACING);
        int part = state.getValue(SLOT).ordinal();
        return pos.subtract(PrecisionFabricatorStructure.offset(part, facing));
    }

    /** 部件格从主体拿到偏移后，自己该摆什么状态 */
    public static BlockState stateFor(int part, Direction facing, BlockState base) {
        return base
                .setValue(SLOT, PartSlot.values()[part])
                .setValue(FACING, facing);
    }

    // ---------- 放下整台机器 ----------

    /**
     * 5 个格子（主体 + 4 个部件格）是不是都能放。
     *
     * <p>主体就是玩家点出来的那一格；放不下时整台都不放（和载具打印机的
     * 「缺一不放」一致），不会出现"主体在、部件格缺一块"的半截机器。</p>
     */
    public static boolean canPlaceAll(LevelReader level, BlockPos main, Direction facing) {
        if (!level.getBlockState(main).canBeReplaced()) {
            return false;
        }
        for (int part = 0; part < PrecisionFabricatorStructure.PARTS; part++) {
            BlockPos p = main.offset(PrecisionFabricatorStructure.offset(part, facing));
            if (!level.getBlockState(p).canBeReplaced()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 把 4 个部件格摆下去（主体那格由原版放置流程负责，这里不碰）。
     *
     * <p>能走到这里说明 {@link #canPlaceAll} 已经确认过 5 格全空，
     * 下面的 {@code canBeReplaced} 只是一层保险：万一有别的途径绕过了放置检查，
     * 也绝不吞掉别人的方块。</p>
     */
    public static void placeAll(Level level, BlockPos main, Direction facing) {
        if (level.isClientSide) {
            return;
        }
        BlockState base = ModBlocks.PRECISION_FABRICATOR_PART.get().defaultBlockState();
        for (int part = 0; part < PrecisionFabricatorStructure.PARTS; part++) {
            BlockPos p = main.offset(PrecisionFabricatorStructure.offset(part, facing));
            if (!level.getBlockState(p).canBeReplaced()) {
                continue;                     // 保险：不吞别人的方块
            }
            level.setBlock(p, stateFor(part, facing, base), Block.UPDATE_ALL);
        }
    }

    /** 拆掉 4 个部件格（主体由调用方处理） */
    public static void removeAll(Level level, BlockPos main, Direction facing) {
        if (level.isClientSide) {
            return;
        }
        for (int part = 0; part < PrecisionFabricatorStructure.PARTS; part++) {
            BlockPos p = main.offset(PrecisionFabricatorStructure.offset(part, facing));
            BlockState state = level.getBlockState(p);
            if (state.getBlock() instanceof PrecisionFabricatorPartBlock
                    && state.getValue(SLOT) == PartSlot.values()[part]
                    && state.getValue(FACING) == facing) {
                level.removeBlock(p, false);
            }
        }
    }

    // ---------- 破坏：挖任意一格 → 整组消失 + 掉本体 ----------

    /**
     * 挖掉任意一个部件格 → 整台精构仪一起消失，并<b>掉落本体</b>。
     *
     * <p>顺序很重要：先把 4 个部件格清掉，再销毁主体 ——
     * 主体走自己的掉落表（{@code loot_table/blocks/precision_fabricator.json}），
     * 所以掉出来的就是精构仪本体物品。创造模式不掉（和原版一致）。</p>
     *
     * <p>如果那一格对着的位置已经不是精构仪（例如结构被别的途径拆过、剩下孤儿部件），
     * 就只拆它自己，不乱动别人的方块。</p>
     */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide) {
            Direction facing = state.getValue(FACING);
            BlockPos main = mainPos(state, pos);
            BlockState mainState = level.getBlockState(main);
            if (mainState.getBlock() instanceof PrecisionFabricatorBlock
                    && mainState.getValue(PrecisionFabricatorBlock.FACING) == facing) {
                removeAll(level, main, facing);
                level.destroyBlock(main, !player.isCreative(), player);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
}
