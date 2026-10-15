package com.deepspacedeployer.ui.theme;

/**
 * 一个长度值：{@code auto} / 像素 / 百分比。
 *
 * <p>刻意做成不可变的小对象 —— 样式解析阶段算一次，布局阶段反复用。</p>
 */
public final class Dim {

    public enum Unit { AUTO, PX, PERCENT }

    public static final Dim AUTO = new Dim(0, Unit.AUTO);
    public static final Dim ZERO = new Dim(0, Unit.PX);

    public final float value;
    public final Unit unit;

    private Dim(float value, Unit unit) {
        this.value = value;
        this.unit = unit;
    }

    public static Dim px(float v) {
        return new Dim(v, Unit.PX);
    }

    public static Dim percent(float v) {
        return new Dim(v, Unit.PERCENT);
    }

    public boolean isAuto() {
        return unit == Unit.AUTO;
    }

    /** 求值：{@code base} 是百分比参照（父容器的内容宽度/高度） */
    public float resolve(float base) {
        return switch (unit) {
            case PX -> value;
            case PERCENT -> base * value / 100f;
            case AUTO -> Float.NaN;            // auto 由布局决定
        };
    }

    /** 解析 CSS 里的写法："12px" / "50%" / "auto" / "12" */
    public static Dim parse(String raw, Dim fallback) {
        if (raw == null) {
            return fallback;
        }
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.isEmpty()) {
            return fallback;
        }
        if ("auto".equals(s)) {
            return AUTO;
        }
        try {
            if (s.endsWith("%")) {
                return percent(Float.parseFloat(s.substring(0, s.length() - 1).trim()));
            }
            if (s.endsWith("px")) {
                return px(Float.parseFloat(s.substring(0, s.length() - 2).trim()));
            }
            // 没有单位的一律当像素（像素画界面的习惯写法）
            return px(Float.parseFloat(s));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public String toString() {
        return switch (unit) {
            case AUTO -> "auto";
            case PX -> value + "px";
            case PERCENT -> value + "%";
        };
    }
}
