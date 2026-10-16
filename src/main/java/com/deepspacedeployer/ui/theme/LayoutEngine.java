package com.deepspacedeployer.ui.theme;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>布局引擎</b>：把"元素树 + 样式"算成每个元素的 {@link LayoutBox}（绝对坐标）。
 *
 * <p>支持的范围（就是我们承诺给老板的子集）：</p>
 * <ul>
 *   <li>块级：竖直堆叠，宽度默认撑满父容器</li>
 *   <li>行内：文本 + {@code span/b/i} 合成一条文字流，按容器宽度<b>自动折行</b>，
 *       支持 {@code text-align: left/center/right}</li>
 *   <li>弹性：{@code display:flex} + {@code flex-direction: row/column} +
 *       {@code gap} + {@code align-items} + {@code justify-content}</li>
 *   <li>定位：{@code position:absolute}（相对最近的 relative/absolute 祖先）</li>
 * </ul>
 *
 * <p>刻意不做：浮动、{@code flex-wrap}、百分比内边距、外边距合并、表格。
 * 这些都是"真浏览器"才需要的东西，主题里用不上。</p>
 *
 * <p>宽度策略很简单：<b>写了 width 就用它，没写就撑满可用宽度</b>；
 * flex 子项没写宽度时按"内容本身要多宽"算（文字宽度 / 子项之和）。</p>
 */
public final class LayoutEngine {

    private final Canvas canvas;
    /** 视口尺寸（主题画布的可用区域） */
    private float viewportW;
    private float viewportH;

    public LayoutEngine(Canvas canvas) {
        this.canvas = canvas;
    }

    /**
     * 算整棵树。
     *
     * @param root 文档根（样式已由 StyleResolver 填好）
     * @return 内容总高度（可能超过视口，用于滚动）
     */
    public float layout(HtmlNode root, float viewportW, float viewportH) {
        this.viewportW = viewportW;
        this.viewportH = viewportH;
        if (root == null) {
            return 0f;
        }
        // ★ 主题可以声明"可用高度比画布小"：根元素上写 data-viewport="216"
        //   场景：界面底部有一块被别的东西占住（比如原版画的玩家背包），
        //   主题只负责上面那部分。声明之后，文字截断、overflow 的滚动范围都按 216 算，
        //   主题作者不必自己算"还剩多少像素"，也不会出现"内容被背包盖住"。
        float flowLimit = declaredViewportHeight(root, viewportH);
        float y = 0f;
        // "流内内容的真实底边"：绝对定位的元素不参与流，所以不能拿它们的 y 当底边
        float flowBottom = 0f;
        for (HtmlNode child : root.children) {
            if (!child.isElement()) {
                continue;
            }
            float before = y;
            y += layoutChild(child, 0f, y, viewportW, flowLimit - y, null);
            if (child.box != null && child.box.visible && !"absolute".equals(
                    child.style == null ? "static" : child.style.position)) {
                flowBottom = Math.max(flowBottom, before + child.box.usedHeight);
            }
        }
        // 返回值 = 流内内容的底边，且至少等于可用高度。
        //
        // ⚠️ 不要直接返回 y：一个 bottom:8 的绝对定位背包占位，坐标在视口内，
        //    但顺序排到它时 y 可能已经超出 —— 那样会报出假溢出，
        //    把真正的排版问题淹掉（排查时吃过这个亏）。
        return Math.max(flowBottom, flowLimit);
    }

    /**
     * 根元素上 {@code data-viewport="216"} 声明的可用高度。
     *
     * <p>返回画布高度（没声明时）。只认第一个能解析成正数的值，写错了就当没写。</p>
     */
    private static float declaredViewportHeight(HtmlNode root, float fallback) {
        if (root == null) {
            return fallback;
        }
        for (HtmlNode child : root.children) {
            if (!child.isElement()) {
                continue;
            }
            String raw = child.attr("data-viewport");
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                float v = Float.parseFloat(raw.trim().replace("px", ""));
                if (v > 0f) {
                    return v;
                }
            } catch (NumberFormatException ignored) {
                // 写错了就当没写，不要因为一个属性让整个界面加载失败
            }
        }
        return fallback;
    }

    // ---------------------------------------------------------------- 单个元素

    /**
     * 摆放一个元素。
     *
     * @param x,y        左上角（画布坐标）
     * @param availW     可用宽度
     * @param availH     可用高度
     * @param containing 最近的定位祖先（absolute 的参照）；null = 画布本身
     * @return 该元素占用的高度（含上下外边距）
     */
    private float layoutChild(HtmlNode node, float x, float y,
                              float availW, float availH, LayoutBox containing) {
        return layoutChild(node, x, y, availW, availH, containing, -1f);
    }

    /**
     * @param forcedW flex 主轴已经算好的外框宽度（&gt;0 时用它，不再对百分比二次求值）
     */
    private float layoutChild(HtmlNode node, float x, float y, float availW, float availH,
                              LayoutBox containing, float forcedW) {
        Style st = node.style;
        if (st == null) {
            st = node.style = new Style();
        }
        LayoutBox box = new LayoutBox();
        node.box = box;

        if (!st.visible() || node.dataHidden) {
            box.visible = false;
            box.x = x;
            box.y = y;
            return 0f;
        }

        // ---- position:absolute：脱离文档流，相对定位祖先 ----
        if ("absolute".equals(st.position)) {
            float cw = containing != null ? containing.w : viewportW;
            float ch = containing != null ? containing.h : viewportH;
            float ox = containing != null ? containing.x : 0f;
            float oy = containing != null ? containing.y : 0f;
            float w = st.width.isAuto() ? intrinsicWidth(node, cw) : st.width.resolve(cw);
            w += 2f * st.borderWidth + st.padLeft + st.padRight;
            float h = st.height.isAuto() ? measureHeight(node, w) : st.height.resolve(ch);
            float px = ox + (st.left != null ? st.left : cw - w - (st.right != null ? st.right : 0f));
            float py = oy + (st.top != null ? st.top : ch - h - (st.bottom != null ? st.bottom : 0f));
            box.x = px;
            box.y = py;
            box.w = w;
            box.h = h;
            layoutContents(node, box, w, h, containing != null ? containing : box);
            return 0f;                                  // 不占流内高度
        }

        // ---- 外边距 ----
        float startY = y + st.marTop;
        float innerAvailW = Math.max(0f, availW - st.marLeft - st.marRight);

        // ---- 宽度：flex 主轴给的就用它；否则写了 width 就用、没写撑满 ----
        //      ⚠️ 宽度是"边框盒"（含 padding 与 border），和 CSS 默认的 content-box 不同，
        //         这样 width:100% 不会因为 padding 而溢出（写界面的更容易理解）。
        float outerW;
        if (forcedW > 0f) {
            outerW = forcedW;
        } else if (!st.width.isAuto()) {
            outerW = st.width.resolve(innerAvailW);
        } else {
            outerW = innerAvailW;
        }
        outerW = Math.max(1f, outerW);

        // ---- 高度：写了就用，没写先按内容量 ----
        float outerH;
        boolean autoHeight = st.height.isAuto();
        if (autoHeight) {
            outerH = measureHeight(node, outerW);
        } else {
            outerH = st.height.resolve(availH);
        }

        // 写死高度时，文字最多能放几行（放着让 LayoutEngine 截断，见 layoutText）
        node.textBoxHeight = autoHeight
                ? Float.MAX_VALUE
                : Math.max(0f, outerH - 2f * st.borderWidth - st.padTop - st.padBottom);

        box.x = x + st.marLeft;
        box.y = startY;
        box.w = outerW;
        box.h = outerH;

        // ---- 摆内容；内容比容器高时，把容器撑高（除非写了固定高度） ----
        float used = layoutContents(node, box, outerW, outerH,
                "relative".equals(st.position) || "absolute".equals(st.position) ? box : containing);
        box.contentHeight = used;
        if (autoHeight) {
            float need = used + 2f * st.borderWidth + st.padTop + st.padBottom;
            if (need > outerH) {
                box.h = need;
            }
        }
        // 占位高度：写了固定高度的容器，内容再多也只占它自己那么高
        // （多出来的内容由 overflow:hidden/scroll 裁掉，不把后面的兄弟元素往下推）。
        // ⚠️ 这里如果返回 box.h（被内容撑高的那个）就会溢出：一个 height:104 的列表
        //    放了 4 行会被算成 170 高，后面所有元素跟着往下跑。
        box.usedHeight = autoHeight ? box.h : outerH;

        return box.usedHeight + st.marTop + st.marBottom;
    }

    /** 摆这个元素的内容（文字流 + 子元素），返回内容占用的高度 */
    private float layoutContents(HtmlNode node, LayoutBox box, float outerW, float outerH,
                                 LayoutBox containing) {
        Style st = node.style;
        float cx = box.contentX(st);
        float cy = box.contentY(st);
        float cw = box.contentW(st);
        float ch = box.contentH(st);
        float y = cy;

        // ① 行内内容（文本 + span/b/i）合成一条文字流
        List<HtmlNode> inline = new ArrayList<>();
        List<HtmlNode> blocks = new ArrayList<>();
        splitChildren(node, inline, blocks);

        if (!inline.isEmpty()) {
            y += layoutText(inline, node, cx, y, cw);
        }

        // ② 块级子元素
        if (!blocks.isEmpty()) {
            if (y > cy + 0.01f && !st.isFlex()) {
                y += st.gap > 0 ? st.gap : 0f;              // 文字流与块之间留一点缝
            }
            if (st.isFlex()) {
                y += layoutFlex(blocks, node, cx, cy, cw, ch, containing);
            } else {
                for (HtmlNode child : blocks) {
                    float used = layoutChild(child, cx, y, cw, ch - (y - cy), containing);
                    y += used;
                }
            }
        }
        return y - cy;
    }

    /** 子元素分成"行内"和"块级"两拨 */
    private void splitChildren(HtmlNode node, List<HtmlNode> inline, List<HtmlNode> blocks) {
        for (HtmlNode c : node.children) {
            if (c.isText()) {
                inline.add(c);
                continue;
            }
            String tag = c.tag;
            // v1 的行内元素只有这几个；img 当块级处理（不支持行内图文混排，见 README 子集说明）
            boolean isInlineTag = "span".equals(tag) || "b".equals(tag) || "i".equals(tag)
                    || "br".equals(tag);
            boolean styleSaysBlock = c.style != null
                    && (!"block".equals(c.style.display) || "flex".equals(c.style.display));
            if (isInlineTag && !styleSaysBlock) {
                inline.add(c);
            } else {
                blocks.add(c);
            }
        }
    }

    // ---------------------------------------------------------------- 文字流

    /** 把行内节点折行并摆好，返回占用高度 */
    private float layoutText(List<HtmlNode> inline, HtmlNode owner, float x, float y, float maxW) {
        Style ownerStyle = owner.style;
        LayoutBox box = owner.box;

        // 先把行内内容摊平成一串"带格式的片段"，同时处理 <br>
        List<LayoutBox.TextRun> flat = new ArrayList<>();
        collectRuns(inline, ownerStyle, flat);

        float size = ownerStyle.fontSize;
        float lineH = ownerStyle.effectiveLineHeight();
        float lineTop = y;

        List<LayoutBox.TextRun> lineRuns = new ArrayList<>();
        float lineW = 0f;

        for (LayoutBox.TextRun run : flat) {
            if ("\n".equals(run.text)) {                    // <br>
                flushLine(box, lineRuns, lineW, x, lineTop, maxW, ownerStyle, lineH);
                lineRuns = new ArrayList<>();
                lineW = 0f;
                lineTop += lineH;
                continue;
            }
            // 按空格切成词，逐词折行（中文没有空格 → 按字符切）
            for (String piece : splitForWrap(run.text)) {
                float pw = canvas.textWidth(piece, size, run.bold);
                if (lineW > 0f && lineW + pw > maxW) {
                    // 写死高度的盒子：这一行已经超出可用高度 → 不再另起一行，
                    // 直接在当前行截断（见 LayoutEngine 的"文字截断"说明）
                    if (lineTop + 2f * lineH > y + owner.textBoxHeight + 0.5f) {
                        truncateLine(box, lineRuns, lineW, x, lineTop, maxW, ownerStyle, lineH);
                        return lineTop + lineH - y;
                    }
                    flushLine(box, lineRuns, lineW, x, lineTop, maxW, ownerStyle, lineH);
                    lineRuns = new ArrayList<>();
                    lineW = 0f;
                    lineTop += lineH;
                }
                LayoutBox.TextRun pieceRun = new LayoutBox.TextRun();
                pieceRun.text = piece;
                pieceRun.color = run.color;
                pieceRun.bold = run.bold;
                lineRuns.add(pieceRun);
                lineW += pw;
            }
        }
        if (!lineRuns.isEmpty() || box.lines.isEmpty()) {
            flushLine(box, lineRuns, lineW, x, lineTop, maxW, ownerStyle, lineH);
            lineTop += lineH;
        }
        return lineTop - y;
    }

    /**
     * 在当前行<b>截断</b>：把这一行的文字缩到"还能放下省略号"为止。
     *
     * <p>给写了高度的盒子兜底（标题条、信息栏、列表行……）。不截断的话，
     * 一行放不下的文字会自己折到第二行、顶破盒子并压到下面的元素上 ——
     * 主题作者只是想写个短标签，结果整个版面被顶下去。</p>
     */
    private void truncateLine(LayoutBox box, List<LayoutBox.TextRun> runs, float lineW,
                              float x, float lineTop, float maxW, Style st, float lineH) {
        float ellipsisW = canvas.textWidth(HtmlNode.ELLIPSIS, st.fontSize, false);
        float budget = Math.max(0f, maxW - ellipsisW);
        List<LayoutBox.TextRun> kept = new ArrayList<>();
        float width = 0f;
        outer:
        for (LayoutBox.TextRun r : runs) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < r.text.length(); i++) {
                float cw = canvas.textWidth(String.valueOf(r.text.charAt(i)), st.fontSize, r.bold);
                if (width + cw > budget) {
                    if (sb.length() > 0) {
                        LayoutBox.TextRun part = new LayoutBox.TextRun();
                        part.text = sb.toString();
                        part.color = r.color;
                        part.bold = r.bold;
                        kept.add(part);
                        width += canvas.textWidth(part.text, st.fontSize, r.bold);
                    }
                    break outer;
                }
                sb.append(r.text.charAt(i));
                width += cw;
            }
            LayoutBox.TextRun part = new LayoutBox.TextRun();
            part.text = sb.toString();
            part.color = r.color;
            part.bold = r.bold;
            kept.add(part);
        }
        // 去掉末尾空白，再补省略号
        if (!kept.isEmpty()) {
            LayoutBox.TextRun last = kept.get(kept.size() - 1);
            last.text = last.text.stripTrailing();
        }
        LayoutBox.TextRun ell = new LayoutBox.TextRun();
        ell.text = HtmlNode.ELLIPSIS;
        kept.add(ell);
        flushLine(box, kept, width, x, lineTop, maxW, st, lineH);
    }

    private void flushLine(LayoutBox box, List<LayoutBox.TextRun> runs, float lineW,
                           float x, float lineTop, float maxW, Style st, float lineH) {
        LayoutBox.TextLine line = new LayoutBox.TextLine();
        line.y = lineTop;
        line.height = lineH;
        line.width = lineW;
        float cursor = switch (st.textAlign) {
            case "center" -> x + Math.max(0f, (maxW - lineW) / 2f);
            case "right" -> x + Math.max(0f, maxW - lineW);
            default -> x;
        };
        for (LayoutBox.TextRun r : runs) {
            r.x = cursor;
            cursor += canvas.textWidth(r.text, st.fontSize, r.bold);
            line.runs.add(r);
        }
        box.lines.add(line);
    }

    /** 折行切分：有空格按词切；没有空格（中文）按单字切 */
    private static List<String> splitForWrap(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        if (text.indexOf(' ') >= 0) {
            String[] words = text.split("(?<= )");
            for (String w : words) {
                if (!w.isEmpty()) {
                    out.add(w);
                }
            }
            return out;
        }
        for (int i = 0; i < text.length(); i++) {
            out.add(String.valueOf(text.charAt(i)));
        }
        return out;
    }

    /** 把行内子树摊平成片段（span/b/i 的样式只影响颜色与粗体） */
    private void collectRuns(List<HtmlNode> nodes, Style parentStyle, List<LayoutBox.TextRun> out) {
        for (HtmlNode n : nodes) {
            if (n.isText()) {
                if (n.text.isEmpty()) {
                    continue;
                }
                LayoutBox.TextRun r = new LayoutBox.TextRun();
                r.text = n.text;
                r.color = parentStyle.color != null ? parentStyle.color : 0xFFE8F4FF;
                out.add(r);
                continue;
            }
            if (n.dataHidden || (n.style != null && !n.style.visible())) {
                continue;
            }
            if ("br".equals(n.tag)) {
                LayoutBox.TextRun r = new LayoutBox.TextRun();
                r.text = "\n";
                out.add(r);
                continue;
            }
            if ("img".equals(n.tag)) {
                continue;                       // 图片当块级元素处理（v1 不支持行内图文混排）
            }
            Style own = n.style != null ? n.style : parentStyle;
            collectRuns(n.children, own, out);
        }
    }

    // ---------------------------------------------------------------- 弹性布局

    private float layoutFlex(List<HtmlNode> items, HtmlNode node,
                             float cx, float cy, float cw, float ch, LayoutBox containing) {
        Style st = node.style;
        boolean column = st.isColumn();
        float gap = st.gap;
        float total = 0f;

        // 先算每个子项的主轴尺寸（宽度已经是边框盒，不要再加 padding）
        List<Float> mains = new ArrayList<>(items.size());
        float sumMain = 0f;
        for (HtmlNode c : items) {
            float m;
            if (column) {
                m = c.style.height.isAuto() ? Math.max(1f, measureHeight(c, cw)) : c.style.height.resolve(ch);
            } else {
                m = c.style.width.isAuto()
                        ? Math.max(1f, intrinsicWidth(c, cw))
                        : Math.min(cw, c.style.width.resolve(cw));
            }
            mains.add(m);
            sumMain += m;
        }
        sumMain += gap * Math.max(0, items.size() - 1);

        // justify-content：主轴起点
        float mainSize = column ? ch : cw;
        float free = Math.max(0f, mainSize - sumMain);
        float cursor;
        float extraGap = 0f;
        switch (st.justifyContent) {
            case "center" -> cursor = (column ? cy : cx) + free / 2f;
            case "flex-end", "end" -> cursor = (column ? cy : cx) + free;
            case "space-between" -> {
                cursor = column ? cy : cx;
                if (items.size() > 1) {
                    extraGap = free / (items.size() - 1);
                }
            }
            default -> cursor = column ? cy : cx;
        }

        float crossMax = 0f;
        for (int i = 0; i < items.size(); i++) {
            HtmlNode c = items.get(i);
            float cross = column ? cw : ch;
            if (column) {
                float used = layoutChildInFlexRow(c, cx, cursor, cw, cross, containing, st,
                        "column", mains.get(i));
                cursor += mains.get(i) + gap + extraGap;
                crossMax = Math.max(crossMax, used);
            } else {
                float used = layoutChildInFlexRow(c, cursor, cy, cw, cross, containing, st,
                        "row", mains.get(i));
                cursor += mains.get(i) + gap + extraGap;
                crossMax = Math.max(crossMax, used);
            }
        }
        return column ? (cursor - cy) : crossMax;
    }

    /**
     * flex 子项：row 时宽度按主轴给定、高度撑满或按内容；column 时反过来。
     * {@code align-items} 只管交叉轴的对齐（stretch 时撑满）。
     */
    private float layoutChildInFlexRow(HtmlNode child, float x, float y,
                                        float availW, float availH,
                                        LayoutBox containing, Style flexStyle, String dir,
                                        float forcedMain) {
        Style cs = child.style;
        float used;
        if ("row".equals(dir)) {
            // 主轴 = 横向：宽度已经由 flex 算好（mains[i]），直接强制使用 ——
            // 不能再把百分比对着已收窄的宽度算第二次（那会把 50% 变成 25%）。
            float forced = Math.max(1f, forcedMain);
            used = layoutChild(child, x, y, availW, availH, containing, forced);
            if ("stretch".equals(flexStyle.alignItems) && child.box != null
                    && child.box.visible && cs.height.isAuto()) {
                child.box.h = Math.max(child.box.h, availH);
            }
        } else {
            used = layoutChild(child, x, y, availW, Math.max(1f, availH), containing);
        }
        return used;
    }

    // ---------------------------------------------------------------- 测量

    /** 内容需要多高（宽度已知，第二次近似：先按可用宽度估，再摆一遍量） */
    private float measureHeight(HtmlNode node, float outerW) {
        Style st = node.style;
        float innerW = Math.max(1f, outerW - 2f * st.borderWidth - st.padLeft - st.padRight);
        float h = 0f;

        List<HtmlNode> inline = new ArrayList<>();
        List<HtmlNode> blocks = new ArrayList<>();
        splitChildren(node, inline, blocks);

        if (!inline.isEmpty()) {
            h += textFlowHeight(inline, st, innerW);
        }
        if (!blocks.isEmpty()) {
            if (st.isFlex()) {
                float maxMain = 0f;
                for (HtmlNode c : blocks) {
                    float m = st.isColumn()
                            ? (c.style.height.isAuto() ? measureHeight(c, innerW) : c.style.height.value)
                            : (c.style.height.isAuto() ? measureHeight(c, intrinsicWidth(c, innerW))
                                    : c.style.height.value);
                    maxMain = st.isColumn() ? maxMain + m : Math.max(maxMain, m);
                }
                if (!st.isColumn()) {
                    for (HtmlNode c : blocks) {
                        maxMain = Math.max(maxMain, measureHeight(c, intrinsicWidth(c, innerW)));
                    }
                }
                h += maxMain + st.gap * Math.max(0, blocks.size() - 1);
            } else {
                for (HtmlNode c : blocks) {
                    h += measureHeight(c, innerW) + c.style.marTop + c.style.marBottom;
                }
            }
        }
        return h + 2f * st.borderWidth + st.padTop + st.padBottom;
    }

    /** 文字流会占多少行 */
    private float textFlowHeight(List<HtmlNode> inline, Style st, float maxW) {
        List<LayoutBox.TextRun> flat = new ArrayList<>();
        collectRuns(inline, st, flat);
        float size = st.fontSize;
        float lineH = st.effectiveLineHeight();
        int lines = 1;
        float lineW = 0f;
        for (LayoutBox.TextRun run : flat) {
            if ("\n".equals(run.text)) {
                lines++;
                lineW = 0f;
                continue;
            }
            for (String piece : splitForWrap(run.text)) {
                float pw = canvas.textWidth(piece, size, run.bold);
                if (lineW > 0f && lineW + pw > maxW) {
                    lines++;
                    lineW = 0f;
                }
                lineW += pw;
            }
        }
        return lines * lineH;
    }

    /** 内容"自然想多宽"（flex 子项没写宽度时用） */
    private float intrinsicWidth(HtmlNode node, float limit) {
        Style st = node.style;
        if (!st.width.isAuto()) {
            return Math.min(limit, st.width.resolve(limit));
        }
        List<HtmlNode> inline = new ArrayList<>();
        List<HtmlNode> blocks = new ArrayList<>();
        splitChildren(node, inline, blocks);

        float w = 0f;
        if (!inline.isEmpty()) {
            List<LayoutBox.TextRun> flat = new ArrayList<>();
            collectRuns(inline, st, flat);
            float line = 0f;
            for (LayoutBox.TextRun r : flat) {
                if ("\n".equals(r.text)) {
                    w = Math.max(w, line);
                    line = 0f;
                    continue;
                }
                line += canvas.textWidth(r.text, st.fontSize, r.bold);
            }
            w = Math.max(w, line);
        }
        if (st.isFlex() && !st.isColumn()) {
            float sum = 0f;
            for (HtmlNode c : blocks) {
                sum += intrinsicWidth(c, limit) + 2f * c.style.borderWidth
                        + c.style.padLeft + c.style.padRight;
            }
            sum += st.gap * Math.max(0, blocks.size() - 1);
            w = Math.max(w, sum);
        } else {
            for (HtmlNode c : blocks) {
                w = Math.max(w, intrinsicWidth(c, limit) + 2f * c.style.borderWidth
                        + c.style.padLeft + c.style.padRight);
            }
        }
        return Math.min(limit, w + 2f * st.borderWidth + st.padLeft + st.padRight);
    }
}
