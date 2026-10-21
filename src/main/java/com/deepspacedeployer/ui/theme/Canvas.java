package com.deepspacedeployer.ui.theme;

/**
 * <b>画布抽象</b>：布局算完之后，所有绘制都通过这几个方法出去。
 *
 * <p>刻意只有这么几个原语，因为要在两个地方各实现一遍、并且保证"长得一模一样"：</p>
 * <ul>
 *   <li>{@code GuiCanvas} —— 游戏里画到 {@code GuiGraphics} 上；</li>
 *   <li>{@code PreviewCanvas} —— 离线用 Java2D 画成 PNG（给老板看效果、给我做回归）。</li>
 * </ul>
 *
 * <p>坐标一律相对<b>主题画布左上角</b>；用户滚动/裁剪通过偏移与裁切栈表达。</p>
 */
public interface Canvas {

    /** 一段文字的宽度（像素） */
    float textWidth(String text, float size, boolean bold);

    /** 一行文字的高度（像素） */
    float textHeight(float size);

    /** 实心矩形 */
    void fillRect(float x, float y, float w, float h, int argb);

    /** 圆角实心矩形（radius=0 时等价于 fillRect） */
    void fillRoundRect(float x, float y, float w, float h, float radius, int argb);

    /** 矩形边框（线宽 w） */
    void strokeRect(float x, float y, float w, float h, float lineWidth, int argb);

    /**
     * 画一行文字（左上角对齐）。
     *
     * @param size 字号（像素）；9 = 原版字号
     */
    void drawText(String text, float x, float y, float size, int argb, boolean bold);

    /**
     * 画一张图（PNG 字节）。{@code pixelated=true} 时用最近邻放大
     * —— 像素画必须开，不然会糊（老板踩过一次）。
     */
    void drawImage(byte[] png, float x, float y, float w, float h, boolean pixelated);

    /**
     * 在矩形里画一个<b>物品图标</b>（{@code minecraft:iron_ingot} 这种 id）。
     *
     * <p>这是"物品展示框"的核心：物品不是原版槽位，而是我们自己按
     * 框的位置/大小/角度画出来的 —— 所以框怎么动，物品就怎么跟，不会错位。</p>
     *
     * <p>默认空实现：离线预览 / 只量不画的画布不需要真的画物品。</p>
     *
     * @param count     数量（&gt;1 时画右下角数字）
     * @param rotateDeg 旋转角度（绕框中心，单位度）
     */
    default void drawItem(String itemId, int count, float x, float y, float w, float h,
                          float rotateDeg) {
    }

    /** 指定宽度下把文字折行（返回每一行的文本） */
    java.util.List<String> wrap(String text, float size, boolean bold, float maxWidth);

    // ---- 裁切与偏移（滚动/overflow:hidden 用） ----

    void pushClip(float x, float y, float w, float h);

    void popClip();

    void pushOffset(float dx, float dy);

    void popOffset();
}
