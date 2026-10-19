package com.deepspacedeployer;

import com.deepspacedeployer.client.ClientSetup;
import com.deepspacedeployer.factory.BlueprintLibrary;
import com.deepspacedeployer.factory.FactoryCommands;
import com.deepspacedeployer.integration.IntegrationProbe;
import com.deepspacedeployer.net.ModPayloads;
import com.deepspacedeployer.registry.ModBlockEntities;
import com.deepspacedeployer.registry.ModBlocks;
import com.deepspacedeployer.registry.ModCapabilities;
import com.deepspacedeployer.registry.ModCreativeTabs;
import com.deepspacedeployer.registry.ModItems;
import com.deepspacedeployer.registry.ModMenus;
import com.deepspacedeployer.shop.ShopReloadListener;
import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import org.slf4j.Logger;

/**
 * 深空铸造体 / Deep Space Deployer
 *
 * <p>航空学（Create: Aeronautics）附属模组：六家星际公司在世界上建厂，
 * 玩家在工厂里交易模块、选择蓝图、一键组装并焊接物理结构。</p>
 */
@Mod(DeepSpaceDeployer.MOD_ID)
public class DeepSpaceDeployer {

    /** 模组 id，所有注册名、资源路径、数据包目录都用它 */
    public static final String MOD_ID = "deepspacedeployer";

    public static final Logger LOGGER = LogUtils.getLogger();

    public DeepSpaceDeployer(IEventBus modEventBus, ModContainer modContainer) {
        // ---- 注册（顺序无所谓，但都要挂在 mod 事件总线上）----
        ModBlocks.register(modEventBus);
        ModItems.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModMenus.register(modEventBus);
        ModCreativeTabs.register(modEventBus);

        // ---- 网络包（银行卡绑定、价目表同步）----
        modEventBus.addListener(ModPayloads::register);

        // ---- 能力（收购站的机械手/管道接口）----
        modEventBus.addListener(ModCapabilities::registerCapabilities);

        // ---- 数据包：价目表（/reload 会重新读）----
        NeoForge.EVENT_BUS.addListener(DeepSpaceDeployer::onAddReloadListeners);

        // ---- 服务器启动时读蓝图文件夹 ----
        NeoForge.EVENT_BUS.addListener(DeepSpaceDeployer::onServerStarted);

        // ---- 管理员指令 /dsd reloadblueprints ----
        NeoForge.EVENT_BUS.addListener(DeepSpaceDeployer::onRegisterCommands);

        // ---- 空卡自动挂失（老板 2026-10-19：空卡 30 游戏日自动按"第三张卡"处理）----
        NeoForge.EVENT_BUS.addListener(DeepSpaceDeployer::onServerTick);

        // ---- 客户端专属：界面注册（服务器不会执行到这里）----
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientSetup.init(modEventBus);
        }

        // ---- 配置文件 ----
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        LOGGER.info("[深空铸造体] 六家公司已就位，等待建造。");
    }

    /** 数据包重载时把价目表读进来（改完 JSON 用 /reload 生效） */
    private static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new ShopReloadListener());
    }

    /**
     * 服务器 tick：每 200 tick（≈10 秒）扫一次空卡，空满 30 游戏日就自动挂失。
     *
     * <p>老板 2026-10-19：空卡 30 游戏日自动按"第三张卡"处理（= 挂失 → 变废卡、清数据）。
     * 30 游戏日 = 720,000 tick 的量级，没必要每 tick 扫，10 秒一次足够准。</p>
     */
    private static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        net.minecraft.server.MinecraftServer server = event.getServer();
        if (server.getTickCount() % 200 != 0) {
            return;
        }
        try {
            long day = com.deepspacedeployer.economy.CardRegistry.gameDay(server);
            int voided = com.deepspacedeployer.economy.CardRegistry.get(server).sweepExpired(server, day);
            if (voided > 0) {
                LOGGER.info("[深空铸造体] 空卡自动挂失：{} 张（游戏日 {}）", voided, day);
            }
        } catch (Throwable t) {
            LOGGER.warn("[深空铸造体] 空卡扫描失败：{}", t.toString());
        }
    }

    /** 服务器起来了就读一遍物理蓝图（两个文件夹），并打印联动探测报告 */
    private static void onServerStarted(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        int count = com.deepspacedeployer.factory.PhysicsBlueprintLibrary.refresh(event.getServer());
        LOGGER.info("[深空铸造体] 物理蓝图已加载：{} 份（Sable-Schematics + enxv_aeronautics_structures）", count);
        for (String line : IntegrationProbe.describe()) {
            LOGGER.info("[深空铸造体][联动] {}", line);
        }
    }

    private static void onRegisterCommands(net.neoforged.neoforge.event.RegisterCommandsEvent event) {
        FactoryCommands.register(event.getDispatcher());    }
}
