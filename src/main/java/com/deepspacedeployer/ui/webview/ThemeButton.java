package com.deepspacedeployer.ui.webview;

import com.deepspacedeployer.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

/**
 * <b>背包界面上的"界面主题"按钮</b>（圆形、可拖拽、记住位置）。
 *
 * <p>为什么挂在背包界面而不是 ESC 菜单（老板 2026-10-17 的要求）：</p>
 * <ul>
 *   <li>ESC（暂停菜单）上放按钮会<b>不响应 / 延迟响应 / 点了要先"继续游戏"才弹</b> ——
 *       暂停菜单有自己的生命周期（打开即暂停、点击后状态回收），在它上面开新界面时机很脆；</li>
 *   <li>背包界面稳定得多（随时可开、不暂停游戏），FTB 这类模组也是这么做的。</li>
 * </ul>
 *
 * <p><b>操作</b>（对照老板的要求）：</p>
 * <table>
 *   <tr><td><b>左键点击</b></td><td>打开主题选择界面（{@link ThemeChoiceScreen}）</td></tr>
 *   <tr><td><b>右键长按拖拽</b></td><td>把按钮拖到任意位置（松手即记住，写进配置文件）</td></tr>
 * </table>
 *
 * <p>形状是<b>圆形</b>（用几层矩形近似画出来，渲染器只有矩形原语）；图标暂时用占位文字
 * "主题" —— 以后换成真正的图标时，只要改 {@link #drawIcon}。</p>
 *
 * <p>位置存在配置里（{@code ui.theme_button_x/y}）。存的是"相对当前界面尺寸的百分比"？
 * 不是 —— 直接存屏幕坐标，越界时会被拉回可视范围（见 {@link #clampTo}），
 * 这样换分辨率不会把按钮甩到屏幕外。</p>
 */
public class ThemeButton extends AbstractWidget {

    /** 圆的外接正方形边长 */
    public static final int SIZE = 20;

    /** 拖拽状态 */
    private boolean dragging;
    /** 这一次右键按下之后有没有真的拖动过（决定松手时是否算"点击"） */
    private boolean dragged;

    public ThemeButton(int x, int y) {
        super(x, y, SIZE, SIZE, Component.translatable("gui.deepspacedeployer.theme_button"));
    }

    /** 从配置里取按钮位置（读不到就用兜底值） */
    public static int[] loadPosition(int fallbackX, int fallbackY) {
        try {
            return new int[]{Config.THEME_BUTTON_X.get(), Config.THEME_BUTTON_Y.get()};
        } catch (Throwable t) {
            return new int[]{fallbackX, fallbackY};      // 配置还没加载：用兜底
        }
    }

    /** 把位置写进配置（写不进去也不影响本次游戏内使用） */
    public static void savePosition(int x, int y) {
        try {
            Config.THEME_BUTTON_X.set(x);
            Config.THEME_BUTTON_Y.set(y);
            Config.THEME_BUTTON_X.save();
        } catch (Throwable ignored) {
            // 配置只读 / 未加载：忽略
        }
    }

    /**
     * 把按钮拉回可视范围。
     *
     * <p>换分辨率、改界面缩放之后，原来存的位置可能跑到屏幕外 —— 那样按钮就"消失"了。
     * 每次渲染前调一次这个。</p>
     */
    public void clampTo(int screenWidth, int screenHeight) {
        int x = Math.max(0, Math.min(getX(), Math.max(0, screenWidth - SIZE)));
        int y = Math.max(0, Math.min(getY(), Math.max(0, screenHeight - SIZE)));
        if (x != getX() || y != getY()) {
            setPosition(x, y);
        }
    }

    public void setPosition(int x, int y) {
        this.setX(x);
        this.setY(y);
    }

    /** 没在拖拽时，点一下就是"打开主题界面" */
    public boolean wasDragged() {
        return this.dragged;
    }

    // ---------------------------------------------------------------- 交互

    @Override
    public void onClick(double mouseX, double mouseY) {
        // 左键点一下 → 打开主题选择界面
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new ThemeChoiceScreen());
    }

    @Override
    protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
        // 右键按住拖动 → 移动按钮（超出一格才算"拖过"，避免手抖就当成拖拽）
        if (!isRightDown()) {
            return;
        }
        if (Math.abs(dragX) > 0.4 || Math.abs(dragY) > 0.4) {
            this.dragging = true;
            this.dragged = true;
        }
        if (this.dragging) {
            setPosition(Math.round((float) mouseX - SIZE / 2f), Math.round((float) mouseY - SIZE / 2f));
        }
    }

    @Override
    public void onRelease(double mouseX, double mouseY) {
        super.onRelease(mouseX, mouseY);
        if (this.dragging) {
            // 松手即记住位置
            savePosition(getX(), getY());
        }
        this.dragging = false;
        this.dragged = false;
    }

    /** 右键（或其它键）按下时也能拖动 —— 原版 Widget 只在左键触发 onDrag */
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && isMouseOver(mouseX, mouseY)) {
            onDrag(mouseX, mouseY, dragX, dragY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    /** 右键松手：如果是拖拽就存位置；如果只是按住没动，也算"打开界面"（对玩家更宽容） */
    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean handled = super.mouseReleased(mouseX, mouseY, button);
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && isMouseOver(mouseX, mouseY)) {
            if (this.dragging) {
                savePosition(getX(), getY());
            }
            this.dragging = false;
            this.dragged = false;
            return true;
        }
        return handled;
    }

    /** 现在右键是不是按着（决定"按住拖"有效） */
    private static boolean isRightDown() {
        try {
            long window = Minecraft.getInstance().getWindow().getWindow();
            return GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 渲染

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean hover = isMouseOver(mouseX, mouseY);
        int cx = getX() + SIZE / 2;
        int cy = getY() + SIZE / 2;
        int radius = SIZE / 2;

        // 圆底：外圈深色描边 + 内圈填充（悬停时变亮）
        drawCircle(graphics, cx, cy, radius, 0xFF0B0D12);
        drawCircle(graphics, cx, cy, radius - 1,
                hover ? 0xFF2E4A6E : 0xFF223047);
        // 中心一点高光，看起来像颗按钮
        graphics.fill(cx - 2, cy - radius + 3, cx + 2, cy - radius + 4,
                hover ? 0xFF7FC8FF : 0xFF3B5A80);

        // 占位图标：一个"主题"字（换成真图标时改 drawIcon）
        drawIcon(graphics, cx, cy, hover);
    }

    /** 图标（现在是一行占位文字；以后换 png 图标就改这里） */
    private void drawIcon(GuiGraphics graphics, int cx, int cy, boolean hover) {
        Component label = Component.translatable("gui.deepspacedeployer.theme_button_short");
        int w = Minecraft.getInstance().font.width(label);
        graphics.drawString(Minecraft.getInstance().font, label,
                cx - w / 2, cy - 4, hover ? 0xFFE8F4FF : 0xFFCFE3F7, false);
    }

    /**
     * 画一个实心圆（渲染原语只有矩形，所以用几条横线拼出来）。
     *
     * <p>半径 {@code r} 的圆：y 从 -r 到 r，每一行半宽 = {@code sqrt(r²-y²)}。
     * 20px 的按钮画 20 行，足够圆了。</p>
     */
    private static void drawCircle(GuiGraphics graphics, int cx, int cy, int r, int argb) {
        if (r <= 0) {
            return;
        }
        for (int dy = -r; dy <= r; dy++) {
            int half = (int) Math.sqrt(Math.max(0, r * r - dy * dy));
            graphics.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, argb);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
