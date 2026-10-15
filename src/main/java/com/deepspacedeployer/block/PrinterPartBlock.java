package com.deepspacedeployer.block;

import com.deepspacedeployer.blockentity.MachineBlockEntity;
import com.deepspacedeployer.multiblock.PrinterStructure;
import com.deepspacedeployer.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 「载具打印机」多方块结构里的<b>部件格</b>。
 *
 * <p>载具打印机在世界里占 3 × 2 × 2 = 12 格，其中只有 1 格是控制器
 * （{@link MachineBlock}，kind = CORE，负责数据与界面），
 * 其余 11 格都是本方块 —— <b>它自己不带方块实体</b>，只记两个东西：</p>
 * <ul>
 *   <li>{@link #PART_X}/{@link #PART_Y}：它在结构里的列号与层号（用来算它到控制器的偏移）</li>
 *   <li>{@link #FACING}：整台机器的朝向（和控制器一致，用来把偏移旋转回去）</li>
 * </ul>
 *
 * <p>这样"点哪里都能开界面、挖哪里整台都掉"都不用额外存数据，
 * 而且不需要给部件注册方块实体类型（少一个崩溃点）。</p>
 */
public class PrinterPartBlock extends Block {

    /** 列号 0..5 */
    public static final EnumProperty<PartColumn> COLUMN =
            EnumProperty.create("column", PartColumn.class);
    /** 层号 0..1 */
    public static final EnumProperty<PartLayer> LAYER =
            EnumProperty.create("layer", PartLayer.class);
    /** 整台机器的朝向（和控制器一致） */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    /** 控制器是否在结构内（防呆，不用也行） */
    public static final BooleanProperty FORMED = BooleanProperty.create("formed");

    public PrinterPartBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(COLUMN, PartColumn.C0)
                .setValue(LAYER, PartLayer.L0)
                .setValue(FACING, Direction.NORTH)
                .setValue(FORMED, Boolean.TRUE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(COLUMN, LAYER, FACING, FORMED);
    }

    /** 部件格不画自己（整台机器的画面由控制器那一格统一渲染） */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    /**
     * 部件格的碰撞：<b>自己那一格给一个整方块</b>。
     *
     * <p>12 个部件格各自占满本格 → 整台机器（3×2×2）全实心，走到哪都能撞到。
     * 之前这里是空的，结果只有控制器那 1 格有碰撞 —— 已修正。</p>
     */
    @Override
    protected net.minecraft.world.phys.shapes.VoxelShape getCollisionShape(
            BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos,
            net.minecraft.world.phys.shapes.CollisionContext context) {
        return net.minecraft.world.phys.shapes.Shapes.block();
    }

    @Override
    protected net.minecraft.world.phys.shapes.VoxelShape getShape(
            BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos,
            net.minecraft.world.phys.shapes.CollisionContext context) {
        return net.minecraft.world.phys.shapes.Shapes.block();
    }

    // ---------- 放置：由控制器负责摆好整台机器，部件格自己不允许单独放置 ----------

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // 单独放部件格是不允许的（物品是控制器，部件格只是结构填充）
        return null;
    }

    // ---------- 交互：转发给控制器 ----------

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hitResult) {
        return forward(state, level, pos, player);
    }

    @Override
    protected net.minecraft.world.ItemInteractionResult useItemOn(
            ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
            net.minecraft.world.InteractionHand hand, BlockHitResult hitResult) {
        InteractionResult r = forward(state, level, pos, player);
        return r == InteractionResult.SUCCESS
                ? net.minecraft.world.ItemInteractionResult.sidedSuccess(level.isClientSide)
                : net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    /** 找到控制器，把这次右键转交给它 —— 多方块结构"点哪一格都开同一个界面" */
    private InteractionResult forward(BlockState state, Level level, BlockPos pos, Player player) {
        BlockPos controller = controllerPos(state, pos);
        if (controller == null) {
            return InteractionResult.PASS;
        }
        if (!(level.getBlockEntity(controller) instanceof MachineBlockEntity be)) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            be.openMenuFor(serverPlayer);
        }
        return InteractionResult.CONSUME;
    }

    /**
     * 由部件格的位置 + 它在结构里的偏移，反推控制器的位置。
     *
     * <p>控制器就是偏移 (0,0,0) 那一格。</p>
     */
    public static BlockPos controllerPos(BlockState state, BlockPos pos) {
        Direction facing = state.getValue(FACING);
        int col = state.getValue(COLUMN).ordinal();
        int layer = state.getValue(LAYER).ordinal();
        BlockPos off = PrinterStructure.offset(col + layer * PrinterStructure.COLUMNS, facing);
        return pos.subtract(off);
    }

    /** 部件格从控制器拿到偏移后，自己该摆什么状态 */
    public static BlockState stateFor(int part, Direction facing, BlockState base) {
        return base
                .setValue(COLUMN, PartColumn.values()[part % PrinterStructure.COLUMNS])
                .setValue(LAYER, PartLayer.values()[part / PrinterStructure.COLUMNS])
                .setValue(FACING, facing)
                .setValue(FORMED, Boolean.TRUE);
    }

    // ---------- 放下整台机器 ----------

    /**
     * 从 {@code clicked} 出发，找一个能放下整台机器的位置（尽量以点击处为中心）。
     *
     * @return 控制器应该放的位置；放不下返回 null
     */
    public static BlockPos findOrigin(LevelReader level, BlockPos clicked, Direction facing) {
        // 控制器落点从"点击处往负方向退"开始试
        for (int back = 0; back <= 2; back++) {
            for (int side = 0; side <= 2; side++) {
                for (int down = 0; down <= 1; down++) {
                    Direction right = facing.getCounterClockWise();
                    BlockPos origin = clicked
                            .relative(right, -side)
                            .relative(facing, -back)
                            .below(down);
                    if (canPlaceAll(level, origin, facing)) {
                        return origin;
                    }
                }
            }
        }
        return null;
    }

    /** 12 个格子是不是都能放 */
    public static boolean canPlaceAll(LevelReader level, BlockPos origin, Direction facing) {
        for (int part = 0; part < PrinterStructure.PARTS; part++) {
            BlockPos p = origin.offset(PrinterStructure.offset(part, facing));
            BlockState st = level.getBlockState(p);
            if (!st.canBeReplaced()) {
                return false;
            }
        }
        return true;
    }

    /** 真正把 12 格摆下去（控制器那一格用 controllerState） */
    public static void placeAll(Level level, BlockPos origin, Direction facing, BlockState controllerState) {
        BlockState partBase = ModBlocks.PRINTER_PART.get().defaultBlockState();
        for (int part = 0; part < PrinterStructure.PARTS; part++) {
            BlockPos p = origin.offset(PrinterStructure.offset(part, facing));
            if (part == 0) {
                level.setBlock(p, controllerState, Block.UPDATE_ALL);
            } else {
                level.setBlock(p, stateFor(part, facing, partBase), Block.UPDATE_ALL);
            }
        }
    }

    /** 拆掉整台机器（从任意一格触发） */
    public static void removeAll(Level level, BlockPos controller) {
        BlockState cs = level.getBlockState(controller);
        if (!(cs.getBlock() instanceof MachineBlock)) {
            return;
        }
        Direction facing = cs.hasProperty(FACING) ? cs.getValue(FACING) : Direction.NORTH;
        for (int part = PrinterStructure.PARTS - 1; part >= 0; part--) {
            BlockPos p = controller.offset(PrinterStructure.offset(part, facing));
            if (p.equals(controller)) {
                continue;
            }
            if (level.getBlockState(p).getBlock() instanceof PrinterPartBlock) {
                level.removeBlock(p, false);
            }
        }
    }

    // ---------- 列 / 层 的枚举 ----------

    public enum PartColumn implements net.minecraft.util.StringRepresentable {
        C0, C1, C2, C3, C4, C5;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public enum PartLayer implements net.minecraft.util.StringRepresentable {
        L0, L1;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        // 部件格不允许被"放"出来（物品是控制器），这里只是防呆
        super.setPlacedBy(level, pos, state, placer, stack);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        // 挖掉任意一格 → 整台机器一起消失
        BlockPos controller = controllerPos(state, pos);
        if (controller != null && level.getBlockState(controller).getBlock() instanceof MachineBlock mb
                && mb.kind() == com.deepspacedeployer.blockentity.MachineKind.CORE) {
            if (!level.isClientSide && level.getBlockEntity(controller)
                    instanceof MachineBlockEntity machine) {
                machine.dropEverything();       // 先把它肚子里的东西吐出来
            }
            removeAll(level, controller);
            // 控制器自己由原版流程移除（这里不 removeBlock，避免递归）
            if (!level.isClientSide) {
                level.destroyBlock(controller, true, player);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
}
