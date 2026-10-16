package com.deepspacedeployer.ui.theme;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>离线预览画布</b>：用 Java2D 把主题渲染成一张 PNG。
 *
 * <pre>
 * javac -d &lt;out&gt; （主题包里的 .java，除 ThemeManager）
 * java -cp &lt;out&gt; com.deepspacedeployer.ui.theme.PreviewCanvas &lt;主题目录&gt; &lt;宽&gt; &lt;高&gt; &lt;输出.png&gt; [k=v …]
 * </pre>
 *
 * <p><b>为什么值得存在</b>：老板是唯一的视觉判断者，但"改一行 CSS 就要开一次游戏"太慢。
 * 有了它，主题改完直接在终端出图，肉眼先过一遍再进游戏。</p>
 *
 * <p><b>字体度量刻意模拟原版</b>：Minecraft 用的是定宽点阵字体（半角 6px、全角 9~10px 每格，
 * 字号 9 时）。所以这里<b>不用 Java2D 的真实字宽</b>去排版，而是用同一套规则算宽度，
 * 再用系统字体画出来 —— 这样"预览里的换行位置"和游戏里一致，
 * 只是笔画细节不同（这一点会在输出里写明）。</p>
 */
public final class PreviewCanvas implements Canvas {

    /** 原版字号 9 时：半角一格 6px、全角一格 9px（含 1px 字距） */
    private static final float HALF_ADVANCE = 6f;
    private static final float FULL_ADVANCE = 9f;
    private static final float BASE_SIZE = 9f;

    private final Graphics2D g;
    private final Font baseFont;
    /** 相对主题画布左上角的偏移栈 */
    private final Deque<float[]> offsets = new ArrayDeque<>();
    private final Deque<java.awt.Shape> clips = new ArrayDeque<>();
    private float offX, offY;
    /** 画布尺寸（"无裁切"时用它兜底） */
    private int canvasW = 1, canvasH = 1;

    private PreviewCanvas(Graphics2D g, String fontName, int canvasW, int canvasH) {
        this.g = g;
        this.baseFont = new Font(fontName, Font.PLAIN, 12);
        this.canvasW = canvasW;
        this.canvasH = canvasH;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
    }

    /**
     * <b>造一个画布</b>（给离线工具和自检用）。
     *
     * <p>构造器是私有的（只希望从这个类的 main 进来），但"把主题渲染成 PNG"这件事
     * 现在有两个入口：命令行 {@code main}，以及 Gradle 任务跑的工具类。
     * 与其把构造器放开，不如给一个意图明确的工厂方法。</p>
     *
     * @param g       Java2D 画布
     * @param fontName 画字用的系统字体名（比如 "Microsoft YaHei"）
     */
    public static PreviewCanvas of(Graphics2D g, String fontName, int canvasW, int canvasH) {
        return new PreviewCanvas(g, fontName, canvasW, canvasH);
    }

    /** 挑一个能显示中文的系统字体（挑不到就交给 Java2D 默认） */
    public static String pickCjkFont() {
        for (String name : new String[]{"Microsoft YaHei", "SimHei", "Noto Sans CJK SC", "SansSerif"}) {
            if (fontExists(name)) {
                return name;
            }
        }
        return Font.SANS_SERIF;
    }

    // ---------------------------------------------------------------- Canvas 实现

    @Override
    public float textWidth(String text, float size, boolean bold) {
        if (text == null || text.isEmpty()) {
            return 0f;
        }
        float units = 0f;
        for (int i = 0; i < text.length(); i++) {
            units += isWide(text.charAt(i)) ? FULL_ADVANCE : HALF_ADVANCE;
        }
        return units * (size / BASE_SIZE);
    }

    @Override
    public float textHeight(float size) {
        return size + 1f;
    }

    @Override
    public void fillRect(float x, float y, float w, float h, int argb) {
        g.setColor(new Color(argb, true));
        g.fillRect(Math.round(x + offX), Math.round(y + offY), Math.round(w), Math.round(h));
    }

    @Override
    public void fillRoundRect(float x, float y, float w, float h, float radius, int argb) {
        g.setColor(new Color(argb, true));
        int r = Math.round(radius);
        g.fillRoundRect(Math.round(x + offX), Math.round(y + offY),
                Math.round(w), Math.round(h), r * 2, r * 2);
    }

    @Override
    public void strokeRect(float x, float y, float w, float h, float lineWidth, int argb) {
        g.setColor(new Color(argb, true));
        g.setStroke(new BasicStroke(lineWidth));
        g.drawRect(Math.round(x + offX), Math.round(y + offY),
                Math.round(w) - 1, Math.round(h) - 1);
    }

    @Override
    public void drawText(String text, float x, float y, float size, int argb, boolean bold) {
        // ⚠️ 字号直接用 size（不是 size×1.33）：原版的"字号 9"指的是**行高 9px**，
        //    中文字形正好占满这个高度。按 pt 放大会让字形高过行框、上下叠在一起
        //    （预览里出现过，纯属预览的画法问题，游戏内点阵字不会）。
        g.setFont(baseFont.deriveFont(bold ? Font.BOLD : Font.PLAIN, size));
        g.setColor(new Color(argb, true));
        // 基线落在行底往上 1px
        g.drawString(text, Math.round(x + offX), Math.round(y + offY + size - 1f));
    }

    @Override
    public void drawImage(byte[] png, float x, float y, float w, float h, boolean pixelated) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            if (img == null) {
                return;
            }
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, pixelated
                    ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                    : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, Math.round(x + offX), Math.round(y + offY),
                    Math.round(w), Math.round(h), null);
        } catch (Exception ignored) {
            // 图坏了就不画（游戏侧同理由 GuiCanvas 兜住）
        }
    }

    @Override
    public List<String> wrap(String text, float size, boolean bold, float maxWidth) {
        List<String> out = new ArrayList<>();
        float line = 0f;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            float adv = (isWide(c) ? FULL_ADVANCE : HALF_ADVANCE) * (size / BASE_SIZE);
            if (line > 0f && line + adv > maxWidth) {
                out.add(cur.toString());
                cur.setLength(0);
                line = 0f;
            }
            cur.append(c);
            line += adv;
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    @Override
    public void pushClip(float x, float y, float w, float h) {
        java.awt.Shape old = g.getClip();
        // Java2D 没设过裁切时 getClip() 返回 null，而栈不允许 null → 用整张画布当"无裁切"
        clips.push(old != null ? old : new java.awt.Rectangle(0, 0, canvasW, canvasH));
        g.setClip(Math.round(x + offX), Math.round(y + offY), Math.round(w), Math.round(h));
    }

    @Override
    public void popClip() {
        if (!clips.isEmpty()) {
            g.setClip(clips.pop());
        }
    }

    @Override
    public void pushOffset(float dx, float dy) {
        offsets.push(new float[]{offX, offY});
        offX += dx;
        offY += dy;
    }

    @Override
    public void popOffset() {
        if (!offsets.isEmpty()) {
            float[] p = offsets.pop();
            offX = p[0];
            offY = p[1];
        }
    }

    /** 全角判断：CJK、全角标点、常用符号区 */
    private static boolean isWide(char c) {
        return (c >= 0x1100 && c <= 0x115F)
                || (c >= 0x2E80 && c <= 0xA4CF)
                || (c >= 0xAC00 && c <= 0xD7A3)
                || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0xFE30 && c <= 0xFE6F)
                || (c >= 0xFF00 && c <= 0xFF60)
                || (c >= 0xFFE0 && c <= 0xFFE6);
    }

    // ---------------------------------------------------------------- 命令行入口

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.out.println("用法: PreviewCanvas <主题目录> <宽> <高> <输出.png> [k=v ...]");
            return;
        }
        Path dir = Path.of(args[0]).toAbsolutePath().normalize();
        int width = Integer.parseInt(args[1]);
        int height = Integer.parseInt(args[2]);
        File out = new File(args[3]);

        Map<String, String> data = new HashMap<>();
        for (int i = 4; i < args.length; i++) {
            // @文件：一行一个 key=value（UTF-8）。中文参数走命令行容易被编码坑，推荐用这个。
            if (args[i].startsWith("@")) {
                for (String line : java.nio.file.Files.readAllLines(
                        Path.of(args[i].substring(1)), java.nio.charset.StandardCharsets.UTF_8)) {
                    String s = line.strip();
                    if (s.isEmpty() || s.startsWith("#")) {
                        continue;
                    }
                    int eq = s.indexOf('=');
                    if (eq > 0) {
                        data.put(s.substring(0, eq).trim(), s.substring(eq + 1).trim());
                    }
                }
                continue;
            }
            int eq = args[i].indexOf('=');
            if (eq > 0) {
                data.put(args[i].substring(0, eq), args[i].substring(eq + 1));
            }
        }

        Path parent = dir.getParent();
        ThemeSource src = new ThemeSource(dir.getFileName().toString(), dir,
                parent, parent == null ? dir : parent.resolve("default"));

        ThemeDocument doc = ThemeDocument.load(src, "index.html", data::get);
        if (doc == null) {
            System.out.println("× 找不到 " + dir.resolve("index.html"));
            return;
        }

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(0x101014));
        g.fillRect(0, 0, width, height);

        PreviewCanvas canvas = PreviewCanvas.of(g, pickCjkFont(), width, height);
        LayoutEngine engine = new LayoutEngine(canvas);
        float contentH = engine.layout(doc.root, width, height);
        new ThemeRenderer(src, canvas).render(doc.root);
        g.dispose();

        ImageIO.write(image, "png", out);
        System.out.println("✔ 已输出 " + out.getAbsolutePath());
        System.out.println("  主题=" + src.name() + "  元素=" + doc.elementCount()
                + "  规则=" + doc.rules.size() + "  内容高=" + Math.round(contentH)
                + "  视口=" + width + "x" + height);
        for (String w : doc.warnings) {
            System.out.println("  ⚠ " + w);
        }
        System.out.println("  注：布局用原版字宽规则算（半角 6px / 全角 9px），"
                + "笔画由系统字体绘制，和游戏内字形略有差别");
    }

    private static boolean fontExists(String name) {
        return new Font(name, Font.PLAIN, 12).getFamily().equalsIgnoreCase(name)
                || java.util.Arrays.asList(java.awt.GraphicsEnvironment
                .getLocalGraphicsEnvironment().getAvailableFontFamilyNames()).contains(name);
    }
}
