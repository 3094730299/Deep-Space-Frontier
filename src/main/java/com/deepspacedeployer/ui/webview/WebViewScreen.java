package com.deepspacedeployer.ui.webview;

import com.deepspacedeployer.DeepSpaceDeployer;
import com.deepspacedeployer.ui.theme.ThemeSource;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <b>主题化界面的宿主</b>：把"主题画出来 + 把鼠标点击翻译成动作"这一层封装起来。
 *
 * <p>用法（每个要主题化的界面都这么接）：</p>
 * <pre>
 * class BuyerScreen extends AbstractContainerScreen&lt;MachineMenu&gt; {
 *     private final WebViewScreen webview = new WebViewScreen(WebScreenId.BUYER);
 *
 *     protected void init() {
 *         super.init();
 *         webview.at(this.leftPos, this.topPos);        // 主题画布对齐到界面
 *         webview.setActionSink(this::onThemeAction);
 *         webview.reload(this::buildData);              // 给数据 → 试着加载主题
 *         if (!webview.visible()) { …建原生控件… }        // 没主题才需要原生控件
 *     }
 *
 *     public void render(GuiGraphics g, int mx, int my, float pt) {
 *         super.render(g, mx, my, pt);                  // 原生界面照常画
 *         webview.render(g, mx, my, pt);                // 主题盖在上面；没主题就什么都不画
 *     }
 * }
 * </pre>
 *
 * <p><b>三条硬规矩</b>（都是踩过的教训）：</p>
 * <ol>
 *   <li><b>主题加载/布局/绘制任何一步出错，都退回原生界面</b> —— 玩家把主题改坏了，
 *       最差的结果只能是"界面变回老样子"，绝不能打不开（所以这里到处是 try/catch）；</li>
 *   <li>数据变了要调 {@link #refresh()} 重新绑定 —— 主题是<b>静态快照</b>：
 *       没有 JS，也不做差量更新，重新绑一遍是最省事也最可预测的做法；</li>
 *   <li>{@link #visible()} 为 false 时，交互事件<b>一个都不要拦</b> ——
 *       否则原生控件会点不动。</li>
 * </ol>
 *
 * <p><b>性能</b>：解析 + 绑定 + 布局只在 {@link #reload} / {@link #refresh} 时做一次，
 * {@link #render} 每帧只负责画（不重新解析）。数据变化不频繁的界面（比如主题选择菜单）
 * 一直不调 refresh 也没问题。</p>
 */
public final class WebViewScreen {

    private final WebScreenId id;

    /** 当前加载好的文档；null = 这个界面没有主题 */
    private ThemeContext context;
    /** 画布在屏幕上的位置 */
    private int originX;
    private int originY;
    /** 视口尺寸 */
    private int viewW;
    private int viewH;

    private ActionSink sink = ActionSink.NONE;
    private DataSupplier dataSupplier;
    private ThemeData lastData;

    /** 上一次没加载成功的原因（给日志和自检用） */
    private String lastError = "";
    /** 上次加载时用的是哪套主题（主题换了要自动重载） */
    private String loadedTheme = "";
    /** 是否允许使用主题（配置关掉 / 界面自己禁用时不加载） */
    private boolean enabled = true;

    /**
     * @param id 界面登记项（决定读哪个 html、默认多大）
     */
    public WebViewScreen(WebScreenId id) {
        this.id = id;
        this.viewW = id.width();
        this.viewH = id.height();
    }

    /** 数据从哪里来 */
    @FunctionalInterface
    public interface DataSupplier {
        ThemeData get();
    }

    // ---------------------------------------------------------------- 装配

    public WebViewScreen setActionSink(ActionSink sink) {
        this.sink = sink == null ? ActionSink.NONE : sink;
        return this;
    }

    /** 主题画布左上角在屏幕上的位置（对齐到原生界面） */
    public WebViewScreen at(int screenX, int screenY) {
        this.originX = screenX;
        this.originY = screenY;
        return this;
    }

    /** 视口尺寸（默认用登记表里的建议值） */
    public WebViewScreen size(int w, int h) {
        this.viewW = w > 0 ? w : id.width();
        this.viewH = h > 0 ? h : id.height();
        return this;
    }

    /** 允许/禁止使用主题 */
    public WebViewScreen setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            this.context = null;
        }
        return this;
    }

    public boolean enabled() {
        return enabled;
    }

    // ---------------------------------------------------------------- 加载 / 刷新

    /** 设置数据源并立刻加载一次 */
    public void reload(DataSupplier supplier) {
        this.dataSupplier = supplier;
        refresh();
    }

    /**
     * 重新取数据、重新绑定、重新布局。
     *
     * <p>界面状态变了（余额、列表、开关……）就调它。<b>不要每帧调</b>：
     * 一次 refresh 等于重新解析一遍 HTML/CSS，主循环里请用"变了才调"。</p>
     */
    public void refresh() {
        if (!enabled) {
            context = null;
            return;
        }
        // ★ 按【本界面】取主题：高级设置里可以把 A 界面指定成主题 2、B 界面用主题 1
        ThemeSource source = WebViewManager.activeFor(id);
        if (source == null) {
            context = null;
            lastError = "没有可用主题";
            return;
        }
        ThemeData data = dataSupplier == null ? ThemeData.empty() : dataSupplier.get();
        if (data == null) {
            data = ThemeData.empty();
        }
        lastData = data;
        loadInto(source, data, null);
    }

    /**
     * 真正加载一次。
     *
     * @param measuring 量文字用的画布；游戏内传绘制用的 {@link GuiCanvas}
     *                  （<b>必须和真正画的时候同一套度量</b>），传 null 就用无绘制版
     */
    private void loadInto(ThemeSource source, ThemeData data, GuiCanvas measuring) {
        com.deepspacedeployer.ui.theme.Canvas canvas =
                measuring != null ? measuring : GuiCanvas.measureOnly();
        ThemeContext loaded;
        try {
            loaded = ThemeContext.load(source, id, data, canvas);
        } catch (Throwable t) {
            loaded = null;
            lastError = t.toString();
        }
        loadedTheme = source.name();
        if (loaded == null) {
            context = null;
            if (lastError.isEmpty()) {
                lastError = "主题里没有 " + id.file() + "（用原生界面）";
            }
            return;
        }
        try {
            loaded.layout(viewW, viewH);
        } catch (Throwable t) {
            context = null;
            lastError = "布局失败：" + t;
            return;
        }
        context = loaded;
        lastError = "";
    }

    /** 主题换了没（宿主每帧查一次，换了就 refresh） */
    public boolean themeChanged() {
        return !WebViewManager.themeNameFor(id).equals(loadedTheme);
    }

    // ---------------------------------------------------------------- 渲染

    /** 这个界面现在是不是在用主题画 */
    public boolean visible() {
        return context != null;
    }

    /**
     * 画主题（在原生界面之后调，等于"盖在上面"）。
     *
     * <p>每帧只做绘制；解析和布局在 {@link #refresh()} 里已经做完了。</p>
     */
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ThemeContext ctx = context;
        if (ctx == null) {
            return;
        }
        GuiCanvas canvas = null;
        try {
            canvas = new GuiCanvas(graphics, originX, originY);
            // ★ 必须把"这一帧的画布"交给上下文 —— 加载时用的是只量不画的画布，
            //   不换的话主题永远画不到屏幕上（症状：只有一层压暗的灰色，空无一物）。
            ctx.render(canvas);
        } catch (Throwable t) {
            // 画崩了就当这个界面没有主题 —— 绝不让整个界面挂掉
            DeepSpaceDeployer.LOGGER.warn("[深空铸造体][界面] 主题绘制异常（{}），本帧起回退原生界面：{}",
                    id.file(), t.toString());
            context = null;
            lastError = "绘制异常：" + t;
        } finally {
            if (canvas != null) {
                // 只清本帧的偏移/裁切状态；贴图缓存是跨帧共享的，不能在这里释放
                canvas.finishFrame();
            }
        }
    }

    /**
     * 界面关闭时调一次：把跨帧的临时贴图放掉。
     *
     * <p>不释放也不会出错（有上限、满了自动清），但主动放掉更干净。</p>
     */
    public void close() {
        GuiCanvas.releaseAll();
        context = null;
    }

    // ---------------------------------------------------------------- 交互

    /**
     * 点击：命中带动作的元素 → 交给动作出口。
     *
     * @return true = 这次点击被主题吃掉了（宿主不要再处理）
     */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        ThemeContext ctx = context;
        if (ctx == null || button != 0) {
            return false;
        }
        float px = (float) (mouseX - originX);
        float py = (float) (mouseY - originY);
        try {
            var node = ctx.hitNode(px, py);
            if (node == null) {
                return false;
            }
            // 往上找最近的带动作的祖先（点图标/文字也算点到按钮）
            com.deepspacedeployer.ui.theme.HtmlNode n = node;
            String action = null;
            while (n != null) {
                String a = n.attr("data-action");
                if (a != null && !a.isBlank()) {
                    action = a.trim();
                    break;
                }
                n = n.parent;
            }
            if (action == null) {
                return false;
            }
            boolean handled = sink.onAction(action, node);
            if (!handled) {
                DeepSpaceDeployer.LOGGER.debug("[深空铸造体][界面] 主题动作没人处理：{}（{}）",
                        action, id.file());
            }
            return true;
        } catch (Throwable t) {
            DeepSpaceDeployer.LOGGER.warn("[深空铸造体][界面] 动作派发异常（{}）：{}",
                    id.file(), t.toString());
            return false;
        }
    }

    /** 滚轮：滚鼠标下面的那个可滚动容器 */
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        ThemeContext ctx = context;
        if (ctx == null) {
            return false;
        }
        float px = (float) (mouseX - originX);
        float py = (float) (mouseY - originY);
        try {
            var node = ctx.scrollableAt(px, py);
            if (node == null) {
                return false;
            }
            ctx.scrollBy(node, (float) (-delta * 10f));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 原生槽位/输入框该摆在哪儿（主题里用 data-slot / data-input 标记） */
    public java.util.Map<String, ThemeContext.Marker> markers() {
        ThemeContext ctx = context;
        return ctx == null ? java.util.Map.of() : ctx.markers();
    }

    // ---------------------------------------------------------------- 诊断

    public WebScreenId screenId() {
        return id;
    }

    public ThemeContext context() {
        return context;
    }

    public String lastError() {
        return lastError;
    }

    /** 一行状态（给 /dsd selftest 和日志用） */
    public String describe() {
        ThemeContext ctx = context;
        if (ctx == null) {
            return id.file() + "：用原生界面" + (lastError.isEmpty() ? "" : "（" + lastError + "）");
        }
        return id.file() + "：" + ctx.document().summary();
    }
}
