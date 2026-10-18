package com.deepspacedeployer.ui.webview;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * <b>模组的按键绑定</b>（客户端专用）。
 *
 * <p>目前只有一个：<b>「按钮移动」</b> —— 默认<u>鼠标中键</u>。</p>
 *
 * <p><b>怎么用</b>（老板 2026-10-17 的要求）：</p>
 * <ol>
 *   <li>按 <b>E</b> 打开背包；</li>
 *   <li>把鼠标移到你想让按钮去的位置；</li>
 *   <li>按一下<b>中键</b> → 那颗圆形「主题」按钮就搬过去了（位置记进配置）。</li>
 * </ol>
 *
 * <p>键位可以在 <b>选项 → 控制 → 按键绑定 →「深空铸造体」</b> 分类里改
 * （键盘、鼠标键都行）。分类名和键名都走语言文件，见
 * {@code key.categories.deepspacedeployer} / {@code key.deepspacedeployer.theme_button_move}。</p>
 *
 * <p>⚠️ <b>为什么不用 {@code KeyMapping.isDown()} 判断</b>：原版的 `isDown()` 是个字段，
 * 只在"没有界面打开"的时候由 {@code KeyboardHandler}/{@code MouseHandler} 更新 ——
 * 背包界面开着时它永远是旧的。所以界面内的按键要走
 * {@code ScreenEvent.MouseButtonPressed}/{@code KeyPressed}，再用
 * {@link #isMoveKey(InputConstants.Key)} 拿"玩家当前绑定的那个键"去比对
 * （这样玩家改键位也照样生效）。</p>
 */
public final class ThemeKeys {

    /** 按键绑定分类（语言键） */
    public static final String CATEGORY = "key.categories.deepspacedeployer";

    /**
     * 「按钮移动」：在背包界面里按下它，把主题按钮搬到鼠标位置。
     *
     * <p>默认<b>鼠标中键</b>。用 {@link InputConstants.Type#MOUSE} 是为了让
     * "控制 → 按键绑定"里能直接录鼠标键（原版鼠标键映射也是这么注册的）。</p>
     */
    public static final KeyMapping MOVE_BUTTON = new KeyMapping(
            "key.deepspacedeployer.theme_button_move",
            InputConstants.Type.MOUSE,
            InputConstants.MOUSE_BUTTON_MIDDLE,
            CATEGORY);

    /** 注册到模组事件总线（{@link com.deepspacedeployer.client.ClientSetup} 里调用） */
    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(ThemeKeys::onRegisterKeyMappings);
    }

    private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(MOVE_BUTTON);
    }

    /** 玩家按下的这个键，是不是「按钮移动」（支持改键位） */
    public static boolean isMoveKey(InputConstants.Key pressed) {
        return pressed != null && MOVE_BUTTON.getKey().equals(pressed);
    }

    private ThemeKeys() {
    }
}
