package com.deepspacedeployer.block;

import com.deepspacedeployer.blockentity.UniversalTerminalBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 通用终端：老板从"星舰计划"搬进来的装饰方块，<b>没有任何功能</b>。
 *
 * <p>模型来自 Blockbench 导出的 OBJ，由 {@code UniversalTerminalRenderer}
 * <b>每帧自己绘制</b>（所以方块本体设成 {@link RenderShape#INVISIBLE}）——
 * 这是为了做"放置"动画：升起 + 四爪张开。方块模型会被烘焙进区块网格，做不到逐帧变化。</p>
 */
public class UniversalTerminalBlock extends Block implements EntityBlock {

    /** 朝向：放下时正面朝玩家（老板要求） */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    /**
     * 碰撞箱：**按模型逐层算出来的多个盒子**（参考 impact_drill 的 HullCoreBlock 做法）。
     *
     * <p>盒子由 {@code tools/gen_collision_shape.py} 从 geo.json 逐层求水平截面得到：</p>
     * <pre>
     *   y 0.0000..0.0625  X 0.2067..0.7308  Z 0.2811..0.7344   托盘（最宽）
     *   y 0.0625..0.1875  X 0.3231..0.6617  Z 0.2811..0.7344   底盘
     *   y 0.1875..1.0000  X 0.3231..0.6617  Z 0.3594..0.6406   立柱机身（最窄）
     * </pre>
     *
     * <p>比单个大盒子贴合得多：托盘比柱身宽、底盘比柱身深，走过去不会被空气挡住。
     * 4 个朝向的形状在类加载时**预烘焙**，运行时直接查表（HullCoreBlock 也是这么做的）。</p>
     */
    private static final VoxelShape SHAPE = Shapes.or(
            Shapes.box(0.2067D, 0.0000D, 0.2811D, 0.7308D, 0.0625D, 0.7344D),
            Shapes.box(0.3231D, 0.0625D, 0.2811D, 0.6617D, 0.1875D, 0.7344D),
            Shapes.box(0.3231D, 0.1875D, 0.3594D, 0.6617D, 1.0000D, 0.6406D));

    /** 按 FACING 预烘焙的 4 个形状：顺序 = 北/东/南/西 */
    private static final VoxelShape[] SHAPES = buildShapes();

    private static VoxelShape[] buildShapes() {
        VoxelShape[] out = new VoxelShape[4];
        for (int i = 0; i < 4; i++) {
            VoxelShape shape = SHAPE;
            for (int turn = 0; turn < i; turn++) {
                shape = rotateY(shape);
            }
            out[i] = shape;
        }
        return out;
    }

    /** 把一个形状绕方块中心转 90°（Y 轴） */
    private static VoxelShape rotateY(VoxelShape shape) {
        VoxelShape[] out = {Shapes.empty()};
        shape.forAllBoxes((x1, y1, z1, x2, y2, z2) ->
                out[0] = Shapes.or(out[0], Shapes.box(1.0D - z2, y1, x1, 1.0D - z1, y2, x2)));
        return out[0];
    }

    private static VoxelShape shapeFor(BlockState state) {
        Direction facing = state.getOptionalValue(FACING).orElse(Direction.NORTH);
        return SHAPES[Math.floorMod(facing.get2DDataValue(), 4)];
    }

    public UniversalTerminalBlock(Properties properties) {
        super(properties);
        registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /** 放下时正面朝玩家 */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    /** 碰撞也用同一套细形状（否则实体会被整块空气挡住） */
    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                           CollisionContext context) {
        return shapeFor(state);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapeFor(state);
    }

    /** 不是完整方块：不挡光（模型是镂空的） */
    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    /** 画面完全交给方块实体渲染器，方块本体什么都不画 */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    /** 右键打开终端界面（6 个卡槽 + 当前公司的商店） */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hitResult) {
        if (!(level.getBlockEntity(pos) instanceof com.deepspacedeployer.blockentity.UniversalTerminalBlockEntity be)) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            be.refreshCompany();
            serverPlayer.openMenu(new SimpleMenuProvider(
                    (windowId, inventory, p) -> new com.deepspacedeployer.menu.TerminalMenu(windowId, inventory, be),
                    be.title()),
                    buf -> buf.writeBlockPos(pos));
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new UniversalTerminalBlockEntity(pos, state);
    }

    /**
     * 客户端每 tick 调一次方块实体 —— <b>动画触发就靠它</b>。
     *
     * <p>没有这个 ticker，方块实体永远不会被 tick，{@code clientTick()} 里的
     * "刚放下就播一次动画"就永远不会执行（现象：完全没有动画）。</p>
     */
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            net.minecraft.world.level.Level level, BlockState state, BlockEntityType<T> type) {
        if (!level.isClientSide) {
            return null;
        }
        return (tickLevel, pos, tickState, blockEntity) -> {
            if (blockEntity instanceof UniversalTerminalBlockEntity terminal) {
                terminal.clientTick();
            }
        };
    }
}
