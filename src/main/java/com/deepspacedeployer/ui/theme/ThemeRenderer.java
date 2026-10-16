package com.deepspacedeployer.ui.theme;

/**
 * <b>渲染器</b>：把算好的布局画到 {@link Canvas} 上。
 *
 * <p>这一段是"游戏内"和"离线预览"<b>共用</b>的 —— 所以老板在预览图上看到什么，
 * 游戏里就是什么（唯一差别是字体度量，见各自的 Canvas 实现）。</p>
 *
 * <p>绘制顺序（和浏览器一致）：自己的背景 → 边框 → 内容（文字行）→ 子元素。
 * {@code overflow:hidden/scroll} 会压一个裁切，scroll 再加一个纵向偏移。</p>
 */
public final class ThemeRenderer {

    private final ThemeSource source;
    private final Canvas canvas;
    /** 每个元素的滚动位置（key = 元素），交互层滚动时改它 */
    private final java.util.Map<HtmlNode, Float> scrollOffsets = new java.util.IdentityHashMap<>();

    public ThemeRenderer(ThemeSource source, Canvas canvas) {
        this.source = source;
        this.canvas = canvas;
    }

    public void render(HtmlNode root) {
        if (root == null) {
            return;
        }
        for (HtmlNode child : root.children) {
            renderNode(child, root.docDir == null ? "" : root.docDir);
        }
    }

    /** 某个元素能不能滚动（内容比容器高） */
    public boolean scrollable(HtmlNode node) {
        return node != null && node.style != null && "scroll".equals(node.style.overflow)
                && node.box != null && node.box.contentHeight > node.box.h + 0.5f;
    }

    public float scrollOf(HtmlNode node) {
        return scrollOffsets.getOrDefault(node, 0f);
    }

    /**
     * <b>滚动表本身</b>（不是拷贝）。
     *
     * <p>给宿主界面用：命中测试必须和渲染看到<b>同一个</b>滚动量，
     * 所以两边共享这一个 Map 实例 —— 宿主改了滚动量，渲染下一帧就用上了。</p>
     */
    public java.util.Map<HtmlNode, Float> scrollView() {
        return scrollOffsets;
    }

    public void scrollBy(HtmlNode node, float delta) {
        if (!scrollable(node)) {
            return;
        }
        float max = Math.max(0f, node.box.contentHeight - node.box.h);
        float v = scrollOf(node) + delta;
        scrollOffsets.put(node, Math.max(0f, Math.min(max, v)));
    }

    // ---------------------------------------------------------------- 递归

    private void renderNode(HtmlNode node, String docDir) {
        if (!node.isElement() || node.box == null || !node.box.visible) {
            return;
        }
        Style st = node.style;
        LayoutBox box = node.box;

        // ① 背景
        if (st.background != null) {
            int bg = applyOpacity(st.background, st.opacity);
            if (st.radius > 0f) {
                canvas.fillRoundRect(box.x, box.y, box.w, box.h, st.radius, bg);
            } else {
                canvas.fillRect(box.x, box.y, box.w, box.h, bg);
            }
        }
        // ①b 背景图（相对该文件所在目录解析，找不到自动回退 default）
        if (st.backgroundImage != null) {
            byte[] png = source.read(st.backgroundImage, docDir);
            if (png != null) {
                canvas.drawImage(png, box.x, box.y, box.w, box.h, st.pixelated);
            }
        }
        // ② 边框
        if (st.borderWidth > 0f && st.borderColor != null) {
            canvas.strokeRect(box.x, box.y, box.w, box.h, st.borderWidth,
                    applyOpacity(st.borderColor, st.opacity));
        }

        // ③ 裁切 / 滚动
        boolean clip = "hidden".equals(st.overflow) || "scroll".equals(st.overflow);
        float scroll = 0f;
        if (clip) {
            canvas.pushClip(box.x, box.y, box.w, box.h);
        }
        if ("scroll".equals(st.overflow)) {
            scroll = scrollOf(node);
            canvas.pushOffset(0f, -scroll);
        }

        // ④ 文字行
        int textColor = st.color != null ? applyOpacity(st.color, st.opacity) : 0xFFE8F4FF;
        for (LayoutBox.TextLine line : box.lines) {
            for (LayoutBox.TextRun run : line.runs) {
                if (run.text == null || run.text.isEmpty()) {
                    continue;
                }
                canvas.drawText(run.text, run.x, line.y, st.fontSize,
                        applyOpacity(run.color != 0 ? run.color : textColor, st.opacity), run.bold);
            }
        }

        // ⑤ 子元素
        for (HtmlNode child : node.children) {
            renderNode(child, docDir);
        }

        if ("scroll".equals(st.overflow)) {
            canvas.popOffset();
        }
        if (clip) {
            canvas.popClip();
        }
    }

    /** 把透明度乘进颜色（画布只有纯色原语，不做整体合成） */
    static int applyOpacity(int argb, float opacity) {
        if (opacity >= 0.999f) {
            return argb;
        }
        int a = (argb >>> 24) & 0xFF;
        int na = Math.max(0, Math.min(255, Math.round(a * Math.max(0f, opacity))));
        return (na << 24) | (argb & 0x00FFFFFF);
    }
}
