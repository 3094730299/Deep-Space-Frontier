package com.deepspacedeployer.ui.theme;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>极简 CSS 解析器 + 层叠</b>。
 *
 * <p>支持的选择器：{@code tag}、{@code .class}、{@code #id}、以及它们的<b>后代组合</b>
 * （{@code .a .b}），多个选择器用逗号分隔。<b>不支持</b>子选择器 {@code >}、
 * 伪类 {@code :hover}、属性选择器 —— 需要的话再加。</p>
 *
 * <p>层叠顺序：元素自身的内联 {@code style="..."} &gt; 选择器更具体的规则 &gt; 靠后的规则。
 * 具体度按 {@code id×100 + class×10 + tag} 算。</p>
 *
 * <p>⚠️ 表里没有的属性<b>静默忽略</b>（不报错）—— 这样玩家抄一段网上的 CSS 进来，
 * 界面也不会崩，只是那几条不生效。</p>
 */
public final class CssParser {

    /** 一条规则 */
    public static final class Rule {
        public final List<Selector> selectors = new ArrayList<>();
        public final Map<String, String> decls = new LinkedHashMap<>();
        /** 源码顺序（越大越靠后，层叠时优先） */
        public int order;
    }

    /** 一个复合选择器（可能带祖先链，如 ".win .row"） */
    public static final class Selector {
        /** 从祖先到自身，每段是 tag#id.class 的组合 */
        public final List<String[]> parts = new ArrayList<>();   // [tag, id, class1 class2 …]
        public int specificity;

        public boolean matches(HtmlNode node) {
            if (parts.isEmpty()) {
                return false;
            }
            if (!compoundMatches(parts.get(parts.size() - 1), node)) {
                return false;
            }
            // 祖先链：从上层往下找，顺序必须对
            int p = parts.size() - 2;
            HtmlNode cur = node.parent;
            while (p >= 0) {
                boolean found = false;
                while (cur != null) {
                    if (compoundMatches(parts.get(p), cur)) {
                        found = true;
                        cur = cur.parent;
                        break;
                    }
                    cur = cur.parent;
                }
                if (!found) {
                    return false;
                }
                p--;
            }
            return true;
        }

        private static boolean compoundMatches(String[] c, HtmlNode n) {
            if (n == null || !n.isElement()) {
                return false;
            }
            String tag = c[0];
            String id = c[1];
            String classes = c[2];
            if (!tag.isEmpty() && !"*".equals(tag) && !tag.equals(n.tag)) {
                return false;
            }
            if (!id.isEmpty() && !id.equals(n.id)) {
                return false;
            }
            if (!classes.isEmpty()) {
                for (String cls : classes.split(" ")) {
                    if (!cls.isEmpty() && !n.hasClass(cls)) {
                        return false;
                    }
                }
            }
            return true;
        }
    }

    private CssParser() {
    }

    /** 解析整份 CSS（会自己跳过注释） */
    public static List<Rule> parse(String css, int startOrder) {
        List<Rule> out = new ArrayList<>();
        if (css == null || css.isBlank()) {
            return out;
        }
        String s = stripComments(css);
        int order = startOrder;
        int i = 0;
        while (i < s.length()) {
            int open = s.indexOf('{', i);
            if (open < 0) {
                break;
            }
            int close = s.indexOf('}', open);
            if (close < 0) {
                break;
            }
            String selectorText = s.substring(i, open).trim();
            String body = s.substring(open + 1, close);
            i = close + 1;
            if (selectorText.isEmpty()) {
                continue;
            }
            Rule rule = new Rule();
            rule.order = order++;
            for (String one : selectorText.split(",")) {
                Selector sel = parseSelector(one.trim());
                if (sel != null) {
                    rule.selectors.add(sel);
                }
            }
            if (rule.selectors.isEmpty()) {
                continue;
            }
            for (String decl : body.split(";")) {
                int colon = decl.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String prop = decl.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String val = decl.substring(colon + 1).trim();
                if (!prop.isEmpty() && !val.isEmpty()) {
                    rule.decls.put(prop, val);
                }
            }
            if (!rule.decls.isEmpty()) {
                out.add(rule);
            }
        }
        return out;
    }

    private static Selector parseSelector(String text) {
        if (text.isEmpty()) {
            return null;
        }
        Selector sel = new Selector();
        // 后代组合符：空白。刻意不支持 ">"（遇到了就当普通空白处理）
        for (String part : text.split("\\s+")) {
            if (part.isEmpty() || ">".equals(part)) {
                continue;
            }
            String tag = "";
            String id = "";
            StringBuilder classes = new StringBuilder();
            int i = 0;
            if (part.charAt(0) != '.' && part.charAt(0) != '#') {
                int j = 0;
                while (j < part.length() && part.charAt(j) != '.' && part.charAt(j) != '#') {
                    j++;
                }
                tag = part.substring(0, j).toLowerCase(Locale.ROOT);
                i = j;
            }
            while (i < part.length()) {
                char c = part.charAt(i);
                int j = i + 1;
                while (j < part.length() && part.charAt(j) != '.' && part.charAt(j) != '#') {
                    j++;
                }
                String name = part.substring(i + 1, j);
                if (c == '#') {
                    id = name;
                } else if (c == '.') {
                    if (classes.length() > 0) {
                        classes.append(' ');
                    }
                    classes.append(name);
                }
                i = j;
            }
            sel.parts.add(new String[]{tag, id, classes.toString()});
        }
        if (sel.parts.isEmpty()) {
            return null;
        }
        int spec = 0;
        for (String[] p : sel.parts) {
            spec += (p[1].isEmpty() ? 0 : 100) + (p[2].isEmpty() ? 0 : 10 * p[2].split(" ").length)
                    + (p[0].isEmpty() || "*".equals(p[0]) ? 0 : 1);
        }
        sel.specificity = spec;
        return sel;
    }

    private static String stripComments(String css) {
        StringBuilder out = new StringBuilder(css.length());
        int i = 0;
        while (i < css.length()) {
            int c = css.indexOf("/*", i);
            if (c < 0) {
                out.append(css, i, css.length());
                break;
            }
            out.append(css, i, c);
            int e = css.indexOf("*/", c + 2);
            i = e < 0 ? css.length() : e + 2;
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- 属性落地

    /**
     * 把一条 CSS 声明写进样式对象。
     *
     * <p><b>这就是"支持的属性清单"</b>：不在 switch 里的属性会被忽略。</p>
     */
    public static void apply(Style st, String prop, String val) {
        String v = val.trim();
        switch (prop) {
            case "color" -> st.color = parseColor(v, st.color);
            case "background", "background-color" -> st.background = parseColor(v, st.background);
            case "background-image" -> st.backgroundImage = parseUrl(v);
            case "background-size" -> st.backgroundSize = v.toLowerCase(Locale.ROOT);
            case "width" -> st.width = Dim.parse(v, st.width);
            case "height" -> st.height = Dim.parse(v, st.height);
            case "padding" -> {
                float[] p = four(v, 0f);
                st.padTop = p[0];
                st.padRight = p[1];
                st.padBottom = p[2];
                st.padLeft = p[3];
            }
            case "padding-top" -> st.padTop = length(v, st.padTop);
            case "padding-right" -> st.padRight = length(v, st.padRight);
            case "padding-bottom" -> st.padBottom = length(v, st.padBottom);
            case "padding-left" -> st.padLeft = length(v, st.padLeft);
            case "margin" -> {
                float[] m = four(v, 0f);
                st.marTop = m[0];
                st.marRight = m[1];
                st.marBottom = m[2];
                st.marLeft = m[3];
            }
            case "margin-top" -> st.marTop = length(v, st.marTop);
            case "margin-right" -> st.marRight = length(v, st.marRight);
            case "margin-bottom" -> st.marBottom = length(v, st.marBottom);
            case "margin-left" -> st.marLeft = length(v, st.marLeft);
            case "border" -> {
                st.borderWidth = length(v, st.borderWidth);
                Integer c = parseColor(lastToken(v), null);
                if (c != null) {
                    st.borderColor = c;
                }
            }
            case "border-width" -> st.borderWidth = length(v, st.borderWidth);
            case "border-color" -> {
                Integer c = parseColor(v, null);
                if (c != null) {
                    st.borderColor = c;
                }
            }
            case "border-radius" -> st.radius = length(v, st.radius);
            case "font-size" -> {
                st.fontSize = length(v, st.fontSize);
                st.fontSizeSelf = st.fontSize;      // 标记"作者写过"，不再继承
            }
            case "text-align" -> {
                st.textAlign = v.toLowerCase(Locale.ROOT);
                st.textAlignSelf = true;            // 标记"作者写过"，不再继承
            }
            case "line-height" -> st.lineHeight = length(v, st.lineHeight);
            case "display" -> {
                String d = v.toLowerCase(Locale.ROOT);
                st.display = switch (d) {
                    case "flex", "none", "block" -> d;
                    case "inline", "inline-block" -> "block";   // 简化：当块处理
                    default -> st.display;
                };
            }
            case "flex-direction" -> st.flexDirection =
                    "column".equalsIgnoreCase(v) ? "column" : "row";
            case "align-items" -> st.alignItems = v.toLowerCase(Locale.ROOT);
            case "justify-content" -> st.justifyContent = v.toLowerCase(Locale.ROOT);
            case "gap" -> st.gap = length(v, st.gap);
            case "position" -> {
                String p = v.toLowerCase(Locale.ROOT);
                st.position = ("absolute".equals(p) || "relative".equals(p)) ? p : "static";
            }
            case "top" -> st.top = num(v, st.top);
            case "right" -> st.right = num(v, st.right);
            case "bottom" -> st.bottom = num(v, st.bottom);
            case "left" -> st.left = num(v, st.left);
            case "opacity" -> {
                try {
                    st.opacity = Math.max(0f, Math.min(1f, Float.parseFloat(v)));
                } catch (NumberFormatException ignored) {
                    // 保持原值
                }
            }
            case "overflow", "overflow-y" -> {
                String o = v.toLowerCase(Locale.ROOT);
                st.overflow = ("hidden".equals(o) || "scroll".equals(o) || "auto".equals(o))
                        ? ("auto".equals(o) ? "scroll" : o) : "visible";
            }
            case "image-rendering" -> st.pixelated = v.toLowerCase(Locale.ROOT).contains("pixel");
            default -> {
                // 不支持的属性：静默忽略（这是承诺过的行为）
            }
        }
    }

    /** 解析内联 style="a:b; c:d" */
    public static void applyInline(Style st, String inline) {
        if (inline == null || inline.isBlank()) {
            return;
        }
        for (String decl : inline.split(";")) {
            int colon = decl.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            apply(st, decl.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                    decl.substring(colon + 1).trim());
        }
    }

    // ---------------------------------------------------------------- 小工具

    private static float length(String v, float fallback) {
        Dim d = Dim.parse(v, null);
        return d == null || d.isAuto() ? fallback : d.value;
    }

    private static Float num(String v, Float fallback) {
        try {
            String s = v.trim().toLowerCase(Locale.ROOT);
            if (s.endsWith("px")) {
                s = s.substring(0, s.length() - 2).trim();
            }
            return Float.parseFloat(s);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** CSS 的 1/2/3/4 值简写 → 上右下左 */
    private static float[] four(String v, float fallback) {
        String[] parts = v.trim().split("\\s+");
        float[] vals = new float[4];
        for (int i = 0; i < parts.length && i < 4; i++) {
            vals[i] = length(parts[i], fallback);
        }
        return switch (parts.length) {
            case 1 -> new float[]{vals[0], vals[0], vals[0], vals[0]};
            case 2 -> new float[]{vals[0], vals[1], vals[0], vals[1]};
            case 3 -> new float[]{vals[0], vals[1], vals[2], vals[1]};
            default -> new float[]{vals[0], vals[1], vals[2], vals[3]};
        };
    }

    private static String lastToken(String v) {
        String[] parts = v.trim().split("\\s+");
        return parts.length == 0 ? v : parts[parts.length - 1];
    }

    /** url(a.png) / url('a.png') / "a.png" → a.png */
    private static String parseUrl(String v) {
        String s = v.trim();
        int lp = s.indexOf('(');
        if (lp >= 0) {
            int rp = s.lastIndexOf(')');
            s = rp > lp ? s.substring(lp + 1, rp) : s.substring(lp + 1);
        }
        s = s.trim();
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            s = s.substring(1, s.length() - 1);
        }
        return s.isBlank() || "none".equalsIgnoreCase(s) ? null : s;
    }

    /** 颜色：支持 #RGB / #RRGGBB / #AARRGGBB / rgb() / rgba() / transparent / 少量常用色名 */
    public static Integer parseColor(String v, Integer fallback) {
        if (v == null) {
            return fallback;
        }
        String s = v.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return fallback;
        }
        if ("transparent".equals(s) || "none".equals(s)) {
            return null;
        }
        if (s.startsWith("#")) {
            String hex = s.substring(1);
            try {
                return switch (hex.length()) {
                    case 3 -> {
                        int r = Integer.parseInt(hex.substring(0, 1), 16) * 17;
                        int g = Integer.parseInt(hex.substring(1, 2), 16) * 17;
                        int b = Integer.parseInt(hex.substring(2, 3), 16) * 17;
                        yield 0xFF000000 | (r << 16) | (g << 8) | b;
                    }
                    case 6 -> 0xFF000000 | Integer.parseInt(hex, 16);
                    case 8 -> (int) Long.parseLong(hex, 16);          // AARRGGBB
                    default -> fallback;
                };
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        if (s.startsWith("rgb")) {
            int lp = s.indexOf('(');
            int rp = s.lastIndexOf(')');
            if (lp < 0 || rp < 0) {
                return fallback;
            }
            String[] parts = s.substring(lp + 1, rp).split(",");
            if (parts.length < 3) {
                return fallback;
            }
            try {
                int r = clamp(Integer.parseInt(parts[0].trim()));
                int g = clamp(Integer.parseInt(parts[1].trim()));
                int b = clamp(Integer.parseInt(parts[2].trim()));
                int a = parts.length >= 4
                        ? clamp((int) (Float.parseFloat(parts[3].trim()) * 255f)) : 255;
                return (a << 24) | (r << 16) | (g << 8) | b;
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        Integer named = switch (s) {
            case "black" -> 0xFF000000;
            case "white" -> 0xFFFFFFFF;
            case "red" -> 0xFFFF5555;
            case "green" -> 0xFF55FF55;
            case "blue" -> 0xFF5555FF;
            case "yellow" -> 0xFFFFFF55;
            case "orange" -> 0xFFFFA500;
            case "gray", "grey" -> 0xFF808080;
            case "silver" -> 0xFFC0C0C0;
            case "cyan", "aqua" -> 0xFF55FFFF;
            case "magenta", "fuchsia" -> 0xFFFF55FF;
            default -> null;
        };
        return named != null ? named : fallback;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
