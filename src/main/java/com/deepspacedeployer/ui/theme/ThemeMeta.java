package com.deepspacedeployer.ui.theme;

/**
 * <b>一套主题的元数据</b>（{@code index.json}）。
 *
 * <p>字段（全部可选，缺了当空串）：</p>
 * <pre>
 * {
 *   "name": "默认主题",          // 显示名（没写就用文件夹名）
 *   "author": "深空铸造体",       // 作者
 *   "version": "2.0.0",         // 版本
 *   "description": "……",        // 介绍
 *   "icon": "icon.png"          // ★ 图标：相对主题文件夹，和 index.json 同级
 * }
 * </pre>
 *
 * <p><b>icon 规则</b>：写相对路径（{@code icon.png}、{@code art/logo.png}）；
 * 不写就默认找 {@code icon.png}；找不到时 {@link ThemeSource} 会自动回退到
 * {@code theme/default/} 里的同名文件 —— 所以自定义主题不写 icon 也能显示默认图标。</p>
 *
 * <p>不引第三方 JSON 库：结构极简单，手写一个只认 {@code "key": "value"} 的解析就够，
 * 解析失败当"没有元数据"（绝不因为一个坏文件让界面打不开）。</p>
 */
public record ThemeMeta(String name, String author, String version, String description, String icon) {

    public static final ThemeMeta EMPTY = new ThemeMeta("", "", "", "", "");

    /** 没写 icon 时的默认文件名 */
    public static final String DEFAULT_ICON = "icon.png";

    /** 解析 index.json 文本；任何异常都返回 {@link #EMPTY} */
    public static ThemeMeta parse(String json) {
        if (json == null || json.isBlank()) {
            return EMPTY;
        }
        try {
            return new ThemeMeta(
                    stringValue(json, "name"),
                    stringValue(json, "author"),
                    stringValue(json, "version"),
                    stringValue(json, "description"),
                    stringValue(json, "icon"));
        } catch (Throwable t) {
            return EMPTY;
        }
    }

    /** 界面上显示的名字：没写就用文件夹名 */
    public String displayName(String fallback) {
        return name == null || name.isBlank() ? fallback : name;
    }

    /** 图标文件名（没写就是 {@code icon.png}） */
    public String iconFile() {
        return icon == null || icon.isBlank() ? DEFAULT_ICON : icon.trim();
    }

    /**
     * 极小的 JSON 取值：找 {@code "key"} 后面的字符串值（支持 {@code \" \\ \n \t} 转义）。
     */
    static String stringValue(String json, String key) {
        if (json == null || key == null) {
            return "";
        }
        int at = json.indexOf('"' + key + '"');
        if (at < 0) {
            return "";
        }
        int colon = json.indexOf(':', at);
        if (colon < 0) {
            return "";
        }
        int open = json.indexOf('"', colon + 1);
        if (open < 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = open + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(++i);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    default -> sb.append(n);
                }
                continue;
            }
            if (c == '"') {
                break;
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
