package com.deepspacedeployer.client.gecko;

import com.deepspacedeployer.DeepSpaceDeployer;
import net.neoforged.fml.ModList;

/**
 * GeckoLib 的可用性判断 + 注册入口。
 *
 * <p>⚠️ 这个类<b>只碰 ModList，不引用 GeckoLib 的任何类型</b> ——
 * 否则没装 GeckoLib 时光是加载这个类就会因为找不到类而崩。</p>
 */
public final class UniversalTerminalGeoSetup {

    private static Boolean loaded;

    public static boolean available() {
        if (loaded == null) {
            loaded = ModList.get().isLoaded("geckolib");
        }
        return loaded;
    }

    /** 渲染器是否已经注册成功（供方块类判断要不要自己画） */
    private static volatile boolean rendererRegistered;

    /** GeckoLib 渲染器是否已就位 */
    public static boolean isRendererRegistered() {
        return rendererRegistered;
    }

    /** 注册渲染器（只在装了 GeckoLib 时才会走到这里） */
    public static void registerRenderers(
            net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(
                com.deepspacedeployer.registry.ModBlockEntities.UNIVERSAL_TERMINAL.get(),
                UniversalTerminalGeoRenderer::new);
        rendererRegistered = true;
        DeepSpaceDeployer.LOGGER.info("[深空铸造体] 已启用 GeckoLib 渲染：通用终端的放置动画");
    }

    private UniversalTerminalGeoSetup() {
    }
}
