package com.deepspacedeployer.ui.webview;

/**
 * <b>逻辑规则的条件求值</b>（UI 编辑器里写的 {@code 余额>100} 这种）。
 *
 * <p>支持的形式（够用即可，不做表达式引擎）：</p>
 * <pre>
 *   余额&gt;100        数字比较：&gt; &gt;= &lt; &lt;= == !=
 *   账户==小明       字符串相等（不写引号）
 *   在线             "有值且不是 0/false/空" 即真
 *   !在线            取反
 *   （空）            恒真
 * </pre>
 *
 * <p>数据来自界面喂给主题的那份 {@link ThemeData}（例如 {@code 余额}、{@code 声望}、
 * {@code 账户}）。字段不存在按空串处理 —— 条件写错只会"不执行动作"，不会崩。</p>
 */
public final class ThemeCondition {

    private ThemeCondition() {
    }

    /** 条件是否成立；空条件恒真 */
    public static boolean test(String cond, ThemeData data) {
        if (cond == null || cond.isBlank()) {
            return true;
        }
        String c = cond.trim();
        boolean negate = false;
        while (c.startsWith("!")) {
            negate = !negate;
            c = c.substring(1).trim();
        }
        boolean result = eval(c, data);
        return negate != result;
    }

    private static boolean eval(String c, ThemeData data) {
        String[] ops = {">=", "<=", "!=", "==", ">", "<"};
        for (String op : ops) {
            int at = c.indexOf(op);
            if (at <= 0) {
                continue;
            }
            String left = c.substring(0, at).trim();
            String right = c.substring(at + op.length()).trim();
            String value = left(data, left);
            if (isNumber(value) && isNumber(right)) {
                double a = Double.parseDouble(value);
                double b = Double.parseDouble(right);
                return switch (op) {
                    case ">=" -> a >= b;
                    case "<=" -> a <= b;
                    case ">" -> a > b;
                    case "<" -> a < b;
                    case "==" -> a == b;
                    default -> a != b;
                };
            }
            return switch (op) {
                case "==" -> value.equals(right);
                case "!=" -> !value.equals(right);
                default -> false;                 // 字符串不能比大小
            };
        }
        // 没有运算符：当布尔看
        String v = left(data, c);
        return !v.isEmpty() && !"0".equals(v) && !"false".equalsIgnoreCase(v);
    }

    private static String left(ThemeData data, String key) {
        try {
            String v = data == null ? null : data.get(key);
            return v == null ? "" : v;
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean isNumber(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
