package com.deepspacedeployer.ui.theme;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>主题 HTML 的节点</b>（元素或文本）。
 *
 * <p>刻意做成"哑数据结构 + 几个小工具方法"：解析器只负责填，布局只负责读，
 * 这样两边都好改、也好写离线预览。</p>
 *
 * <p>⚠️ 这里<b>没有脚本</b>：{@code <script>} 在解析阶段就被整段丢掉。
 * 玩家自定义的主题只能"描述长什么样"，不能执行任何东西。</p>
 */
public final class HtmlNode {

    public enum Kind { ELEMENT, TEXT }

    public Kind kind;
    /** 元素：小写标签名（未知标签会被当成 div） */
    public String tag = "";
    /** 文本：原文（可能含 {{占位符}}，渲染时才替换） */
    public String text = "";
    public String id = "";
    public final List<String> classes = new ArrayList<>();
    /** 其余属性（含 data-*、style、src、href、width/height 等） */
    public final Map<String, String> attrs = new LinkedHashMap<>();

    public HtmlNode parent;
    public final List<HtmlNode> children = new ArrayList<>();

    /** 引用它的那个文件所在目录（相对 theme 根，用于解析相对路径） */
    public String docDir = "";

    /** {@code data-if="..."} 求值结果：true = 这次不显示（等价于 display:none） */
    public boolean dataHidden;

    /** 样式（由 StyleResolver 填） */
    public Style style;

    /** 布局结果（由 LayoutEngine 填） */
    public LayoutBox box;

    public static HtmlNode element(String tag) {
        HtmlNode n = new HtmlNode();
        n.kind = Kind.ELEMENT;
        n.tag = tag == null ? "div" : tag.toLowerCase(java.util.Locale.ROOT);
        return n;
    }

    public static HtmlNode text(String s) {
        HtmlNode n = new HtmlNode();
        n.kind = Kind.TEXT;
        n.text = s == null ? "" : s;
        return n;
    }

    public boolean isElement() {
        return kind == Kind.ELEMENT;
    }

    public boolean isText() {
        return kind == Kind.TEXT;
    }

    public void add(HtmlNode child) {
        child.parent = this;
        children.add(child);
    }

    /** 属性；不存在返回 null */
    public String attr(String name) {
        if (name == null) {
            return null;
        }
        String v = attrs.get(name.toLowerCase(java.util.Locale.ROOT));
        if (v != null) {
            return v;
        }
        // 常见别名，少写点兼容代码
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "class" -> classes.isEmpty() ? null : String.join(" ", classes);
            case "id" -> id.isEmpty() ? null : id;
            default -> null;
        };
    }

    public String attr(String name, String fallback) {
        String v = attr(name);
        return v == null ? fallback : v;
    }

    public boolean hasClass(String c) {
        return classes.contains(c);
    }

    /** data-* 开关：属性存在且不是 "false"/"0" 就算真 */
    public boolean flag(String name) {
        String v = attr(name);
        if (v == null) {
            return false;
        }
        return !("false".equalsIgnoreCase(v) || "0".equals(v) || "no".equalsIgnoreCase(v));
    }

    /** 深度优先遍历（含自己） */
    public void walk(java.util.function.Consumer<HtmlNode> visitor) {
        visitor.accept(this);
        for (HtmlNode c : children) {
            c.walk(visitor);
        }
    }

    /** 按 id 找（深度优先，第一个命中） */
    public HtmlNode byId(String wantId) {
        if (wantId == null) {
            return null;
        }
        if (wantId.equals(id)) {
            return this;
        }
        for (HtmlNode c : children) {
            HtmlNode hit = c.byId(wantId);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /** 深拷贝（列表 repeat 时用：一份模板复制成多份） */
    public HtmlNode copy() {
        HtmlNode n = new HtmlNode();
        n.kind = kind;
        n.tag = tag;
        n.text = text;
        n.id = id;
        n.classes.addAll(classes);
        n.attrs.putAll(attrs);
        n.docDir = docDir;
        n.dataHidden = dataHidden;
        n.style = style == null ? null : style.copy();   // 防御：万一在样式解析之后才克隆
        for (HtmlNode c : children) {
            n.add(c.copy());
        }
        return n;
    }

    /** 供日志/调试：一层元素摘要 */
    public String brief() {
        if (isText()) {
            String t = text.strip();
            return t.isEmpty() ? "" : "\"" + (t.length() > 18 ? t.substring(0, 18) + "…" : t) + "\"";
        }
        StringBuilder sb = new StringBuilder(tag);
        if (!id.isEmpty()) {
            sb.append('#').append(id);
        }
        for (String c : classes) {
            sb.append('.').append(c);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return brief();
    }
}
