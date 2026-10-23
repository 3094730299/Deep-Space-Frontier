package com.deepspacedeployer.block;

import com.deepspacedeployer.Company;
import com.deepspacedeployer.Tier;
import com.deepspacedeployer.blockentity.MachineBlockEntity;
import com.deepspacedeployer.blockentity.MachineKind;
import com.deepspacedeployer.item.BuyerBlockItem;
import com.deepspacedeployer.menu.MachineMenu;
import com.deepspacedeployer.net.ShopDataPayload;
import com.deepspacedeployer.registry.ModItems;
import com.deepspacedeployer.registry.ModMenus;
import com.deepspacedeployer.shop.ShopData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 四种功能方块的公共实现（商店 / 工厂 / 焊接 / 收购）。
 */
public class MachineBlock extends Block implements EntityBlock {

    /** 插卡后点亮 */
    public static final BooleanProperty LIT = BlockStateProperties.LIT;
    /** 属于哪家公司（决定外观与货币） */
    public static final EnumProperty<Company> COMPANY = EnumProperty.create("company", Company.class);
    /** 工厂等级 */
    public static final EnumProperty<Tier> TIER = EnumProperty.create("tier", Tier.class);
    /**
     * 放置朝向。
     *
     * <p>用 {@code HORIZONTAL_FACING}（而不是自造的属性）是有意的 ——
     * GeckoLib 的 {@code GeoBlockRenderer} 会自动读这几个属性来旋转模型：
     * {@code FACING} / {@code HORIZONTAL_FACING} / {@code VERTICAL_DIRECTION} /
     * {@code FACING_HOPPER}。所以渲染侧一行旋转代码都不用写。</p>
     */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    private final MachineKind kind;

    public MachineBlock(MachineKind kind) {
        super(Properties.of()
                .mapColor(MapColor.METAL)
                .strength(3.5F, 8.0F)
                .sound(SoundType.METAL)
                .requiresCorrectToolForDrops()
                .lightLevel(state -> state.getValue(LIT) ? 7 : 0));
        this.kind = kind;
        registerDefaultState(this.stateDefinition.any()
                .setValue(LIT, Boolean.FALSE)
                .setValue(COMPANY, Company.VALUES[0])
                .setValue(TIER, Tier.LOW)
                .setValue(FACING, Direction.NORTH));
    }

    public MachineKind kind() {
        return kind;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LIT, COMPANY, TIER, FACING);
    }

    /** 放置时从物品上的数据读公司/等级（世界生成的建筑会直接写进方块状态） */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState();
        ItemStack stack = context.getItemInHand();

        // 1) 原版的方块状态组件 —— 掉落表用 copy_state 写进来的就是它，
        //    所以"挖起来再放下"能保住原来那台的公司与等级
        BlockItemStateProperties properties = stack.get(DataComponents.BLOCK_STATE);
        if (properties != null) {
            state = properties.apply(state);
        }

        // 2) 我们自己的 CUSTOM_DATA（保留兼容：指令/调试给物品打标记时用）
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null) {
            CompoundTag tag = data.copyTag();
            if (tag.contains("Company")) {
                state = state.setValue(COMPANY, Company.byId(tag.getString("Company")));
            }
            if (tag.contains("Tier")) {
                state = state.setValue(TIER, Tier.byId(tag.getString("Tier")));
            }
        }
        // 3) 无归属的方块（工厂装配核心）：公司属性一律归零，
        //    免得放下之后带着某家公司的外观/名称（挖起来再放下也不会"认领"公司）
        state = state.setValue(COMPANY, kind.isCompanyBound() ? state.getValue(COMPANY) : Company.VALUES[0]);

        // 3.5) 收购投送机：3 个等级物品自己带等级（/give 出来的也要放对）
        if (kind == MachineKind.BUYER
                && stack.getItem() instanceof BuyerBlockItem buyerItem) {
            state = state.setValue(TIER, buyerItem.tier());
        }

        // 4) 朝向：正面朝着玩家（和通用终端一致）
        Direction facing = context.getHorizontalDirection().getOpposite();
        state = state.setValue(FACING, facing);

        // 5) 载具打印机是多方块（3×2×2 = 12 格）：
        //    控制器落在摆放的那一格，其余 11 格在 setPlacedBy 里补上。
        //
        //    ★ 全有或全无（老板要求，2026-10-15）★
        //      12 格里**只要有一格放不下**（被别的方块、别的多方块结构占着），
        //      就【整台不放下】—— 连控制器也不放，而不是"把被挡的格子跳过"。
        //
        //      为什么要这样：以前是"被挡的格子跳过"，机器就会缺格，
        //      于是**打印机的碰撞箱缺一块**（老板反馈的现象：
        //      "碰撞箱被其他多方块结构挤掉"）。
        //
        //      返回 null ⇒ 原版 {@code BlockItem.place} 直接 FAIL：
        //      什么都不放、也不消耗物品。
        //      ⚠️ 老板明确要求**不要给提示**，所以这里不往聊天框发任何消息。
        if (kind == MachineKind.CORE
                && !PrinterPartBlock.canPlaceAll(context.getLevel(), context.getClickedPos(), facing)) {
            return null;
        }

        return state;
    }

    /**
     * 放下「载具打印机」时，把整台机器（3×2×2 = 12 格）一次摆好。
     *
     * <p>控制器就是玩家放的那一格，其余 11 格是 {@link PrinterPartBlock}。</p>
     *
     * <p>⚠️ 能走到这里，说明 {@link #getStateForPlacement} 已经确认过
     * <b>12 格全是空的</b>（老板要求"全有或全无"），所以下面的
     * {@code canBeReplaced} 判断正常情况下永远为真 ——
     * 它只是一层保险：万一有别的途径绕过放置检查，
     * 也绝不吞掉别人的方块（宁可少一格，也不拆别人东西）。</p>
     */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer,
                            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (kind != MachineKind.CORE || level.isClientSide) {
            return;
        }
        Direction facing = state.hasProperty(FACING) ? state.getValue(FACING) : Direction.NORTH;
        for (int part = 0; part < com.deepspacedeployer.multiblock.PrinterStructure.PARTS; part++) {
            BlockPos p = pos.offset(com.deepspacedeployer.multiblock.PrinterStructure.offset(part, facing));
            if (p.equals(pos)) {
                continue;                     // 这一格是控制器自己（part 3，偏移 0,0,0）
            }
            if (!level.getBlockState(p).canBeReplaced()) {
                continue;                     // 保险：不吞别人的方块
            }
            level.setBlock(p, PrinterPartBlock.stateFor(part, facing,
                    com.deepspacedeployer.registry.ModBlocks.PRINTER_PART.get().defaultBlockState()),
                    Block.UPDATE_ALL);
        }
    }

    /**
     * 拆掉机器之前，把里面的东西全部吐出来。
     *
     * <p>方块里的东西包括：9 个投料格、银行卡、材料缓冲、组装中预留的材料，
     * 以及<b>存在方块里的各玩家余额</b>（换成该公司票据掉出来）。
     * 不做这件事的话，这些东西会随着方块一起消失。</p>
     */
    /** 拆多方块时的重入保护（removeAll 里会再触发一次破坏流程） */
    private static final java.util.Set<BlockPos> TEARDOWN = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof MachineBlockEntity machine) {
            if (kind == MachineKind.CORE && TEARDOWN.add(pos)) {
                try {
                    machine.dropEverything();          // 先把肚子里的东西吐出来
                    com.deepspacedeployer.block.PrinterPartBlock.removeAll(level, pos);
                } finally {
                    TEARDOWN.remove(pos);
                }
            } else {
                machine.dropEverything();
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MachineBlockEntity(pos, state);
    }

    /** 收购站 / 工厂核心 / 商店（星联那套要检测玩家靠近）需要每 tick 处理 */
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        // 收购投送机：客户端也要 tick（动画靠它推进）
        // 商店的展开/闭合不用客户端 ticker —— 它挂在 GeckoLib 控制器的回调上（每帧必到）
        if (level.isClientSide()) {
            if (this.kind != MachineKind.BUYER) {
                return null;
            }
            return (lvl, pos, st, blockEntity) -> {
                if (blockEntity instanceof MachineBlockEntity machine) {
                    machine.clientAnimTick();
                }
            };
        }
        if (this.kind != MachineKind.BUYER && this.kind != MachineKind.CORE
                && this.kind != MachineKind.TERMINAL) {
            return null;
        }
        return (lvl, pos, st, blockEntity) -> {
            if (blockEntity instanceof MachineBlockEntity machine) {
                MachineBlockEntity.serverTick(lvl, pos, st, machine);
            }
        };
    }


    /**
     * 收购投送机的碰撞箱：<b>一个完整方块</b>。
     *
     * <p>老板定稿 —— 不要按模型超出方格，就是一个普通整方块（占满本格 y 0..1）。</p>
     */
    private static final VoxelShape BUYER_SHAPE = Shapes.block();

    /**
     * 载具打印机（工厂核心）控制器那一格的碰撞：<b>一个整方块</b>。
     *
     * <p>整台机器占 3×2×2 = 12 格，其中 11 格是 {@link PrinterPartBlock}
     * （各自给自己的整方块碰撞），控制器这格同样只占本格。</p>
     *
     * <p>⚠️ 不要按模型做"超出方格"的碰撞 —— 实测会挡住旁边 2×2×3 一片区域
     * 导致放不下方块（老板反馈过）。</p>
     */
    private static final VoxelShape CORE_SHAPE = Shapes.block();

    /** 按机器种类取碰撞形状 */
    private VoxelShape shapeFor() {
        return switch (kind) {
            case BUYER -> BUYER_SHAPE;
            case CORE -> CORE_SHAPE;
            default -> Shapes.block();
        };
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapeFor();
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                           CollisionContext context) {
        return shapeFor();
    }

    /**
     * 收购投送机的画面全部交给 GeckoLib，所以方块本体不画。
     *
     * <p>其余机器仍然用普通方块模型。</p>
     */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        // 只有 GeckoLib 渲染器真的注册上了，才隐藏本体 ——
        // 否则没装 GeckoLib 时这些方块会变成隐形方块。
        if (!com.deepspacedeployer.client.gecko.UniversalTerminalGeoSetup.isRendererRegistered()) {
            return RenderShape.MODEL;
        }
        return hasCustomModel(state) ? RenderShape.INVISIBLE : RenderShape.MODEL;
    }

    /**
     * 这个方块状态下有没有 GeckoLib 自定义模型。
     *
     * <p>⚠️ 必须和 {@code MachineGeoModel.geoName(方块实体)} 的判断<b>完全一致</b>：
     * 那边按「种类 + 公司」挑模型，这边按方块状态挑渲染形状。
     * 两边不一致就会出现"本体和模型同时画"，或者"谁都不画"的灵异现象。</p>
     */
    private boolean hasCustomModel(BlockState state) {
        if (kind == MachineKind.BUYER || kind == MachineKind.CORE) {
            return true;
        }
        // 星联动力联盟的商店用老板新做的模型；其余公司的商店仍用原版方块模型
        return kind == MachineKind.TERMINAL && state.hasProperty(COMPANY)
                && state.getValue(COMPANY) == Company.ASTRALINK;
    }

    private Component menuTitle() {
        return Component.translatable("block.deepspacedeployer." + kind.id());
    }

    /** 手持银行卡右键：插卡 + 开机 + 打开界面 */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (!(level.getBlockEntity(pos) instanceof MachineBlockEntity be)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        // 授权铭牌 / 升级插件 / 蓝图凭证：把这次右键让给物品自己处理（否则会被下面的"开界面"吃掉）
        if (stack.getItem() instanceof com.deepspacedeployer.item.MachineTargetItem) {
            return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        }
        if (!ModItems.isBankCard(stack)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!level.isClientSide) {
            be.insertCard(stack, player);
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.card_inserted",
                    Component.translatable("block.deepspacedeployer." + kind.id())), true);
            openMenu(player, pos, be);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    /** 空手右键：直接开界面（不需要插卡）；插卡只是"让机器能花钱" */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hitResult) {
        if (!(level.getBlockEntity(pos) instanceof MachineBlockEntity be)) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        // 老板要求"无法切换"：公司/等级已经不可能在游戏里改了 ——
        // 公司由你放的是哪一件物品决定，等级只能靠「机械级别升级插件」升。
        openMenu(player, pos, be);
        return InteractionResult.CONSUME;
    }

    /**
     * 打开这台机器的界面。
     *
     * <p>逻辑放在方块实体里（{@link MachineBlockEntity#openMenuFor}），
     * 因为多方块结构的<b>部件格</b>也要能点开同一个界面 —— 两边共用一份实现，
     * 不会走着走着走偏。</p>
     */
    private void openMenu(Player player, BlockPos pos, MachineBlockEntity be) {
        if (player instanceof ServerPlayer serverPlayer) {
            be.openMenuFor(serverPlayer);
        }
    }
}
