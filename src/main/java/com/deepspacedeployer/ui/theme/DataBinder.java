package com.deepspacedeployer.ui.theme;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>数据绑定</b>：主题里不允许写 JS，所以"动起来"靠这两样：
 *
 * <ol>
 *   <li><b>占位符</b>：{@code {{余额}}} → 替换成真实文字（在折行之前替换，宽度才算得准）；</li>
 *   <li><b>显示开关</b>：{@code data-if="online"} / {@code data-if="!online"}
 *       → 不成立时这个元素整个不参与布局（等价于 display:none）。</li>
 * </ol>
 *
 * <p>按钮靠 {@code data-action="sell_now"} 这类属性被界面代码识别（渲染层不管，
 * 交互层 {@code ThemeScreen} 负责命中与派发）。</p>
 *
 * <p>取值真假规则：{@code null}、空串、{@code 0}、{@code false}、{@code no} 都算假。</p>
 */
public final class DataBinder {

    /** 数据来源：给一个 key 返回当前值（返回 null 表示没有） */
    public interface Data {
        String get(String key);

        /** 空数据源，离线预览时用 */
        Data EMPTY = key -> null;
    }

    private DataBinder() {
    }

    /** 就地绑定：先展开列表，再替换占位符、求值 data-if */
    public static void bind(HtmlNode root, Data data) {
        if (root == null) {
            return;
        }
        Data src = data == null ? Data.EMPTY : data;
        // 列表计数还要能被"数量"这个名字取到（见 withCountAliases 的说明）——必须先算
        src = withCountAliases(root, src);
        expandRepeats(root, src);
        bindPlain(root, src);
    }

    /**
     * 给<b>列表计数</b>加一个短名字，让主题里能写 {@code data-if="!商品数量"}。
     *
     * <p>背景：计数存在带点的键里（{@code 商品.count}，见 {@link #expandRepeats} 与
     * {@code ThemeData#list}）。{@code data-if} 只认单个键名、不会拼字符串，
     * 所以主题作者没法用"商品 + .count"拼出来。以前只有两种选择：
     * 让 Java 侧额外 put 一个 {@code 商品数量}，或者主题里写不了这个条件 ——
     * 前者每个界面都要记得补、漏一个就出 bug（真发生过：8 个界面里的
     * {@code data-if="!商品数量"} 全都取不到值 → <b>列表非空时空状态提示照样显示</b>，
     * 把内容往下顶、最后一行被背包盖住）。</p>
     *
     * <p>现在统一在这里映射：扫一遍文档里所有 {@code data-repeat="X"}，
     * 就顺手提供 {@code X数量} → {@code X.count}。主题作者写
     * {@code data-if="!商品数量"}，Java 侧一个额外的键都不用 put。</p>
     */
    private static Data withCountAliases(HtmlNode root, Data base) {
        List<String> names = new ArrayList<>();
        root.walk(n -> {
            if (!n.isElement()) {
                return;
            }
            String key = n.attr("data-repeat");
            if (key != null && !key.isBlank()) {
                String k = key.trim();
                if (!names.contains(k)) {
                    names.add(k);
                }
            }
        });
        if (names.isEmpty()) {
            return base;
        }
        return key -> {
            // ① 短名字：商品数量 → 商品.count
            for (String n : names) {
                if ((n + "数量").equals(key)) {
                    String v = base.get(n + ".count");
                    if (v != null) {
                        return v;
                    }
                }
            }
            // ② 也允许直接写 商品.count（主题作者两种写法都能用）
            return base.get(key);
        };
    }

    /** 展开 {@code data-repeat="items"}：把模板元素复制成 N 份 */
    private static void expandRepeats(HtmlNode node, Data data) {
        for (HtmlNode child : new ArrayList<>(node.children)) {
            if (!child.isElement()) {
                continue;
            }
            String key = child.attr("data-repeat");
            if (key == null || key.isBlank()) {
                expandRepeats(child, data);
                continue;
            }
            int count = parseInt(data.get(key.trim() + ".count"));
            int index = node.children.indexOf(child);
            node.children.remove(index);
            for (int i = 0; i < count; i++) {
                HtmlNode clone = child.copy();
                clone.attrs.remove("data-repeat");
                // 行内取 {{名字}} 时自动带上 列表名.i. 前缀；取全局键（没有前缀）仍然可用。
                // 另外给每一行一个 {{行号}}：列表里的按钮靠它知道"点的是第几条"。
                bind(clone, new ScopedData(data, key.trim() + "." + i + ".", Integer.toString(i)));
                node.add(clone);
            }
        }
    }

    private static void bindPlain(HtmlNode root, Data src) {
        root.walk(n -> {
            if (n.isText()) {
                n.text = substitute(n.text, src);
                return;
            }
            // 属性里的 {{占位符}} 也要替换：列表行会写成
            //   data-action="buy" data-index="{{行号}}"
            // 点击时界面就能知道"点的是第几条"（动作名保持静态，方便排查日志）。
            for (java.util.Map.Entry<String, String> e : n.attrs.entrySet()) {
                String v = e.getValue();
                if (v != null && v.indexOf("{{") >= 0 && !"data-repeat".equals(e.getKey())) {
                    e.setValue(substitute(v, src));
                }
            }
            String cond = n.attr("data-if");
            if (cond != null && !cond.isBlank()) {
                n.dataHidden = !eval(cond, src);
            }
        });
    }

    private static int parseInt(String v) {
        try {
            return v == null ? 0 : Math.max(0, Math.min(500, Integer.parseInt(v.trim())));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 带前缀的数据视图：{@code get("名字")} 先找 {@code items.3.名字}，
     * 找不到再找全局的 {@code 名字} —— 这样列表行里既能用行内数据也能用全局数据。
     *
     * <p>{@code rowIndex} 是这一行的序号，暴露成 {@code 行号}：
     * 列表里的按钮靠它知道"点的是第几条"（写成 {@code data-action="buy" data-index="{{行号}}"}）。</p>
     */
    private record ScopedData(Data base, String prefix, String rowIndex) implements Data {
        @Override
        public String get(String key) {
            if ("行号".equals(key)) {
                return rowIndex;
            }
            String v = base.get(prefix + key);
            return v != null ? v : base.get(key);
        }
    }

    /** 把 {@code {{key}}} 换成值；未知的 key 原样留着（方便一眼看出没接上） */
    public static String substitute(String text, Data data) {
        if (text == null || text.indexOf('{') < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int open = text.indexOf("{{", i);
            if (open < 0) {
                out.append(text, i, text.length());
                break;
            }
            int close = text.indexOf("}}", open + 2);
            if (close < 0) {
                out.append(text, i, text.length());
                break;
            }
            out.append(text, i, open);
            String key = text.substring(open + 2, close).trim();
            String val = data.get(key);
            if (val != null) {
                out.append(val);
            } else {
                out.append(text, open, close + 2);      // 未知键：原样留着，方便一眼看出没接上
            }
            i = close + 2;
        }
        return out.toString();
    }

    /**
     * 求值一个显示条件。
     *
     * <p>v1 只支持两种写法：{@code name}（为真才显示）和 {@code !name}（为假才显示）。
     * 刻意不做表达式运算 —— 那是脚本的入口，一旦开了就收不住。</p>
     */
    public static boolean eval(String expr, Data data) {
        String e = expr.trim();
        boolean negate = e.startsWith("!");
        if (negate) {
            e = e.substring(1).trim();
        }
        boolean truthy = truthy(data.get(e));
        return negate != truthy;
    }

    public static boolean truthy(String v) {
        if (v == null) {
            return false;
        }
        String s = v.trim();
        return !(s.isEmpty() || "0".equals(s) || "false".equalsIgnoreCase(s)
                || "no".equalsIgnoreCase(s) || "off".equalsIgnoreCase(s));
    }
}
