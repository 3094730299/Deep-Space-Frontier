package com.deepspacedeployer.registry;

import com.deepspacedeployer.DeepSpaceDeployer;
import com.deepspacedeployer.blockentity.MachineBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, DeepSpaceDeployer.MOD_ID);

    /**
     * 四个功能方块共用同一个方块实体类型。
     *
     * <p>⚠️ <b>每加一个 MachineKind，这里的方块清单也一定要加上</b> ——
     * 漏了的后果是：放置那个方块时原版 {@code BlockEntity.validateBlockState} 发现
     * "这个方块不在本类型的合法方块里"，直接抛
     * {@code Invalid block entity ... got Block{deepspacedeployer:market}} → 客户端崩。
     * （寄售市场就漏过一次，见 docs/设计决策.md §2.38/§2.39。）</p>
     */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MachineBlockEntity>> MACHINE =
            BLOCK_ENTITIES.register("machine", () -> BlockEntityType.Builder.of(
                    MachineBlockEntity::new,
                    ModBlocks.FACTORY_TERMINAL.get(),
                    ModBlocks.FACTORY_CORE.get(),
                    ModBlocks.BUYER.get(),
                    ModBlocks.MARKET.get()
            ).build(null));

    /** 通用终端的方块实体（只为动画渲染服务，不存数据） */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.deepspacedeployer.blockentity.UniversalTerminalBlockEntity>>
            UNIVERSAL_TERMINAL = BLOCK_ENTITIES.register("universal_terminal", () -> BlockEntityType.Builder.of(
                    com.deepspacedeployer.blockentity.UniversalTerminalBlockEntity::new,
                    ModBlocks.UNIVERSAL_TERMINAL.get()).build(null));

    /** 精构仪（装饰方块）的方块实体 —— 只为了让 GeckoLib 有东西可画 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.deepspacedeployer.blockentity.PrecisionFabricatorBlockEntity>>
            PRECISION_FABRICATOR = BLOCK_ENTITIES.register("precision_fabricator", () -> BlockEntityType.Builder.of(
                    com.deepspacedeployer.blockentity.PrecisionFabricatorBlockEntity::new,
                    ModBlocks.PRECISION_FABRICATOR.get()).build(null));

    public static void register(IEventBus modEventBus) {
        BLOCK_ENTITIES.register(modEventBus);
    }

    private ModBlockEntities() {
    }
}
