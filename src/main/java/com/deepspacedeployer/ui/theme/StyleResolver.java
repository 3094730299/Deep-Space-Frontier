package com.deepspacedeployer.ui.theme;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>把 CSS 规则套到 DOM 上</b>，算出每个元素最终生效的 {@link Style}。
 *
 * <p>顺序（和浏览器一致）：继承 → 选择器规则（按具体度、再按源码顺序）→ 内联 style。</p>
 */
public final class StyleResolver {

    private final List<CssParser.Rule> rules;

    public StyleResolver(List<CssParser.Rule> rules) {
        this.rules = rules == null ? List.of() : rules;
    }

    /** 从根开始算整棵树 */
    public void resolve(HtmlNode root) {
        resolveNode(root, null);
    }

    private void resolveNode(HtmlNode node, Style parentStyle) {
        if (node.isText()) {
            return;                       // 文本节点不单独持有样式，跟随父元素
        }
        Style st = new Style();
        if (parentStyle != null) {
            st.inheritFrom(parentStyle);
        }

        // 命中的规则：具体度高的后应用；相同具体度按源码顺序
        List<CssParser.Rule> hits = new ArrayList<>();
        for (CssParser.Rule r : rules) {
            for (CssParser.Selector sel : r.selectors) {
                if (sel.matches(node)) {
                    hits.add(r);
                    break;
                }
            }
        }
        if (!hits.isEmpty()) {
            hits.sort((a, b) -> {
                int sa = bestSpecificity(a, node);
                int sb = bestSpecificity(b, node);
                return sa != sb ? Integer.compare(sa, sb) : Integer.compare(a.order, b.order);
            });
            for (CssParser.Rule r : hits) {
                for (var e : r.decls.entrySet()) {
                    CssParser.apply(st, e.getKey(), e.getValue());
                }
            }
        }

        // 内联样式永远最后（优先级最高）
        CssParser.applyInline(st, node.attr("style"));

        node.style = st;
        for (HtmlNode child : node.children) {
            if (child.isElement()) {
                resolveNode(child, st);
            }
        }
    }

    private static int bestSpecificity(CssParser.Rule r, HtmlNode node) {
        int best = 0;
        for (CssParser.Selector sel : r.selectors) {
            if (sel.matches(node)) {
                best = Math.max(best, sel.specificity);
            }
        }
        return best;
    }
}
