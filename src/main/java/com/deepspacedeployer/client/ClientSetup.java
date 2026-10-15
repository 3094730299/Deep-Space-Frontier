package com.deepspacedeployer.client;

import com.deepspacedeployer.Company;
import com.deepspacedeployer.net.ShopEditPayload;
import com.deepspacedeployer.registry.ModMenus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 客户端专用注册。
 *
 * <p>这里只能被 {@code Dist.CLIENT} 分支调用到，服务器永远不会加载这个类。</p>
 */
public final class ClientSetup {

    public static void init(IEventBus modEventBus) {
        modEventBus.addListener(ClientSetup::onRegisterTerminalRenderers);
        modEventBus.addListener(ClientSetup::onRegisterMachineRenderer);
        modEventBus.addListener(ClientSetup::onRegisterMenuScreens);
        NeoForge.EVENT_BUS.addListener(ClientSetup::onScreenInit);
    }



    /**
     * 通用终端的渲染器（GeckoLib，<b>可选</b>）。
     *
     * <p>只在装了 GeckoLib 时才注册；没装就整个跳过，模组其余功能照常。</p>
     */
    private static void onRegisterTerminalRenderers(
            net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers event) {
        if (!com.deepspacedeployer.client.gecko.UniversalTerminalGeoSetup.available()) {
            com.deepspacedeployer.DeepSpaceDeployer.LOGGER.info(
                    "[深空铸造体] 没有 GeckoLib，通用终端不显示（其它功能照常）");
            return;
        }
        com.deepspacedeployer.client.gecko.UniversalTerminalGeoSetup.registerRenderers(event);
    }

    /**
     * 机器类方块（收购投送机 / 载具打印机）的 GeckoLib 渲染器。
     *
     * <p>⚠️ <b>只注册这一个</b>：它们共用同一个方块实体类型 {@code MACHINE}，
     * 而原版一个方块实体类型只能有一个渲染器 ——
     * 分别注册两个会互相覆盖（现象：两个方块都显示成同一个模型）。</p>
     */
    private static void onRegisterMachineRenderer(
            net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers event) {
        if (!com.deepspacedeployer.client.gecko.UniversalTerminalGeoSetup.available()) {
            return;
        }
        event.registerBlockEntityRenderer(
                com.deepspacedeployer.registry.ModBlockEntities.MACHINE.get(),
                com.deepspacedeployer.client.gecko.MachineGeoRenderer::new);
        com.deepspacedeployer.DeepSpaceDeployer.LOGGER.info(
                "[深空铸造体] 已启用 GeckoLib 渲染：收购投送机 + 载具打印机");
    }

    private static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.TERMINAL.get(), MachineScreen::new);
        event.register(ModMenus.CORE.get(), MachineScreen::new);
        event.register(ModMenus.BUYER.get(), MachineScreen::new);
        event.register(ModMenus.TRANSFER.get(), TransferScreen::new);
        event.register(ModMenus.TERMINAL_UI.get(), TerminalScreen::new);
    }


    /** ESC（暂停）菜单左侧加一个"价目表编辑器"按钮，只有 OP 能看见 */
    private static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof PauseScreen)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !minecraft.player.hasPermissions(2)) {
            return;
        }
        event.addListener(Button.builder(Component.translatable("gui.deepspacedeployer.editor_button"), b -> {
            PacketDistributor.sendToServer(new ShopEditPayload(Company.VALUES[0].id(), 0, 0, 0, 0));
            minecraft.setScreen(new ShopEditorScreen());
        }).bounds(4, 40, 120, 20).build());
    }

    private ClientSetup() {
    }
}
