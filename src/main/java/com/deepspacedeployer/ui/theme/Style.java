package com.deepspacedeployer.ui.theme;

/**
 * <b>一条元素最终生效的样式</b>（CSS 层叠 + 继承算完之后的结果）。
 *
 * <p>能用的属性就是下面这些字段 —— 这就是我们对老板承诺的<b>子集边界</b>：
 * 表里没有的 CSS 属性会被解析器忽略（不报错，只是不生效）。
 * 想加新属性就在 {@link CssParser#apply} 里加一个 case，然后在这里加字段。</p>
 *
 * <p>默认值对齐 Minecraft 本身：字号 9px（原版字体高度）、无背景、block 布局。</p>
 */
public final class Style {

    // ---- 颜色（0xAARRGGBB；null = 不画 / 继承） ----
    /** 文字颜色 */
    public Integer color;
    /** 背景色 */
    public Integer background;
    /** 背景图（主题内的相对路径，渲染时再解析） */
    public String backgroundImage;
    /** 背景图怎么铺：stretch（默认）/ tile */
    public String backgroundSize = "stretch";

    // ---- 盒子 ----
    public Dim width = Dim.AUTO;
    public Dim height = Dim.AUTO;
    public float padTop, padRight, padBottom, padLeft;
    public float marTop, marRight, marBottom, marLeft;
    public float borderWidth;
    public Integer borderColor;
    public float radius;

    // ---- 文字 ----
    /** 字号（像素）。原版字体建议 9 的整数倍缩放 */
    public float fontSize = 9f;
    /** left / center / right */
    public String textAlign = "left";
    /** 行高（px）；0 = 用字号 */
    public float lineHeight;

    // ---- 布局 ----
    /** block / flex / none */
    public String display = "block";
    /** row / column（display:flex 时有效） */
    public String flexDirection = "row";
    /** flex-start / center / flex-end / stretch */
    public String alignItems = "stretch";
    /** flex-start / center / flex-end / space-between */
    public String justifyContent = "flex-start";
    /** 子元素间距 */
    public float gap;

    /** static / relative / absolute */
    public String position = "static";
    public Float top, right, bottom, left;

    /** 0~1，只作用于本元素自身绘制（不做子树合成，够用就行） */
    public float opacity = 1f;
    /** visible / hidden / scroll */
    public String overflow = "visible";
    /** 图层：越大越靠上（默认 0；背景建议 -1） */
    public int zIndex;
    /** 图片放大时用最近邻（像素画必开） */
    public boolean pixelated;

    public Style() {
    }

    /** 复制一份（继承给子元素时用） */
    public Style copy() {
        Style s = new Style();
        s.color = color;
        s.background = background;
        s.backgroundImage = backgroundImage;
        s.backgroundSize = backgroundSize;
        s.width = width;
        s.height = height;
        s.padTop = padTop;
        s.padRight = padRight;
        s.padBottom = padBottom;
        s.padLeft = padLeft;
        s.marTop = marTop;
        s.marRight = marRight;
        s.marBottom = marBottom;
        s.marLeft = marLeft;
        s.borderWidth = borderWidth;
        s.borderColor = borderColor;
        s.radius = radius;
        s.fontSize = fontSize;
        s.textAlign = textAlign;
        s.lineHeight = lineHeight;
        s.display = display;
        s.flexDirection = flexDirection;
        s.alignItems = alignItems;
        s.justifyContent = justifyContent;
        s.gap = gap;
        s.position = position;
        s.top = top;
        s.right = right;
        s.bottom = bottom;
        s.left = left;
        s.opacity = opacity;
        s.overflow = overflow;
        s.zIndex = zIndex;
        s.pixelated = pixelated;
        return s;
    }

    /** 把"可继承"的属性从父级拿过来（CSS 里只有这几个会继承） */
    public void inheritFrom(Style parent) {
        if (color == null) {
            color = parent.color;
        }
        if (lineHeight == 0f) {
            lineHeight = parent.lineHeight;
        }
        if (!textAlignSelf) {
            textAlign = parent.textAlign;
        }
        // 字号也继承（除非自己写了）
        if (fontSizeSelf == 0f) {
            fontSize = parent.fontSize;
        }
    }

    /** 内部用：记录"作者自己写过 font-size 吗"（0 表示没写） */
    float fontSizeSelf;

    /** 内部用：记录"作者自己写过 text-align 吗" */
    boolean textAlignSelf;

    public boolean visible() {
        return !"none".equals(display);
    }

    public boolean isFlex() {
        return "flex".equals(display);
    }

    public boolean isColumn() {
        return "column".equals(flexDirection);
    }

    public float effectiveLineHeight() {
        return lineHeight > 0f ? lineHeight : fontSize + 1f;
    }
}
