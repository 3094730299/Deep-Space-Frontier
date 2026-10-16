package com.deepspacedeployer.ui.theme;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>布局结果</b>：一个元素算完之后在画布上的位置与尺寸。
 *
 * <p>由 {@code LayoutEngine} 填，渲染器只读它。坐标一律相对<b>主题画布左上角</b>
 * （游戏里再整体平移到界面位置）。</p>
 */
public final class LayoutBox {

    /** 边框盒（内容 + 内边距 + 边框） */
    public float x, y, w, h;
    /** 是否要画（display:none、data-if 不成立 → false） */
    public boolean visible = true;
    /** 内容实际高度（可能大于 h，overflow:scroll 时用来算滚动范围） */
    public float contentHeight;
    /**
     * 在父容器流里<b>占用</b>的高度。
     *
     * <p>和 {@link #h} 的区别：写了 {@code height} 的容器即使内容更高，也只占自己那么高
     * （多出来的内容被 overflow 裁掉）；而 {@link #h} 对"没写高度"的元素会被内容撑大。
     * 布局引擎用它决定"下一个兄弟元素从哪儿开始"。</p>
     */
    public float usedHeight;
    /** 文本框：折好行的文字（只有"直接含文字"的元素才有） */
    public final List<TextLine> lines = new ArrayList<>();

    // ---- 便捷取值 ----

    public float contentX(Style s) {
        return x + s.borderWidth + s.padLeft;
    }

    public float contentY(Style s) {
        return y + s.borderWidth + s.padTop;
    }

    public float contentW(Style s) {
        return Math.max(0f, w - 2f * s.borderWidth - s.padLeft - s.padRight);
    }

    public float contentH(Style s) {
        return Math.max(0f, h - 2f * s.borderWidth - s.padTop - s.padBottom);
    }

    public boolean hit(float px, float py) {
        return visible && px >= x && px < x + w && py >= y && py < y + h;
    }

    /** 一行文字 */
    public static final class TextLine {
        public final List<TextRun> runs = new ArrayList<>();
        /** 行顶 y（相对画布） */
        public float y;
        public float height;
        /** 行宽（居中/右对齐时用） */
        public float width = 0f;
    }

    /** 一段同格式的文字 */
    public static final class TextRun {
        public String text;
        public int color = 0xFFE8F4FF;
        public boolean bold;
        /** 这段文字在行内的起始 x（相对画布） */
        public float x;
    }
}
