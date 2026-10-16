package com.deepspacedeployer.ui.webview;

import com.deepspacedeployer.ui.theme.DataBinder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>喂给主题的数据</b>：一个扁平的 {@code 键 → 字符串} 表。
 *
 * <p>主题里写 {@code {{余额}}}，就是到这张表里取 {@code 余额}。</p>
 *
 * <p><b>列表怎么表示</b>（配合 {@code data-repeat="材料"}）：</p>
 * <pre>
 * 材料.count = 3            ← 有几项
 * 材料.0.名称 = 铁锭          ← 第 0 项的字段
 * 材料.0.数量 = 12
 * 材料.1.名称 = 铜锭
 * …
 * </pre>
 * 列表行里写 {@code {{名称}}} 会自动先找 {@code 材料.0.名称}，找不到再找全局的 {@code 名称}
 * （见 {@code DataBinder.ScopedData}），所以行里也能引用全局数据。
 *
 * <p>值一律是字符串：主题只做展示，不需要类型。数字由调用方格式化好
 * （比如金额自己加千位分隔符、小数自己定几位）。</p>
 */
public final class ThemeData implements DataBinder.Data {

    private final Map<String, String> values = new LinkedHashMap<>();

    public ThemeData put(String key, Object value) {
        if (key == null) {
            return this;
        }
        values.put(key, value == null ? "" : String.valueOf(value));
        return this;
    }

    /** 条件用：{@code data-if="在线"} 时，只有真值才显示 */
    public ThemeData put(String key, boolean value) {
        values.put(key, value ? "1" : "");
        return this;
    }

    public ThemeData put(String key, long value) {
        values.put(key, Long.toString(value));
        return this;
    }

    /**
     * 放一个列表。
     *
     * @param key   列表名（主题里写 {@code data-repeat="名字"}）
     * @param rows  每项是一张"字段 → 值"的表
     */
    public ThemeData list(String key, List<? extends Map<String, ?>> rows) {
        if (key == null) {
            return this;
        }
        int n = rows == null ? 0 : rows.size();
        values.put(key + ".count", Integer.toString(n));
        for (int i = 0; i < n; i++) {
            Map<String, ?> row = rows.get(i);
            if (row == null) {
                continue;
            }
            for (Map.Entry<String, ?> e : row.entrySet()) {
                values.put(key + "." + i + "." + e.getKey(),
                        e.getValue() == null ? "" : String.valueOf(e.getValue()));
            }
        }
        return this;
    }

    /** 常用：一行一行的"值列表"（每项只有一个字段，比如主题列表） */
    public ThemeData listOf(String key, String field, List<String> items) {
        List<Map<String, ?>> rows = new java.util.ArrayList<>();
        for (String s : items) {
            rows.add(Map.of(field, s == null ? "" : s));
        }
        return list(key, rows);
    }

    @Override
    public String get(String key) {
        return key == null ? null : values.get(key);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public int size() {
        return values.size();
    }

    /** 调试用：前几个键值 */
    public String brief() {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (n++ >= 6) {
                sb.append(" …");
                break;
            }
            if (n > 1) {
                sb.append(' ');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** 空数据（离线预览/自检用） */
    public static ThemeData empty() {
        return new ThemeData();
    }
}
