package com.deepspacedeployer.ui.webview;

import com.deepspacedeployer.DeepSpaceDeployer;
import com.deepspacedeployer.ui.theme.HtmlNode;
import com.deepspacedeployer.ui.theme.LayoutBox;
import com.deepspacedeployer.ui.theme.LayoutEngine;
import com.deepspacedeployer.ui.theme.ThemeDocument;
import com.deepspacedeployer.ui.theme.ThemeRenderer;
import com.deepspacedeployer.ui.theme.ThemeSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * <b>一个界面 + 一套主题的一帧</b>：把"加载好的文档"和"布局结果"绑在一起。
 *
 * <p>职责很窄，就四件事：</p>
 * <ol>
 *   <li>{@link #load} —— 读主题里的 html（带数据绑定）、算样式；</li>
 *   <li>{@link #layout} —— 按画布尺寸算每个元素的位置；</li>
 *   <li>{@link #render} —— 交给 {@link ThemeRenderer} 画出来（游戏内 / 离线预览共用）；</li>
 *   <li>{@link #hitTest} / {@link #markers} —— 给交互层用：点到了哪个动作、原生槽位该摆在哪。</li>
 * </ol>
 *
 * <p><b>为什么要有这个类</b>：{@code ThemeDocument} 只懂"文档"，不懂"布局和装数据的容器"。
 * 宿主界面（{@link WebViewScreen}）不想每次都把这套流程写一遍，就在这里封一次。</p>
 */
public final class ThemeContext {

    /** 加载、布局之后的文档；null = 这个界面没有主题（调用方回退原生界面） */
    private final ThemeDocument doc;
    private final ThemeSource source;
    private final ThemeRenderer renderer;
    private final LayoutEngine engine;

    /**
     * 滚动偏移的<b>薄同步视图</b>：真正的值在 {@link ThemeRenderer} 里，
     * 这里只是给命中测试用的同一个 Map 实例。
     */
    private final Map<HtmlNode, Float> scrollView;

    /**
     * <b>整页滚动量</b>（px）。主题内容比视口高时，滚轮滚的是这一整页 ——
     * 主题画的框、物品格（data-slot）、点击命中的坐标，全都按它一起偏移。
     *
     * <p>为什么需要：以前只有"元素自己 overflow:scroll"能滚；
     * 老板 2026-10-21 观察到"滚动时物品本该跟着走"，就是这个意思。</p>
     */
    private float globalScrollY;

    /** 布局时用的视口 */
    private float viewportW;
    private float viewportH;
    private float contentHeight;

    private ThemeContext(ThemeDocument doc, ThemeSource source, ThemeRenderer renderer,
                         LayoutEngine engine, Map<HtmlNode, Float> scrollView) {
        this.doc = doc;
        this.source = source;
        this.renderer = renderer;
        this.engine = engine;
        this.scrollView = scrollView;
    }

    // ---------------------------------------------------------------- 加载

    /**
     * 加载某个界面的主题文档。
     *
     * @param source 主题资源根（{@code ThemeManager.active()}）
     * @param id     界面登记项（决定读哪个 html）
     * @param data   数据
     * @param canvas 画布（布局要用它量文字宽度；游戏内传 {@link GuiCanvas}，离线传 PreviewCanvas）
     * @return 可用的上下文；html 不存在 / 解析失败返回 null（<b>调用方必须回退原生界面</b>）
     */
    public static ThemeContext load(ThemeSource source, WebScreenId id, ThemeData data,
                                    com.deepspacedeployer.ui.theme.Canvas canvas) {
        if (source == null || id == null || canvas == null) {
            return null;
        }
        try {
            ThemeDocument doc = ThemeDocument.load(source, id.file(), data);
            if (doc == null || doc.root == null) {
                return null;
            }
            ThemeRenderer renderer = new ThemeRenderer(source, canvas);
            LayoutEngine engine = new LayoutEngine(canvas);
            // 让命中测试能读到渲染器里的滚动值（同一个 Map 实例，不是拷贝）
            Map<HtmlNode, Float> scrollView = renderer.scrollView();
            return new ThemeContext(doc, source, renderer, engine, scrollView);
        } catch (Throwable t) {
            // 主题是玩家可以随便改的文件：任何异常都只记日志，绝不让界面打不开
            DeepSpaceDeployer.LOGGER.warn("[深空铸造体][界面] 主题加载失败（{}）：{}",
                    id.file(), t.toString());
            return null;
        }
    }

    // ---------------------------------------------------------------- 布局

    /** 算布局；返回内容总高度 */
    public float layout(float viewportW, float viewportH) {
        this.viewportW = viewportW;
        this.viewportH = viewportH;
        try {
            this.contentHeight = engine.layout(doc.root, viewportW, viewportH);
            // 布局变了（换主题 / 改分辨率 / 数据变化）→ 滚动量夹回合法范围
            this.globalScrollY = Math.max(0f, Math.min(globalScrollY, maxGlobalScroll()));
        } catch (Throwable t) {
            DeepSpaceDeployer.LOGGER.warn("[深空铸造体][界面] 主题布局失败（{}）：{}",
                    doc.file, t.toString());
            this.contentHeight = viewportH;
        }
        return this.contentHeight;
    }

    public void render() {
        try {
            renderer.render(doc.root);
        } catch (Throwable t) {
            DeepSpaceDeployer.LOGGER.warn("[深空铸造体][界面] 主题绘制失败（{}）：{}",
                    doc.file, t.toString());
        }
    }

    /**
     * 用<b>指定的画布</b>画一帧（游戏内必须走这个）。
     *
     * <p>加载时的画布是"只量不画"的（{@link com.deepspacedeployer.ui.webview.GuiCanvas#measureOnly()}），
     * 只用来算文字宽度和换行位置；真正落笔要换成绑定 {@code GuiGraphics} 的画布。
     * 不换的话界面上什么都看不到（只有一层压暗的灰）。</p>
     */
    public void render(com.deepspacedeployer.ui.theme.Canvas frameCanvas) {
        try {
            renderer.setCanvas(frameCanvas);
            renderer.setGlobalScrollY(globalScrollY);
            renderer.render(doc.root);
        } catch (Throwable t) {
            DeepSpaceDeployer.LOGGER.warn("[深空铸造体][界面] 主题绘制失败（{}）：{}",
                    doc.file, t.toString());
        }
    }

    public float contentHeight() {
        return contentHeight;
    }

    public ThemeDocument document() {
        return doc;
    }

    public ThemeSource source() {
        return source;
    }

    public String file() {
        return doc.file;
    }

    // ---------------------------------------------------------------- 交互

    /**
     * 点到了哪个带动作的元素。
     *
     * <p>返回<b>最近的</b>带 {@code data-action} 的祖先 —— 这样主题作者可以把
     * {@code data-action} 写在容器上，里面放图标/文字，点哪都算点到按钮。</p>
     *
     * @return 动作名（原样，含 {@code buy:5} 这种参数）；没点到返回 null
     */
    public String hitAction(float px, float py) {
        HtmlNode hit = hitNode(px, py);
        if (hit == null) {
            return null;
        }
        HtmlNode n = hit;
        while (n != null) {
            String action = n.attr("data-action");
            if (action != null && !action.isBlank()) {
                return action.trim();
            }
            n = n.parent;
        }
        return null;
    }

    /** 命中的最内层元素（给"悬停提示"这类用途留的口子） */
    public HtmlNode hitNode(float px, float py) {
        if (doc == null || doc.root == null) {
            return null;
        }
        return hitWalk(doc.root, px, py + globalScrollY, 0f, 0f);
    }

    /**
     * 深度优先、<b>后画的先命中</b>（子元素画在父元素上面，
     * 同一层里后面的画在上面，所以要从后往前找）。
     *
     * @param dx,dy 祖先的滚动偏移累计值（和渲染器的推偏移保持一致）
     */
    private HtmlNode hitWalk(HtmlNode node, float px, float py, float dx, float dy) {
        if (!node.isElement() || node.box == null || !node.box.visible) {
            return null;
        }
        // 从后往前（后画的在上面）
        for (int i = node.children.size() - 1; i >= 0; i--) {
            HtmlNode hit = hitWalk(node.children.get(i), px, py, dx, dy);
            if (hit != null) {
                return hit;
            }
        }
        LayoutBox b = node.box;
        float x = b.x - dx;
        float y = b.y - dy;
        if (px >= x && px < x + b.w && py >= y && py < y + b.h) {
            return node;
        }
        return null;
    }

    /** 整页还能往下滚多少（内容不高于视口就是 0） */
    public float maxGlobalScroll() {
        return Math.max(0f, contentHeight - viewportH);
    }

    public float globalScrollY() {
        return globalScrollY;
    }

    /**
     * 整页滚动。
     *
     * @return true = 确实滚动了（调用方可以吃掉这次滚轮）
     */
    public boolean scrollPageBy(float delta) {
        float max = maxGlobalScroll();
        if (max <= 0f) {
            return false;
        }
        float next = Math.max(0f, Math.min(max, globalScrollY + delta));
        if (Math.abs(next - globalScrollY) < 0.5f) {
            return false;
        }
        globalScrollY = next;
        return true;
    }

    /**
     * 鼠标位置是否落在"可滚动容器"里 —— <b>不看内容是否真的超出</b>。
     *
     * <p>为什么需要：老板 2026-10-22 反馈"不管鼠标在哪都是整页滚"。
     * 原因是之前要求"内容必须超出容器"才认这个滚动区，
     * 列表内容刚好没超出时就落到整页滚动上，手感完全不对。</p>
     *
     * <p>现在的规则很直接：<b>指针在列表里就归列表管</b>（哪怕没得滚），
     * 指针在别处才滚整页。</p>
     */
    public HtmlNode scrollContainerAt(float px, float py) {
        // ★ 不再走 hitNode 猜：直接把每个 overflow:scroll 元素的**矩形**拿来比。
        //   （老板 2026-10-22："抛弃识别鼠标坐标，改为小滚动框碰到鼠标指针就触发"）
        //   命中测试受可见性/层叠/滚动补偿影响，容易"差一点就选不中"；
        //   矩形判定简单、可预测：指针落在框里 → 就是它。
        if (doc == null || doc.root == null) {
            return null;
        }
        HtmlNode[] best = {null};
        float[] bestArea = {Float.MAX_VALUE};
        doc.root.walk(n -> {
            if (!n.isElement() || n.box == null || n.style == null) {
                return;
            }
            if (!"scroll".equals(n.style.overflow)) {
                return;
            }
            // ⚠️ 只减"整页滚动量"。
            //    元素**自己**的滚动量只作用于它的**子元素**，不会挪动它自己的框 ——
            //    以前这里多减了 scrollOf(n)，结果框滚几行后判定矩形就移走了，
            //    指针"离开"框 → 掉回整页滚动（老板 2026-10-22 报的就是这个）。
            float x = n.box.x;
            float y = n.box.y - globalScrollY;
            if (px >= x && px < x + n.box.w && py >= y && py < y + n.box.h) {
                float area = n.box.w * n.box.h;
                if (area < bestArea[0]) {      // 取最内层（面积最小）的那个
                    bestArea[0] = area;
                    best[0] = n;
                }
            }
        });
        return best[0];
    }


    /** 某个元素自己的滚动量（命中测试递归时要往下传） */
    float scrollOf(HtmlNode node) {
        return scrollView.getOrDefault(node, 0f);
    }

    /** 能不能滚动（滚轮用） */
    public boolean scrollable(HtmlNode node) {
        return renderer.scrollable(node);
    }

    public void scrollBy(HtmlNode node, float delta) {
        renderer.scrollBy(node, delta);
    }

    /**
     * 鼠标位置上的可滚动元素（滚轮事件用）。
     *
     * <p>从最内层往外找：找到第一个"能滚"的祖先就滚它 —— 和浏览器的行为一致。</p>
     */
    public HtmlNode scrollableAt(float px, float py) {
        HtmlNode hit = hitNode(px, py);
        HtmlNode n = hit;
        while (n != null) {
            if (scrollable(n)) {
                return n;
            }
            n = n.parent;
        }
        return null;
    }

    // ---------------------------------------------------------------- 原生槽位 / 输入框

    /**
     * 主题里标记的"原生控件占位"。
     *
     * <p>原版的物品格和输入框没法用 HTML 画（要支持拖拽、右键分堆、光标输入），
     * 所以主题只负责<b>标记位置</b>：</p>
     * <pre>
     * &lt;div data-slot="input9"&gt;&lt;/div&gt;   ← 3×3 投料格摆这里
     * &lt;div data-slot="card"&gt;&lt;/div&gt;     ← 银行卡槽摆这里
     * &lt;div data-input="价格"&gt;&lt;/div&gt;    ← 输入框摆这里
     * </pre>
     *
     * @return 标记名 → 位置（相对主题画布左上角；可能为空表）
     */
    public Map<String, Marker> markers() {
        if (doc == null || doc.root == null) {
            return Map.of();
        }
        Map<String, Marker> out = new java.util.LinkedHashMap<>();
        collectMarkers(doc.root, 0f, -globalScrollY, out);
        return out;
    }

    private void collectMarkers(HtmlNode node, float dx, float dy, Map<String, Marker> out) {
        if (!node.isElement() || node.box == null || !node.box.visible) {
            return;
        }
        String slot = node.attr("data-slot");
        String input = node.attr("data-input");
        if (slot != null && !slot.isBlank()) {
            out.putIfAbsent("slot:" + slot.trim(), boxMarker(node, dx, dy));
        }
        if (input != null && !input.isBlank()) {
            out.putIfAbsent("input:" + input.trim(), boxMarker(node, dx, dy));
        }
        // ⚠️ 渲染器只做纵向滚动（pushOffset(0, -scroll)），所以只累加 dy。
        //    以前这里把滚动加到了 dx 上、dy 原样传下去 —— 结果：
        //    元素滚动了，但 data-slot 标记不动 → 物品格不跟框走（错位）。
        float ndy = dy + scrollOf(node);
        for (HtmlNode child : node.children) {
            collectMarkers(child, dx, ndy, out);
        }
    }

    private static Marker boxMarker(HtmlNode node, float dx, float dy) {
        return new Marker(node.box.x - dx, node.box.y - dy, node.box.w, node.box.h,
                node.attr("data-slot", node.attr("data-input", "")));
    }

    /** 一个占位标记的位置（相对主题画布左上角） */
    public record Marker(float x, float y, float w, float h, String name) {

        /** 可序列化的一行（自检/日志用） */
        public String brief() {
            return name + "(" + Math.round(x) + "," + Math.round(y) + " "
                    + Math.round(w) + "x" + Math.round(h) + ")";
        }
    }

    // ---------------------------------------------------------------- 自检

    /**
     * 遍历所有元素，收集"用到但没实现"的风险点（给自检命令用）。
     *
     * <p>现在只查两件事：有没有动作、有没有槽位标记。以后要加"未支持的 CSS 属性"报告也走这里。</p>
     */
    public List<String> describe() {
        List<String> out = new ArrayList<>();
        if (doc == null || doc.root == null) {
            return out;
        }
        List<String> actions = new ArrayList<>();
        List<String> slots = new ArrayList<>();
        doc.root.walk(n -> {
            if (!n.isElement()) {
                return;
            }
            String a = n.attr("data-action");
            if (a != null && !a.isBlank()) {
                actions.add(a.trim());
            }
            String s = n.attr("data-slot");
            if (s != null && !s.isBlank()) {
                slots.add(s.trim());
            }
            String in = n.attr("data-input");
            if (in != null && !in.isBlank()) {
                slots.add("input:" + in.trim());
            }
        });
        Collections.sort(actions);
        Collections.sort(slots);
        out.add("动作 " + actions.size() + " 个：" + actions);
        out.add("占位 " + slots.size() + " 个：" + slots);
        out.add("内容高 " + Math.round(contentHeight) + "（视口 " + Math.round(viewportW)
                + "x" + Math.round(viewportH) + "）");
        return out;
    }

    /** 主题渲染器的滚动表（只读视图，给诊断用） */
    Map<HtmlNode, Float> scrollTable() {
        return Collections.unmodifiableMap(scrollView);
    }
}
