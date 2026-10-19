package com.deepspacedeployer.registry;

import com.deepspacedeployer.DeepSpaceDeployer;
import com.deepspacedeployer.block.MachineBlock;
import com.deepspacedeployer.block.UniversalTerminalBlock;
import com.deepspacedeployer.blockentity.MachineKind;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 方块注册表 */
public final class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(Registries.BLOCK, DeepSpaceDeployer.MOD_ID);

    public static final DeferredHolder<Block, MachineBlock> FACTORY_TERMINAL = register(MachineKind.TERMINAL);
    public static final DeferredHolder<Block, MachineBlock> FACTORY_CORE = register(MachineKind.CORE);
    public static final DeferredHolder<Block, MachineBlock> BUYER = register(MachineKind.BUYER);
    public static final DeferredHolder<Block, MachineBlock> MARKET = register(MachineKind.MARKET);

    /**
     * 「载具打印机」多方块结构的部件格。
     *
     * <p>载具打印机占 3×2×2 = 12 格，只有 1 格是控制器（{@code FACTORY_CORE}），
     * 其余 11 格都是这个方块。它**没有方块实体**（状态里记了列/层/朝向就够）。</p>
     */
    public static final DeferredHolder<Block, com.deepspacedeployer.block.PrinterPartBlock> PRINTER_PART =
            BLOCKS.register("printer_part", () -> new com.deepspacedeployer.block.PrinterPartBlock(
                    Block.Properties.of()
                            .mapColor(net.minecraft.world.level.material.MapColor.METAL)
                            .strength(3.5F, 8.0F)
                            .sound(net.minecraft.world.level.block.SoundType.METAL)
                            .requiresCorrectToolForDrops()
                            .noOcclusion()));

    /** 通用终端：纯装饰方块（原版方块模型，157 个 element，无 GeckoLib 依赖） */
    public static final DeferredHolder<Block, UniversalTerminalBlock> UNIVERSAL_TERMINAL =
            BLOCKS.register("universal_terminal", () -> new UniversalTerminalBlock(Block.Properties.of()
                    .strength(3.0F)
                    .sound(SoundType.METAL)
                    .noOcclusion()
                    .lightLevel(state -> 7)));

    /**
     * 精构仪 —— <b>1×1×5 装饰性多方块的主体</b>
     * （老板 2026-10-19 的新建模：164 个方块 / 7 段骨骼 / 一条 5 秒制作动画）。
     *
     * <p>整个模型由 GeckoLib 画（{@code PrecisionFabricatorRenderer}），
     * 所以主体方块是隐形的；没装 GeckoLib 时会显示成普通方块。
     * 主体左右各 2 格是 {@link #PRECISION_FABRICATOR_PART}（见 {@code PrecisionFabricatorStructure}）。</p>
     *
     * <p><b>贴图</b>：建模计划里那张 512×512 是 <b>UV 布局图</b>（13 种平涂色），
     * 直接当材质会像调色板；现在用 {@code tools/gen_precision_fabricator_texture.py}
     * 按 .bbmodel 骨骼分组上材质（白色机身 / 金色滑轨 / 深灰机械臂 / 深色蓝图屏）。
     * 源模型和 UV 布局同步在 {@code art/建模计划/}。</p>
     */
    public static final DeferredHolder<Block, com.deepspacedeployer.block.PrecisionFabricatorBlock>
            PRECISION_FABRICATOR = BLOCKS.register("precision_fabricator",
                    () -> new com.deepspacedeployer.block.PrecisionFabricatorBlock(Block.Properties.of()
                            .strength(3.0F)
                            .sound(SoundType.METAL)
                            .noOcclusion()
                            .lightLevel(state -> 5)));

    /**
     * 精构仪多方块结构的部件格（主体左右各 2 格，共 4 格）。
     *
     * <p>和载具打印机的 {@code printer_part} 一样：<b>没有方块实体</b>
     * （状态里记了 {@code slot} + {@code facing} 就够），不画自己，
     * 但碰撞占满本格。破坏任一个部件格都会由 {@code PrecisionFabricatorPartBlock}
     * 连主体一起拆掉。</p>
     *
     * <p>⚠️ 部件格<b>不发光的</b>：主体那格的 {@code lightLevel(5)} 只加一份光，
     * 别再给 4 个部件格各加一份。</p>
     */
    public static final DeferredHolder<Block, com.deepspacedeployer.block.PrecisionFabricatorPartBlock>
            PRECISION_FABRICATOR_PART = BLOCKS.register("precision_fabricator_part",
                    () -> new com.deepspacedeployer.block.PrecisionFabricatorPartBlock(Block.Properties.of()
                            .mapColor(net.minecraft.world.level.material.MapColor.METAL)
                            .strength(3.0F)
                            .sound(SoundType.METAL)
                            .noOcclusion()));

    private static DeferredHolder<Block, MachineBlock> register(MachineKind kind) {
        return BLOCKS.register(kind.id(), () -> new MachineBlock(kind));
    }

    /** 按种类取方块（GUI、掉落物、世界生成都要用） */
    public static MachineBlock get(MachineKind kind) {
        return switch (kind) {
            case TERMINAL -> FACTORY_TERMINAL.get();
            case CORE -> FACTORY_CORE.get();
            case BUYER -> BUYER.get();
            case MARKET -> MARKET.get();
        };
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
    }

    private ModBlocks() {
    }
}
