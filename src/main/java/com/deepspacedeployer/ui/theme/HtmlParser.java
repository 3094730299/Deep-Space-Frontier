package com.deepspacedeployer.ui.theme;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * <b>极简 HTML 解析器</b>：够用就好，不做完整规范。
 *
 * <p>支持：</p>
 * <ul>
 *   <li>元素、属性（双引号 / 单引号 / 无引号）、注释、DOCTYPE</li>
 *   <li>自闭合（{@code <br/>}）与常见空元素（{@code br img hr}）</li>
 *   <li>实体：{@code &amp;amp; &amp;lt; &amp;gt; &amp;quot; &amp;nbsp;} 与
 *      数字形式 {@code &#65; &#x41;}</li>
 *   <li>{@code <style>} 里的 CSS 会被收集起来交给 {@link CssParser}</li>
 * </ul>
 *
 * <p>刻意不支持（也不会实现）：</p>
 * <ul>
 *   <li><b>{@code <script>}：整段丢弃</b> —— 主题里不允许有任何脚本</li>
 *   <li>HTML5 的隐式闭合规则：标签要基本配对；配错时按"往上找最近的同名开始标签"补救</li>
 * </ul>
 */
public final class HtmlParser {

    /** 空元素：不需要闭合标签 */
    private static final List<String> VOID_TAGS =
            List.of("br", "img", "hr", "meta", "link", "input", "area", "base", "col");

    /** 解析结果 */
    public static final class Result {
        /** 文档根（一个合成的 #document 节点，children 就是顶层元素） */
        public HtmlNode root = HtmlNode.element("#document");
        /** 所有 {@code <style>} 里的 CSS 文本，按出现顺序 */
        public final List<String> styleSheets = new java.util.ArrayList<>();
        /** {@code <link rel="stylesheet" href="...">} 引用的路径（相对文档目录） */
        public final List<String> linkedSheets = new java.util.ArrayList<>();
        /** 文档标题（{@code <title>}），没有就空串 */
        public String title = "";
    }

    private final String src;
    private int pos;

    private HtmlParser(String src) {
        this.src = src == null ? "" : src;
    }

    /**
     * @param html    主题 HTML 原文
     * @param docDir  该文件所在目录（相对 theme 根，""=根），会写进每个节点的 docDir，
     *                这样相对路径（./a.png）才能解析对
     */
    public static Result parse(String html, String docDir) {
        HtmlParser p = new HtmlParser(html);
        Result r = p.run();
        r.root.docDir = docDir == null ? "" : docDir;
        r.root.walk(n -> n.docDir = r.root.docDir);
        return r;
    }

    private Result run() {
        Result result = new Result();
        Deque<HtmlNode> stack = new ArrayDeque<>();
        stack.push(result.root);
        StringBuilder text = new StringBuilder();

        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c != '<') {
                text.append(c);
                pos++;
                continue;
            }
            // 到标签了：先把攒下的文本挂上去
            flushText(stack.peek(), text);

            if (startsWith("<!--")) {
                int end = src.indexOf("-->", pos + 4);
                pos = end < 0 ? src.length() : end + 3;
                continue;
            }
            if (startsWith("<!")) {                       // DOCTYPE 之类
                int end = src.indexOf('>', pos);
                pos = end < 0 ? src.length() : end + 1;
                continue;
            }
            if (startsWith("</")) {                       // 结束标签
                int end = src.indexOf('>', pos);
                String name = src.substring(pos + 2, end < 0 ? src.length() : end)
                        .trim().toLowerCase(Locale.ROOT);
                pos = end < 0 ? src.length() : end + 1;
                closeTag(stack, name);
                continue;
            }

            // 开始标签
            int end = findTagEnd(pos);
            String inside = src.substring(pos + 1, end);
            pos = end + 1;

            boolean selfClose = inside.endsWith("/");
            if (selfClose) {
                inside = inside.substring(0, inside.length() - 1);
            }
            String tag = readTagName(inside);
            if (tag.isEmpty()) {
                continue;
            }

            if ("script".equals(tag)) {                   // ★ 脚本整段丢弃
                skipElement("script");
                continue;
            }
            if ("style".equals(tag)) {
                String css = readRawUntil("style");
                result.styleSheets.add(css);
                continue;
            }
            if ("title".equals(tag)) {
                result.title = readRawUntil("title").strip();
                continue;
            }

            HtmlNode node = HtmlNode.element(tag);
            parseAttrs(inside, node);
            stack.peek().add(node);

            if ("link".equals(tag)) {
                String rel = node.attr("rel", "");
                String href = node.attr("href");
                if (href != null && rel.toLowerCase(Locale.ROOT).contains("stylesheet")) {
                    result.linkedSheets.add(href);
                }
            }

            if (!selfClose && !VOID_TAGS.contains(tag)) {
                stack.push(node);
            }
        }
        flushText(stack.peek(), text);
        return result;
    }

    // ---------------------------------------------------------------- 工具

    private void flushText(HtmlNode parent, StringBuilder sb) {
        if (sb.length() == 0) {
            return;
        }
        String raw = sb.toString();
        sb.setLength(0);
        String decoded = decodeEntities(raw);
        // 纯空白（换行/缩进）不进 DOM：它只影响源码可读性，不该在界面里占位
        if (decoded.isBlank()) {
            return;
        }
        parent.add(HtmlNode.text(decoded));
    }

    /** 关闭到最近的同名开始标签（多关的忽略；找不到就忽略） */
    private void closeTag(Deque<HtmlNode> stack, String name) {
        if (name.isEmpty()) {
            return;
        }
        for (HtmlNode n : stack) {
            if (n.isElement() && name.equals(n.tag)) {
                while (!stack.isEmpty()) {
                    HtmlNode top = stack.pop();
                    if (top == n) {
                        return;
                    }
                }
                return;
            }
        }
        // 没有对应的开始标签 → 忽略这个结束标签
    }

    private int findTagEnd(int from) {
        boolean inQuote = false;
        char quote = 0;
        for (int i = from + 1; i < src.length(); i++) {
            char c = src.charAt(i);
            if (inQuote) {
                if (c == quote) {
                    inQuote = false;
                }
            } else if (c == '"' || c == '\'') {
                inQuote = true;
                quote = c;
            } else if (c == '>') {
                return i;
            }
        }
        return src.length();
    }

    private static String readTagName(String inside) {
        int i = 0;
        while (i < inside.length() && !Character.isWhitespace(inside.charAt(i))) {
            i++;
        }
        return inside.substring(0, i).trim().toLowerCase(Locale.ROOT);
    }

    /** 解析属性：id / class 单独存，其余进 attrs；属性名一律小写 */
    private static void parseAttrs(String inside, HtmlNode node) {
        int i = 0;
        int n = inside.length();
        while (i < n && !Character.isWhitespace(inside.charAt(i))) {
            i++;                                        // 跳过标签名
        }
        while (i < n) {
            while (i < n && Character.isWhitespace(inside.charAt(i))) {
                i++;
            }
            if (i >= n) {
                break;
            }
            int nameStart = i;
            while (i < n && !Character.isWhitespace(inside.charAt(i))
                    && inside.charAt(i) != '=') {
                i++;
            }
            String name = inside.substring(nameStart, i).trim().toLowerCase(Locale.ROOT);
            String value = "";
            while (i < n && Character.isWhitespace(inside.charAt(i))) {
                i++;
            }
            if (i < n && inside.charAt(i) == '=') {
                i++;
                while (i < n && Character.isWhitespace(inside.charAt(i))) {
                    i++;
                }
                if (i < n && (inside.charAt(i) == '"' || inside.charAt(i) == '\'')) {
                    char q = inside.charAt(i++);
                    int vStart = i;
                    while (i < n && inside.charAt(i) != q) {
                        i++;
                    }
                    value = inside.substring(vStart, Math.min(i, n));
                    if (i < n) {
                        i++;
                    }
                } else {
                    int vStart = i;
                    while (i < n && !Character.isWhitespace(inside.charAt(i))) {
                        i++;
                    }
                    value = inside.substring(vStart, i);
                }
            }
            value = decodeEntities(value);
            if (name.isEmpty()) {
                continue;
            }
            switch (name) {
                case "id" -> node.id = value;
                case "class" -> {
                    for (String c : value.split("\\s+")) {
                        if (!c.isBlank()) {
                            node.classes.add(c);
                        }
                    }
                }
                default -> node.attrs.put(name, value);
            }
        }
    }

    /** 读 {@code <style>} / {@code <title>} 的原始内容（不解析标签） */
    private String readRawUntil(String tag) {
        String close = "</" + tag;
        int end = src.toLowerCase(Locale.ROOT).indexOf(close, pos);
        if (end < 0) {
            String rest = src.substring(pos);
            pos = src.length();
            return rest;
        }
        String content = src.substring(pos, end);
        int gt = src.indexOf('>', end);
        pos = gt < 0 ? src.length() : gt + 1;
        return content;
    }

    /** 跳过整个元素（含内容），用于 {@code <script>} */
    private void skipElement(String tag) {
        String close = "</" + tag;
        int end = src.toLowerCase(Locale.ROOT).indexOf(close, pos);
        if (end < 0) {
            pos = src.length();
            return;
        }
        int gt = src.indexOf('>', end);
        pos = gt < 0 ? src.length() : gt + 1;
    }

    private boolean startsWith(String s) {
        return src.startsWith(s, pos);
    }

    /** 常见 HTML 实体解码 */
    static String decodeEntities(String s) {
        if (s.indexOf('&') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != '&') {
                out.append(c);
                i++;
                continue;
            }
            int semi = s.indexOf(';', i);
            if (semi < 0 || semi - i > 10) {
                out.append(c);
                i++;
                continue;
            }
            String ent = s.substring(i + 1, semi);
            String rep = switch (ent.toLowerCase(Locale.ROOT)) {
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "quot" -> "\"";
                case "apos", "#39" -> "'";
                case "nbsp" -> "\u00A0";
                default -> null;
            };
            if (rep == null && ent.startsWith("#")) {
                try {
                    int code = ent.startsWith("#x") || ent.startsWith("#X")
                            ? Integer.parseInt(ent.substring(2), 16)
                            : Integer.parseInt(ent.substring(1));
                    rep = String.valueOf((char) code);
                } catch (RuntimeException ignored) {
                    rep = null;
                }
            }
            if (rep == null) {
                out.append(c);
                i++;
            } else {
                out.append(rep);
                i = semi + 1;
            }
        }
        return out.toString();
    }
}
