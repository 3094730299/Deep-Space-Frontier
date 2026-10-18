package com.deepspacedeployer.ui.webview;

import com.deepspacedeployer.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
 *   <tr><td><b>按键「按钮移动」（默认鼠标中键）</b></td>
 *       <td>背包界面里把鼠标移到想去的地方按一下 → 按钮<b>搬过去</b>（记进配置）。
 *           键位在「选项 → 控制 → 按键绑定」里可改，见 {@link ThemeKeys#MOVE_BUTTON}</td></tr>
 * </table>
 *
 * <p><b>默认位置：屏幕底部居中</b>（老板 2026-10-17 的要求）。</p>
 * <p>一开始默认在左上角，但左上角<b>会和 FTB 这类模组的按钮打架</b>（被盖住就看不见了），
 * 底部居中那块一般是空的。配置里 {@code ui.theme_button_x/y} 初始是 {@code -1}，
 * 意思是"按屏幕尺寸自动放底部居中"；一旦玩家拖过或按键摆过，就变成具体的坐标。</p>
 *
 * <p>形状是<b>圆形</b>（用几层矩形近似画出来，渲染器只有矩形原语），外面一圈<b>深蓝描边</b>；
 * 图标是老板给的像素图，处理成 48×48 的透明贴图放在
 * {@code assets/deepspacedeployer/textures/gui/theme_button.png}（见
 * {@code tools/process_theme_icon.py}：它会抠掉原图里"画进图里的浅蓝棋盘格"背景）。
 * 想换图标就换那张 48×48 的 png，代码不用动。</p>
 *
 * <p>位置存在配置里（{@code ui.theme_button_x/y}），越界时会被拉回可视范围（见 {@link #clampTo}），
 * 这样换分辨率不会把按钮甩到屏幕外。</p>
 */
public class ThemeButton extends AbstractWidget {

    /** 圆的外接正方形边长 */
    public static final int SIZE = 20;

    /** 图标贴图（48×48 的像素图，按 24×24 画出来；放在 textures/gui/ 下） */
    private static final ResourceLocation ICON =
            ResourceLocation.fromNamespaceAndPath("deepspacedeployer", "textures/gui/theme_button.png");

    /** 图标画多大（比按钮略大一点，让圆环只露一圈边） */
    private static final int ICON_SIZE = 24;

    /** 深蓝描边（老板 2026-10-17 要求"加个深蓝描边"） */
    private static final int BORDER = 0xFF14215E;

    /** 配置里的"还没摆过"标记：见到它就按屏幕尺寸放到**底部居中** */
    public static final int AUTO = -1;

    /** 拖拽状态 */
    private boolean dragging;
    /** 这一次右键按下之后有没有真的拖动过（决定松手时是否算"点击"） */
    private boolean dragged;

    public ThemeButton(int x, int y) {
        super(x, y, SIZE, SIZE, Component.translatable("gui.deepspacedeployer.theme_button"));
    }

    /**
     * 从配置里取按钮位置；没摆过（-1）就<b>底部居中</b>。
     *
     * @param screenWidth  当前界面宽度（GUI 缩放后的坐标）
     * @param screenHeight 当前界面高度
     */
    public static int[] loadPosition(int screenWidth, int screenHeight) {
        int x = AUTO;
        int y = AUTO;
        try {
            x = Config.THEME_BUTTON_X.get();
            y = Config.THEME_BUTTON_Y.get();
        } catch (Throwable ignored) {
            // 配置还没加载：当作没摆过
        }
        if (x == AUTO) {
            x = (screenWidth - SIZE) / 2;
        }
        if (y == AUTO) {
            y = screenHeight - SIZE - 6;            // 贴近底部，留 6px 边距
        }
        return new int[]{x, y};
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
     * <p>三种情况会用到它：</p>
     * <ol>
     *   <li>换分辨率 / 改界面缩放之后，原来存的坐标跑到屏幕外 → 按钮"消失"了；</li>
     *   <li>中键摆放时鼠标贴近边缘，按钮会有一半在屏幕外；</li>
     *   <li>配置里存了 {@code -1}（还没摆过）→ 由 {@link #loadPosition} 先算成底部居中。</li>
     * </ol>
     *
     * <p>⚠️ 这里<b>不做</b>"不许盖在物品栏上"的限制：原版界面并没有这种约束，
     * 玩家的槽位照样能点（按钮只占 20×20，且是最后加入渲染的，点按钮时不会穿透到槽位）。
     * 所以按老板说的"能覆盖物品栏就无需特殊处理"。</p>
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

    /** 左键点按钮 → 打开主题选择界面（只在真的点在按钮上时触发） */
    @Override
    public void onClick(double mouseX, double mouseY) {
        if (!isMouseOver(mouseX, mouseY)) {
            return;
        }
        Minecraft.getInstance().setScreen(new ThemeChoiceScreen());
    }

    /**
     * 把按钮<b>以鼠标位置为中心</b>放过去（越界由调用方 {@link #clampTo} 拉回）。
     *
     * <p>给"按键绑定：按钮移动"用 —— 默认是**鼠标中键**：在背包界面里把鼠标移到想去的地方，
     * 按一下中键，按钮就搬过去。键位在「选项 → 控制 → 按键绑定」里能改
     * （见 {@code ThemeKeys.MOVE_BUTTON}）。</p>
     */
    public void moveToCentered(double mouseX, double mouseY) {
        moveTo((int) Math.round(mouseX) - SIZE / 2, (int) Math.round(mouseY) - SIZE / 2);
    }

    /** 移动到指定位置（拖拽 / 键位共用；越界会被 clampTo 拉回来） */
    public void moveTo(int x, int y) {
        setPosition(x, y);
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
            moveTo(Math.round((float) mouseX - SIZE / 2f), Math.round((float) mouseY - SIZE / 2f));
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

        // 圆底：深色填充（悬停时变亮一点）
        drawCircle(graphics, cx, cy, radius, hover ? 0xFF16233A : 0xFF0B0D12);
        // ★ 深蓝描边：画一个"实心深蓝圆 + 中间再盖一层圆底"→ 露出来的就是一圈环。
        //    ⚠️ 别用半透明白/黑去"擦"里面：那只是混合，会在圆里留下脏色（踩过）。
        drawCircle(graphics, cx, cy, radius, BORDER);
        drawCircle(graphics, cx, cy, radius - 2, hover ? 0xFF16233A : 0xFF0B0D12);

        // ★ 图标：贴图里已经抠掉背景、是圆的，直接盖在圆底上
        int half = ICON_SIZE / 2;
        graphics.blit(ICON, cx - half, cy - half, 0f, 0f, ICON_SIZE, ICON_SIZE,
                ICON_SIZE, ICON_SIZE);
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
