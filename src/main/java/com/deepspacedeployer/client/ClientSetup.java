package com.deepspacedeployer.client;

import com.deepspacedeployer.Company;
import com.deepspacedeployer.net.ShopEditPayload;
import com.deepspacedeployer.registry.ModMenus;
import com.deepspacedeployer.ui.webview.ThemeButton;
import com.deepspacedeployer.ui.webview.ThemeKeys;
import com.deepspacedeployer.ui.webview.WebViewManager;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
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

    /** 背包界面上那颗「主题」按钮（每次界面初始化时更新；用来响应"按钮移动"键位） */
    private static ThemeButton themeButton;
    /** 上一帧的鼠标位置（GUI 坐标）—— 键盘触发"按钮移动"时要用 */
    private static int lastMouseX;
    private static int lastMouseY;

    public static void init(IEventBus modEventBus) {
        modEventBus.addListener(ClientSetup::onRegisterTerminalRenderers);
        modEventBus.addListener(ClientSetup::onRegisterMachineRenderer);
        modEventBus.addListener(ClientSetup::onRegisterMenuScreens);
        // ★ 按键绑定：「按钮移动」（默认鼠标中键）
        ThemeKeys.register(modEventBus);
        NeoForge.EVENT_BUS.addListener(ClientSetup::onScreenInit);
        NeoForge.EVENT_BUS.addListener(ClientSetup::onScreenRender);
        NeoForge.EVENT_BUS.addListener(ClientSetup::onMousePressed);
        NeoForge.EVENT_BUS.addListener(ClientSetup::onKeyPressed);
        NeoForge.EVENT_BUS.addListener(ClientSetup::onClientTick);

        // ★ 界面主题（内置 WebView）：把 jar 里内置的默认主题释放到磁盘 + 记住当前主题名。
        //   ⚠️ 以前【没有任何地方】调 ThemeManager.ensureDefaultTheme()，
        //      所以 theme/default/ 一直没落到磁盘上、玩家根本看不到这个目录（旧的待办第 5 条）。
        WebViewManager.bootstrap();
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


    /**
     * 往界面上加两个入口：
     *
     * <ul>
     *   <li><b>玩家背包</b>（按 E 打开）—— 加一颗圆形「界面主题」按钮
     *       （左键点开、右键长按拖拽、按「按钮移动」键一把搬过来、位置记进配置）；</li>
     *   <li><b>ESC 暂停菜单</b> —— 只给 OP 加「价目表编辑器」。</li>
     * </ul>
     *
     * <p>⚠️ 「界面主题」按钮<b>故意不放 ESC 菜单</b>（老板 2026-10-17 的要求）：
     * 暂停菜单有自己的生命周期，在它上面开新界面会出现"不响应 / 延迟响应 /
     * 要先点『继续游戏』才弹出来"。背包界面随时可开、不暂停游戏，稳定得多。</p>
     */
    private static void onScreenInit(ScreenEvent.Init.Post event) {
        var screen = event.getScreen();

        // ① 背包界面：生存（InventoryScreen）和创造（CreativeModeInventoryScreen）都要加。
        //    ⚠️ 这是**两个不同的类**（创造模式那个直接继承 AbstractContainerScreen），
        //    只判断 InventoryScreen 的话创造模式里就看不到按钮（老板 2026-10-18 实测反馈）。
        if (screen instanceof InventoryScreen || screen instanceof CreativeModeInventoryScreen) {
            addThemeButton(event, (AbstractContainerScreen<?>) screen);
            return;
        }

        // ② ESC 暂停菜单：只给 OP 加价目表编辑器
        if (!(screen instanceof PauseScreen)) {
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

    /** 把圆形主题按钮摆到背包界面上（位置从配置里读；没摆过就底部居中） */
    private static void addThemeButton(ScreenEvent.Init.Post event, AbstractContainerScreen<?> screen) {
        int[] pos = ThemeButton.loadPosition(screen.width, screen.height);
        ThemeButton button = new ThemeButton(pos[0], pos[1]);
        // 换分辨率后按钮可能跑到可视范围外 → 每次初始化先拉回来
        button.clampTo(screen.width, screen.height);
        event.addListener(button);
        // 记住它，供「按钮移动」键位使用（界面关掉后这个引用也没人再读）
        themeButton = button;
    }

    // ---------------------------------------------------------------- 「按钮移动」键位

    /**
     * 每帧记一下鼠标在 GUI 坐标里的位置。
     *
     * <p>键盘触发「按钮移动」时没有鼠标坐标可拿（{@code ScreenEvent.KeyPressed} 只给键码），
     * 所以从渲染事件里顺手存一份。</p>
     */
    private static void onScreenRender(ScreenEvent.Render.Post event) {
        if (isPlayerInventory(event.getScreen())) {
            lastMouseX = event.getMouseX();
            lastMouseY = event.getMouseY();
        }
    }

    /**
     * 鼠标按下：如果按的是「按钮移动」（默认<b>中键</b>），把主题按钮搬到鼠标位置。
     *
     * <p>⚠️ 不吞事件：原版背包里中键还有自己的用处（创造模式"复制物品"），
     * 所以只在"原版不会做别的事"时才搬 —— 见 {@link #wouldCloneItem}。</p>
     */
    private static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!isPlayerInventory(event.getScreen())) {
            return;
        }
        if (!ThemeKeys.isMoveKey(InputConstants.Type.MOUSE.getOrCreate(event.getButton()))) {
            return;
        }
        AbstractContainerScreen<?> inventory = (AbstractContainerScreen<?>) event.getScreen();
        if (wouldCloneItem(inventory)) {
            return;                 // 创造模式 + 鼠标下有物品：这一下交给原版复制
        }
        moveThemeButtonTo(inventory, event.getMouseX(), event.getMouseY());
    }

    /** 键盘按下「按钮移动」（玩家把键位改成了键盘键时走这里） */
    private static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (!isPlayerInventory(event.getScreen())) {
            return;
        }
        if (!ThemeKeys.isMoveKey(InputConstants.getKey(event.getKeyCode(), event.getScanCode()))) {
            return;
        }
        moveThemeButtonTo((AbstractContainerScreen<?>) event.getScreen(), lastMouseX, lastMouseY);
    }

    /** 生存背包和创造背包（是两个不同的类） */
    private static boolean isPlayerInventory(net.minecraft.client.gui.screens.Screen screen) {
        return screen instanceof InventoryScreen || screen instanceof CreativeModeInventoryScreen;
    }

    /** 把按钮搬到鼠标处、拉回可视范围、写进配置 */
    private static void moveThemeButtonTo(AbstractContainerScreen<?> screen, double mouseX, double mouseY) {
        if (themeButton == null) {
            return;
        }
        themeButton.moveToCentered(mouseX, mouseY);
        themeButton.clampTo(screen.width, screen.height);
        ThemeButton.savePosition(themeButton.getX(), themeButton.getY());
    }

    /**
     * 这一下中键会不会被原版用来"复制物品"？
     *
     * <p>原版只在<b>创造模式</b>、且鼠标下面有物品的槽位时才会复制
     * （{@code AbstractContainerScreen.mouseClicked} 里的 {@code button == 2} 分支）。
     * 那种情况下让原版干活，免得玩家想复制物品却把按钮搬走了。</p>
     */
    private static boolean wouldCloneItem(AbstractContainerScreen<?> screen) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !minecraft.player.isCreative()) {
            return false;
        }
        Slot slot = screen.getSlotUnderMouse();
        return slot != null && slot.hasItem();
    }

    /**
     * 客户端每 tick：看看主题是不是被换掉了。
     *
     * <p>这里只负责"发现 + 记日志"；真正让当前界面换皮，由各界面自己查
     * {@code WebViewScreen.themeChanged()}（避免这里持有界面引用导致内存泄漏）。</p>
     */
    private static void onClientTick(ClientTickEvent.Post event) {
        WebViewManager.pollThemeChange();
    }

    private ClientSetup() {
    }
}
