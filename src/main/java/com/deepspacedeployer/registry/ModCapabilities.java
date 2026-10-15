package com.deepspacedeployer.registry;

import com.deepspacedeployer.DeepSpaceDeployer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * 把方块实体接到 NeoForge 的能力系统上，机械手 / 漏斗 / 管道才能往收购站里塞物品。
 */
public final class ModCapabilities {

    @SubscribeEvent
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.MACHINE.get(),
                (blockEntity, side) -> blockEntity.itemHandler());
        DeepSpaceDeployer.LOGGER.debug("[深空铸造体] 物品能力已注册");
    }

    private ModCapabilities() {
    }
}
