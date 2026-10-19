package com.deepspacedeployer.ui.webview;

import com.deepspacedeployer.DeepSpaceDeployer;
import com.deepspacedeployer.ui.theme.LayoutEngine;
import com.deepspacedeployer.ui.theme.ThemeDocument;
import com.deepspacedeployer.ui.theme.ThemeManager;
import com.deepspacedeployer.ui.theme.ThemeRenderer;
import com.deepspacedeployer.ui.theme.ThemeSource;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * <b>主题编辑器（第 2 步：文件浏览器 + 预览）</b>。
 *
 * <p>老板 2026-10-19 的要求：像 MT 管理器一样浏览主题目录、右键做文件操作，
 * 选中 json 看文本、选中图片看渲染、选中 html 看渲染效果。
 * <b>这个界面是模组内置的，不受任何主题影响</b>（不读主题里的 html/css）。</p>
 *
 * <p>布局：左栏 = 目录树（首项 {@code ..} 返回上级）；中栏 = 预览；右栏 = 文件信息与操作。</p>
 *
 * <p>后续步骤（已列入待办）：文本编辑、组件库、画布拖拽/缩放、属性面板、图层、保存回 html。</p>
 */
public class ThemeEditorScreen extends Screen {

    private static final int LEFT_W = 150;
    private static final int RIGHT_W = 140;
    private static final int ROW_H = 12;

    private static final int COL_PANEL = 0xF012161F;
    private static final int COL_HEAD = 0xFF1B2233;
    private static final int COL_ROW = 0xFF161C28;
    private static final int COL_ROW_HOVER = 0xFF22304A;
    private static final int COL_SEL = 0xFF1B2A45;
    private static final int COL_BORDER = 0xFF2A3550;
    private static final int COL_TEXT = 0xFFE6ECF5;
    private static final int COL_DIM = 0xFF8A97AD;
    private static final int COL_ACCENT = 0xFF4C7DFF;
    private static final int COL_BAD = 0xFFFF7A7A;

    /** 剪贴板（复制/剪切共用） */
    private static Path clipboard;
    private static boolean clipboardCut;

    private final String themeName;
    private final Screen parent;
    private final ThemeSource source;

    /** 当前所在目录（相对主题根） */
    private String dir = "";
    private final List<Path> entries = new ArrayList<>();
    private int selected = -1;
    private float scroll;

    /** 右键菜单（屏幕坐标；null = 没打开） */
    private int menuX = -1;
    private int menuY = -1;
    private final String[] menuItems = {"复制", "剪切", "粘贴", "重命名", "删除"};

    /** 重命名状态 */
    private boolean renaming;
    private EditBox renameBox;
    private Path renameTarget;
    /** 非 null = 正在"导入资源"（从系统选的图），保存时复制进主题目录 */
    private Path importSource;

    /** 正在编辑的文本文件（null = 只看不编） */
    private Path editingFile;
    private ThemeTextBuffer buffer;
    private boolean dirty;
    /** 光标闪烁计时 */
    private long caretTick;

    /** 文本预览的滚动 */
    private float textScroll;

    // ---------------- 组件编辑（第 3 步） ----------------
    /** 正在编辑组件的 html 文件（null = 没进入组件编辑） */
    private Path compFile;
    private final List<Comp> comps = new ArrayList<>();
    private Comp sel;
    private boolean dragging;
    private boolean resizing;
    private int grabDx;
    private int grabDy;
    /** 画布在屏幕上的位置（渲染时记下来，鼠标命中用） */
    private int canvasX;
    private int canvasY;
    private int canvasW;
    private int canvasH;
    private static final int HANDLE = 7;

    // ---------------- 主题里已有元素的编辑（需要渲染器提供源码位置） ----------------
    /** 当前预览的文档（每帧重建，用来做命中测试） */
    private com.deepspacedeployer.ui.theme.ThemeDocument previewDoc;
    /** 画布上选中的已有元素 */
    private com.deepspacedeployer.ui.theme.HtmlNode pickedNode;
    /** 已有元素被拖到哪儿：key = 节点，value = {x, y, w, h} */
    private final java.util.Map<com.deepspacedeployer.ui.theme.HtmlNode, int[]> nodeEdits =
            new java.util.LinkedHashMap<>();
    private boolean draggingNode;
    private boolean resizingNode;

    /** 一个可编辑组件（只做"摆设"，不参与方块逻辑 —— 老板的规则） */
    private static final class Comp {
        String type = "text";
        String text = "";
        /** 图片组件的图片（相对主题根/当前目录的路径） */
        String src = "";
        /**
         * 点击时触发的动作（空 = 纯摆设）。
         *
         * <p>动作名就是宿主界面 {@code onThemeAction} 认识的那些（{@code close}、
         * {@code sell_now}、{@code toggle_autosell}……），保存时写成 {@code data-action}，
         * 这样渲染器/宿主现有的点击派发**不用改就能用**。</p>
         */
        String action = "";
        int x = 10;
        int y = 10;
        int w = 80;
        int h = 18;
        int layer = 0;
    }

    public ThemeEditorScreen(String themeName, Screen parent) {
        super(Component.translatable("gui.deepspacedeployer.editor_title", themeName));
        this.themeName = themeName;
        this.parent = parent;
        this.source = ThemeManager.source(themeName);
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void init() {
        super.init();
        reloadEntries();
        rebuildFooter();
    }

    private void rebuildFooter() {
        clearWidgets();
        if (renaming) {
            return;                       // 重命名时只显示输入框（在 render 里画）
        }
        int by = this.height - 26;
        addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.editor_back"),
                        b -> this.onClose())
                .bounds(this.width / 2 - 150, by, 88, 20).build());
        Button save = Button.builder(Component.translatable("gui.deepspacedeployer.editor_save"),
                        b -> saveFile())
                .bounds(this.width / 2 - 50, by, 88, 20).build();
        save.active = editingFile != null && dirty;      // 没改动就是灰的
        addRenderableWidget(save);
        addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.editor_open_folder"),
                        b -> openInExplorer())
                .bounds(this.width / 2 + 50, by, 100, 20).build());
        if (compFile != null) {
            addRenderableWidget(Button.builder(
                            Component.translatable("gui.deepspacedeployer.editor_save_html"),
                            b -> saveComps())
                    .bounds(this.width / 2 + 154, by, 96, 20).build());
        }
    }

    @Override
    public void onClose() {
        ThemeImages.releaseAll();
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    // ---------------------------------------------------------------- 文件列表

    private void reloadEntries() {
        entries.clear();
        try {
            Path base = themeDir();
            Path current = dir.isEmpty() ? base : base.resolve(dir).normalize();
            if (current.startsWith(base) && Files.isDirectory(current)) {
                try (Stream<Path> list = Files.list(current)) {
                    List<Path> dirs = new ArrayList<>();
                    List<Path> files = new ArrayList<>();
                    list.forEach(p -> (Files.isDirectory(p) ? dirs : files).add(p));
                    dirs.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()));
                    files.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()));
                    entries.addAll(dirs);
                    entries.addAll(files);
                }
            }
        } catch (Throwable t) {
            DeepSpaceDeployer.LOGGER.warn("[深空铸造体][编辑器] 列目录失败：{}", t.toString());
        }
        selected = -1;
        scroll = 0;
    }

    private Path themeDir() {
        return source.dir();
    }

    private Path selectedPath() {
        return selected >= 0 && selected < entries.size() ? entries.get(selected) : null;
    }

    private String relOf(Path p) {
        try {
            return themeDir().relativize(p).toString().replace('\\', '/');
        } catch (Throwable t) {
            return p.getFileName().toString();
        }
    }

    // ---------------------------------------------------------------- 渲染

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 系统文件选择器是异步的：每帧取一次结果，选好了就弹"重命名"对话框
        Path picked = ThemeFilePicker.consume();
        if (picked != null && sel != null && !renaming) {
            startImport(picked);
        }
        this.renderBackground(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, 8, COL_TEXT);

        int top = 26;
        int bottom = this.height - 32;

        drawLeft(g, mouseX, mouseY, top, bottom);
        drawCenter(g, top, bottom);
        drawRight(g, mouseX, mouseY, top, bottom);

        for (Renderable r : this.renderables) {
            r.render(g, mouseX, mouseY, partialTick);
        }

        if (menuX >= 0) {
            drawContextMenu(g, mouseX, mouseY);
        }
        if (renaming) {
            drawRenameDialog(g, mouseX, mouseY);
        }
    }

    private void drawLeft(GuiGraphics g, int mouseX, int mouseY, int top, int bottom) {
        int x = 6;
        g.fill(x, top - 14, x + LEFT_W, bottom, COL_PANEL);
        g.fill(x, top - 14, x + LEFT_W, top, COL_HEAD);
        g.drawString(this.font, "/" + (dir.isEmpty() ? "" : dir + "/"), x + 4, top - 11, COL_DIM, false);
        drawBorder(g, x, top - 14, LEFT_W, bottom - top + 14, COL_BORDER);

        g.enableScissor(x + 1, top, x + LEFT_W - 1, bottom - 1);
        float y = top + 2 - scroll;
        if (!dir.isEmpty()) {
            drawRow(g, x + 1, Math.round(y), "..", COL_DIM, false, mouseX, mouseY);
            y += ROW_H;
        }
        for (int i = 0; i < entries.size(); i++) {
            Path p = entries.get(i);
            boolean isDir = Files.isDirectory(p);
            String name = p.getFileName().toString() + (isDir ? "/" : "");
            boolean sel = i == selected;
            drawRow(g, x + 1, Math.round(y), name, isDir ? COL_ACCENT : COL_TEXT, sel, mouseX, mouseY);
            y += ROW_H;
        }
        g.disableScissor();
    }

    private void drawRow(GuiGraphics g, int x, int y, String text, int color, boolean sel,
                         int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX < x + LEFT_W - 2 && mouseY >= y && mouseY < y + ROW_H;
        if (sel || hover) {
            g.fill(x, y, x + LEFT_W - 2, y + ROW_H, sel ? COL_SEL : COL_ROW_HOVER);
        }
        g.drawString(this.font, this.font.plainSubstrByWidth(text, LEFT_W - 8), x + 3, y + 2,
                color, false);
    }

    private void drawCenter(GuiGraphics g, int top, int bottom) {
        int x = 6 + LEFT_W + 4;
        int w = this.width - RIGHT_W - 10 - x;
        int h = bottom - top;
        g.fill(x, top, x + w, bottom, 0xFF0A0C12);
        drawBorder(g, x, top, w, h, COL_BORDER);

        Path p = selectedPath();
        if (p == null) {
            g.drawCenteredString(this.font,
                    Component.translatable("gui.deepspacedeployer.editor_pick_file"),
                    x + w / 2, top + h / 2 - 4, COL_DIM);
            return;
        }
        if (Files.isDirectory(p)) {
            g.drawCenteredString(this.font,
                    Component.translatable("gui.deepspacedeployer.editor_dir_hint"),
                    x + w / 2, top + h / 2 - 4, COL_DIM);
            return;
        }
        String name = p.getFileName().toString().toLowerCase();
        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            drawImagePreview(g, p, x, top, w, h);
        } else if (name.endsWith(".html") || name.endsWith(".htm")) {
            drawHtmlPreview(g, p, x, top, w, h);
        } else if (editingFile != null && editingFile.equals(p) && buffer != null) {            drawEditor(g, p, x, top, w, h);
        } else {
            drawTextPreview(g, p, x, top, w, h);
        }
    }

    /** 可编辑的文本区（带光标） */
    private void drawEditor(GuiGraphics g, Path p, int x, int top, int w, int h) {
        int lineH = 10;
        int visible = Math.max(1, (h - 14) / lineH);
        buffer.setScrollLine(buffer.scrollLine());
        buffer.ensureVisible(visible);

        int first = buffer.scrollLine();
        g.enableScissor(x + 1, top + 1, x + w - 1, top + h - 13);
        for (int i = 0; i < visible && first + i < buffer.lineCount(); i++) {
            String line = buffer.line(first + i).replace("\t", "    ");
            int drawY = top + 4 + i * lineH;
            g.drawString(this.font, this.font.plainSubstrByWidth(line, w - 14),
                    x + 5, drawY, COL_TEXT, false);
        }
        // 光标：500ms 亮 / 500ms 灭
        if ((System.currentTimeMillis() / 500L) % 2 == 0) {
            int caretRow = buffer.caretLine() - first;
            if (caretRow >= 0 && caretRow < visible) {
                String before = buffer.line(buffer.caretLine());
                before = before.substring(0, Math.min(buffer.caretCol(), before.length()))
                        .replace("\t", "    ");
                int cx = x + 5 + this.font.width(before);
                int cy = top + 4 + caretRow * lineH;
                g.fill(cx, cy, cx + 1, cy + 9, COL_ACCENT);
            }
        }
        g.disableScissor();

        g.drawString(this.font,
                (dirty ? "● " : "") + buffer.caretLine() + ":" + buffer.caretCol()
                        + "  " + relOf(p),
                x + 4, top + h - 11, dirty ? COL_ACCENT : COL_DIM, false);
    }

    private void drawImagePreview(GuiGraphics g, Path p, int x, int top, int w, int h) {
        ResourceLocation loc = ThemeImages.get(p);
        if (loc == null) {
            g.drawCenteredString(this.font, "无法读取图片", x + w / 2, top + h / 2 - 4, COL_BAD);
            return;
        }
        // 缩放到框内（保持比例），最多放大 4 倍，避免小图糊成一片
        int[] size = imageSize(p);
        int iw = Math.max(1, size[0]);
        int ih = Math.max(1, size[1]);
        float scale = Math.min((w - 12f) / iw, (h - 12f) / ih);
        scale = Math.min(scale, 4f);
        if (scale <= 0f) {
            scale = 1f;
        }
        int dw = Math.round(iw * scale);
        int dh = Math.round(ih * scale);
        int dx = x + (w - dw) / 2;
        int dy = top + (h - dh) / 2;
        g.blit(loc, dx, dy, 0f, 0f, dw, dh, iw, ih);
        g.drawString(this.font, iw + "×" + ih, x + 4, top + h - 12, COL_DIM, false);
    }

    private void drawHtmlPreview(GuiGraphics g, Path p, int x, int top, int w, int h) {
        try {
            String rel = relOf(p);
            ThemeDocument doc = ThemeDocument.load(source, rel, null);
            if (doc == null || doc.root == null) {
                g.drawString(this.font, "HTML 解析失败", x + 6, top + 6, COL_BAD, false);
                return;
            }
            previewDoc = doc;
            applyNodeEdits(doc.root);

            GuiCanvas canvas = new GuiCanvas(g, x + 4, top + 4);
            LayoutEngine engine = new LayoutEngine(canvas);
            // 预览按界面本身的建议尺寸布局，再用左上角对齐（不做缩放，保持 1:1 像素）
            engine.layout(doc.root, Math.min(w - 8, 340), Math.min(h - 8, 340));
            ThemeRenderer renderer = new ThemeRenderer(source, canvas);
            renderer.render(doc.root);
            canvas.finishFrame();

            // ★ 组件层：画在主题渲染的上面（图层越大越靠上）
            canvasX = x + 4;
            canvasY = top + 4;
            canvasW = Math.min(w - 8, 340);
            canvasH = Math.min(h - 8, 340);
            drawComponents(g);
            drawPickedNode(g);

            g.drawString(this.font, rel, x + 4, top + h - 12, COL_DIM, false);
        } catch (Throwable t) {
            g.drawString(this.font, "预览出错：" + t.getMessage(), x + 6, top + 6, COL_BAD, false);
        }
    }

    private void drawTextPreview(GuiGraphics g, Path p, int x, int top, int w, int h) {
        String text;
        try {
            text = Files.readString(p, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            g.drawString(this.font, "读取失败", x + 6, top + 6, COL_BAD, false);
            return;
        }
        String[] lines = text.split("\n", -1);
        int lineH = 10;
        int maxLines = (h - 10) / lineH;
        int first = Math.round(textScroll);
        g.enableScissor(x + 1, top + 1, x + w - 1, top + h - 1);
        for (int i = 0; i < maxLines && first + i < lines.length; i++) {
            String line = lines[first + i].replace("\t", "    ");
            g.drawString(this.font, this.font.plainSubstrByWidth(line, w - 12),
                    x + 5, top + 4 + i * lineH, COL_TEXT, false);
        }
        g.disableScissor();
        g.drawString(this.font, (first + 1) + "/" + lines.length, x + 4, top + h - 12, COL_DIM, false);
    }

    private void drawRight(GuiGraphics g, int mouseX, int mouseY, int top, int bottom) {
        int x = this.width - RIGHT_W - 6;
        g.fill(x, top - 14, x + RIGHT_W, bottom, COL_PANEL);
        g.fill(x, top - 14, x + RIGHT_W, top, COL_HEAD);
        g.drawString(this.font, Component.translatable("gui.deepspacedeployer.editor_info"),
                x + 4, top - 11, COL_TEXT, false);
        drawBorder(g, x, top - 14, RIGHT_W, bottom - top + 14, COL_BORDER);

        Path p = selectedPath();
        int y = top + 4;
        if (p == null) {
            g.drawString(this.font, "—", x + 5, y, COL_DIM, false);
            return;
        }
        g.drawString(this.font, this.font.plainSubstrByWidth(p.getFileName().toString(), RIGHT_W - 10),
                x + 5, y, COL_TEXT, false);
        y += 12;
        g.drawString(this.font, "路径：" + relOf(p), x + 5, y, COL_DIM, false);
        y += 11;
        try {
            if (Files.isRegularFile(p)) {
                g.drawString(this.font, "大小：" + Files.size(p) + " B", x + 5, y, COL_DIM, false);
            }
        } catch (Throwable ignored) {
            // 读不到大小就不显示
        }
        y += 14;
        g.drawString(this.font, Component.translatable("gui.deepspacedeployer.editor_tip"),
                x + 5, y, COL_DIM, false);
        y += 10;
        g.drawString(this.font, Component.translatable("gui.deepspacedeployer.editor_tip2"),
                x + 5, y, COL_DIM, false);

        if (clipboard != null) {
            g.drawString(this.font, "剪贴板：" + (clipboardCut ? "剪切 " : "复制 ")
                            + clipboard.getFileName(), x + 5, bottom - 16, COL_ACCENT, false);
        }

        // ---------------- 组件面板（选中 html 时才出现） ----------------
        if (compFile != null) {
            int cy = top + 74;
            g.drawString(this.font, Component.translatable("gui.deepspacedeployer.editor_components"),
                    x + 5, cy, COL_ACCENT, false);
            cy += 12;
            for (int i = 0; i < ADD_LABELS.length; i++) {
                addRects[i][0] = x + 5;
                addRects[i][1] = cy;
                addRects[i][2] = RIGHT_W - 12;
                addRects[i][3] = 14;
                boolean hover = mouseX >= addRects[i][0] && mouseX < addRects[i][0] + addRects[i][2]
                        && mouseY >= cy && mouseY < cy + 14;
                g.fill(addRects[i][0], cy, addRects[i][0] + addRects[i][2], cy + 14,
                        hover ? COL_ROW_HOVER : COL_ROW);
                drawBorder(g, addRects[i][0], cy, addRects[i][2], 14, COL_BORDER);
                g.drawString(this.font, ADD_LABELS[i], addRects[i][0] + 4, cy + 3, COL_TEXT, false);
                cy += 16;
            }

            if (sel != null) {
                cy += 4;
                g.drawString(this.font, "类型：" + sel.type, x + 5, cy, COL_TEXT, false);
                cy += 10;
                g.drawString(this.font, "x=" + sel.x + " y=" + sel.y
                        + " " + sel.w + "×" + sel.h, x + 5, cy, COL_DIM, false);
                cy += 10;
                g.drawString(this.font, "图层：" + sel.layer, x + 5, cy, COL_DIM, false);
                cy += 12;
                propRects[0][0] = x + 5; propRects[0][1] = cy; propRects[0][2] = 40; propRects[0][3] = 14;
                propRects[1][0] = x + 49; propRects[1][1] = cy; propRects[1][2] = 40; propRects[1][3] = 14;
                propRects[2][0] = x + 93; propRects[2][1] = cy; propRects[2][2] = 40; propRects[2][3] = 14;
                drawMini(g, propRects[0], "层+");
                drawMini(g, propRects[1], "层-");
                drawMini(g, propRects[2], "删除");
                if ("image".equals(sel.type)) {
                    cy += 16;
                    propRects[3][0] = x + 5; propRects[3][1] = cy;
                    propRects[3][2] = RIGHT_W - 12; propRects[3][3] = 14;
                    drawMini(g, propRects[3],
                            Component.translatable("gui.deepspacedeployer.editor_pick_image").getString());
                }

                cy += 16;
                propRects[4][0] = x + 5; propRects[4][1] = cy;
                propRects[4][2] = RIGHT_W - 12; propRects[4][3] = 14;
                drawMini(g, propRects[4], "动作：" + (sel.action == null || sel.action.isBlank()
                        ? "无" : sel.action));
            }
        }
    }

    private static final String[] ADD_LABELS = {"+ 文本", "+ 按钮", "+ 物品框", "+ 图片"};
    private static final String[] ADD_TYPES = {"text", "button", "slot", "image"};
    private final int[][] addRects = new int[4][4];
    private final int[][] propRects = new int[5][4];

    private void drawMini(GuiGraphics g, int[] r, String label) {
        g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], COL_ROW);
        drawBorder(g, r[0], r[1], r[2], r[3], COL_BORDER);
        g.drawCenteredString(this.font, label, r[0] + r[2] / 2, r[1] + 3, COL_TEXT);
    }

    /** 右栏组件按钮的命中；返回 true = 处理了 */
    private boolean compPanelClicked(double mouseX, double mouseY) {
        if (compFile == null) {
            return false;
        }
        for (int i = 0; i < ADD_LABELS.length; i++) {
            if (hit(addRects[i], mouseX, mouseY)) {
                addComp(ADD_TYPES[i]);
                return true;
            }
        }
        if (sel != null) {
            if (hit(propRects[0], mouseX, mouseY)) {
                sel.layer++;
                return true;
            }
            if (hit(propRects[1], mouseX, mouseY)) {
                sel.layer = Math.max(-1, sel.layer - 1);
                return true;
            }
            if (hit(propRects[2], mouseX, mouseY)) {
                comps.remove(sel);
                sel = null;
                return true;
            }
            if (hit(propRects[4], mouseX, mouseY)) {
                sel.action = nextAction(sel.action);
                return true;
            }
            if ("image".equals(sel.type) && hit(propRects[3], mouseX, mouseY)) {
                ThemeFilePicker.pickImage();      // 系统文件选择器（线程里弹，结果下一帧取）
                return true;
            }
        }
        return false;
    }

    /** 可选动作（都是宿主 onThemeAction 认识的；空串 = 纯摆设） */
    private static final String[] ACTIONS = {
            "", "close", "switch_page", "page_prev", "page_next",
            "toggle_autosell", "sell_now", "open_transfer", "assemble",
            "pick_target", "post", "confirm", "center", "bind", "rename"
    };

    private static String nextAction(String current) {
        int at = 0;
        for (int i = 0; i < ACTIONS.length; i++) {
            if (ACTIONS[i].equals(current == null ? "" : current)) {
                at = i;
                break;
            }
        }
        return ACTIONS[(at + 1) % ACTIONS.length];
    }

    private static boolean hit(int[] r, double mx, double my) {
        return r[2] > 0 && mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
    }

    private void drawContextMenu(GuiGraphics g, int mouseX, int mouseY) {
        int w = 70;
        int h = menuItems.length * 14 + 4;
        g.fill(menuX, menuY, menuX + w, menuY + h, 0xF0181D2A);
        drawBorder(g, menuX, menuY, w, h, COL_BORDER);
        for (int i = 0; i < menuItems.length; i++) {
            int y = menuY + 2 + i * 14;
            boolean hover = mouseX >= menuX && mouseX < menuX + w && mouseY >= y && mouseY < y + 14;
            if (hover) {
                g.fill(menuX + 1, y, menuX + w - 1, y + 14, COL_ROW_HOVER);
            }
            boolean disabled = menuItems[i].equals("粘贴") && clipboard == null;
            g.drawString(this.font, menuItems[i], menuX + 6, y + 3,
                    disabled ? COL_DIM : COL_TEXT, false);
        }
    }

    private void drawRenameDialog(GuiGraphics g, int mouseX, int mouseY) {
        int w = 220;
        int h = 70;
        int x = (this.width - w) / 2;
        int y = (this.height - h) / 2;
        g.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0xF0000000);
        g.fill(x, y, x + w, y + h, COL_PANEL);
        drawBorder(g, x, y, w, h, COL_ACCENT);
        g.drawString(this.font, Component.translatable("gui.deepspacedeployer.editor_rename"),
                x + 8, y + 8, COL_TEXT, false);
        if (renameBox != null) {
            renameBox.setX(x + 8);
            renameBox.setY(y + 24);
            renameBox.setWidth(w - 16);
            renameBox.render(g, mouseX, mouseY, 0f);
        }
        for (Renderable r : this.renderables) {
            r.render(g, mouseX, mouseY, 0f);
        }
    }

    private static void drawBorder(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    // ---------------------------------------------------------------- 交互

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (renaming) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        // 右键菜单优先
        if (menuX >= 0) {
            int w = 70;
            int h = menuItems.length * 14 + 4;
            if (mouseX >= menuX && mouseX < menuX + w && mouseY >= menuY && mouseY < menuY + h) {
                int idx = (int) ((mouseY - menuY - 2) / 14);
                if (idx >= 0 && idx < menuItems.length && button == 0) {
                    runMenu(menuItems[idx]);
                }
            }
            menuX = -1;
            return true;
        }

        int top = 26;
        int bottom = this.height - 32;
        if (mouseX >= 6 && mouseX < 6 + LEFT_W && mouseY >= top && mouseY < bottom) {
            int rowIndex = rowIndexAt(mouseY);
            if (rowIndex == -2) {                    // ".."
                dir = parentDir(dir);
                reloadEntries();
                return true;
            }
            if (rowIndex >= 0) {
                selected = rowIndex;
                textScroll = 0;
                Path p = entries.get(rowIndex);
                if (button == 1) {                   // 右键 → 菜单
                    menuX = (int) mouseX;
                    menuY = (int) mouseY;
                    return true;
                }
                if (button == 0 && Files.isDirectory(p)) {
                    dir = relOf(p);
                    reloadEntries();
                    return true;
                }
                if (button == 0 && Files.isRegularFile(p)) {
                    openSelectedFile(p);
                    return true;
                }
            }
            return true;
        }
        // 中栏：组件层（拖动 / 选中 / 拉伸）
        if (compMouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        // 右栏：组件面板按钮
        if (button == 0 && compPanelClicked(mouseX, mouseY)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (compFile != null && (dragging || resizing || draggingNode || resizingNode)) {
            compMouseDragged(mouseX, mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (compFile != null) {
            compMouseReleased();
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** 命中的行（-1 = 空白，-2 = ".."） */
    private int rowIndexAt(double mouseY) {
        int idx = (int) ((mouseY - 26 - 2 + scroll) / ROW_H);
        if (!dir.isEmpty()) {
            if (idx == 0) {
                return -2;
            }
            idx -= 1;
        }
        return idx >= 0 && idx < entries.size() ? idx : -1;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int top = 26;
        int bottom = this.height - 32;
        if (mouseY < top || mouseY > bottom) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        if (mouseX < 6 + LEFT_W) {
            int rows = entries.size() + (dir.isEmpty() ? 0 : 1);
            float max = Math.max(0, rows * ROW_H - (bottom - top - 4));
            scroll = (float) Math.max(0, Math.min(max, scroll - scrollY * 12));
            return true;
        }
        Path p = selectedPath();
        if (p != null && Files.isRegularFile(p)) {
            String name = p.getFileName().toString().toLowerCase();
            if (!name.endsWith(".png") && !name.endsWith(".jpg") && !name.endsWith(".jpeg")
                    && !name.endsWith(".html") && !name.endsWith(".htm")) {
                textScroll = (float) Math.max(0, textScroll - scrollY * 2);
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ---------------------------------------------------------------- 文本编辑

    /** 能不能直接用文本编辑器改（html 只预览、不改——它由组件面板负责） */
    private static boolean isTextEditable(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        return n.endsWith(".json") || n.endsWith(".css") || n.endsWith(".txt")
                || n.endsWith(".md") || n.endsWith(".js");
    }

    /** 选中一个文件：文本类就载入编辑器，html 进入组件编辑，其它清掉编辑状态 */
    private void openSelectedFile(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        if (n.endsWith(".html") || n.endsWith(".htm")) {
            editingFile = null;
            buffer = null;
            dirty = false;
            loadComps(p);
            rebuildFooter();
            return;
        }
        compFile = null;
        comps.clear();
        sel = null;
        if (isTextEditable(p)) {
            try {
                buffer = new ThemeTextBuffer();
                buffer.setText(Files.readString(p, StandardCharsets.UTF_8));
                editingFile = p;
                dirty = false;
                rebuildFooter();
                return;
            } catch (Throwable t) {
                DeepSpaceDeployer.LOGGER.warn("[深空铸造体][编辑器] 读取文本失败：{}", t.toString());
            }
        }
        if (editingFile != null) {
            editingFile = null;
            buffer = null;
            dirty = false;
            rebuildFooter();
        }
    }

    /** 保存当前编辑的文本文件 */
    private void saveFile() {
        if (editingFile == null || buffer == null) {
            return;
        }
        try {
            Files.writeString(editingFile, buffer.toText(), StandardCharsets.UTF_8);
            dirty = false;
            rebuildFooter();
            refreshLiveTheme();
            DeepSpaceDeployer.LOGGER.info("[深空铸造体][编辑器] 已保存：{}", relOf(editingFile));
        } catch (Throwable t) {
            DeepSpaceDeployer.LOGGER.warn("[深空铸造体][编辑器] 保存失败：{}", t.toString());
        }
    }

    /** 存的是"当前启用的主题"的样式时，让游戏内界面立刻跟着变 */
    private void refreshLiveTheme() {
        try {
            if (themeName.equals(com.deepspacedeployer.ui.theme.ThemeManager.activeName())) {
                WebViewManager.markThemesDirty();
            }
        } catch (Throwable ignored) {
            // 刷新失败不影响保存
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (renaming) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (editingFile == null || buffer == null) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        boolean ctrl = (modifiers & 0x2) != 0;          // GLFW_MOD_CONTROL
        switch (keyCode) {
            case org.lwjgl.glfw.GLFW.GLFW_KEY_S -> {
                if (ctrl) {
                    saveFile();
                    return true;
                }
            }
            case org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE -> {
                buffer.backspace();
                dirty = true;
                return true;
            }
            case org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE -> {
                buffer.delete();
                dirty = true;
                return true;
            }
            case org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER,
                 org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER -> {
                buffer.newline();
                dirty = true;
                return true;
            }
            case org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT -> {
                buffer.moveLeft();
                return true;
            }
            case org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT -> {
                buffer.moveRight();
                return true;
            }
            case org.lwjgl.glfw.GLFW.GLFW_KEY_UP -> {
                buffer.moveUp();
                return true;
            }
            case org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN -> {
                buffer.moveDown();
                return true;
            }
            case org.lwjgl.glfw.GLFW.GLFW_KEY_HOME -> {
                buffer.home();
                return true;
            }
            case org.lwjgl.glfw.GLFW.GLFW_KEY_END -> {
                buffer.end();
                return true;
            }
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
        // 走到这里只有一种情况：按了 S 但没按 Ctrl
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (renaming) {
            return super.charTyped(codePoint, modifiers);
        }
        if (editingFile == null || buffer == null) {
            return super.charTyped(codePoint, modifiers);
        }
        buffer.insert(codePoint);
        dirty = true;
        return true;
    }

    // ---------------------------------------------------------------- 组件层（第 3 步）

    /** 画组件：按 layer 从小到大，选中项画边框 + 右下角拉伸把手 */
    private void drawComponents(GuiGraphics g) {
        List<Comp> sorted = new ArrayList<>(comps);
        sorted.sort(Comparator.comparingInt(c -> c.layer));
        for (Comp c : sorted) {
            int cx = canvasX + c.x;
            int cy = canvasY + c.y;
            switch (c.type) {
                case "button" -> {
                    g.fill(cx, cy, cx + c.w, cy + c.h, 0xFF1B2233);
                    drawBorder(g, cx, cy, c.w, c.h, COL_ACCENT);
                    g.drawString(this.font, this.font.plainSubstrByWidth(c.text, c.w - 6),
                            cx + 3, cy + (c.h - 8) / 2, COL_TEXT, false);
                }
                case "slot" -> {
                    g.fill(cx, cy, cx + c.w, cy + c.h, 0xFF0C0C12);
                    drawBorder(g, cx, cy, c.w, c.h, COL_BORDER);
                }
                case "image" -> {
                    g.fill(cx, cy, cx + c.w, cy + c.h, 0xFF14161F);
                    drawBorder(g, cx, cy, c.w, c.h, COL_DIM);
                    g.drawCenteredString(this.font, "图片", cx + c.w / 2, cy + c.h / 2 - 4, COL_DIM);
                }
                default -> g.drawString(this.font, this.font.plainSubstrByWidth(c.text, c.w),
                        cx, cy, COL_TEXT, false);
            }
            if (c == sel) {
                drawBorder(g, cx - 1, cy - 1, c.w + 2, c.h + 2, COL_ACCENT);
                g.fill(cx + c.w - HANDLE / 2, cy + c.h - HANDLE / 2,
                        cx + c.w + HANDLE / 2, cy + c.h + HANDLE / 2, COL_ACCENT);
            }
        }
    }

    /** 组件层命中：从最上层往下找 */
    private Comp compAt(double mouseX, double mouseY) {
        List<Comp> sorted = new ArrayList<>(comps);
        sorted.sort((a, b) -> Integer.compare(b.layer, a.layer));
        for (Comp c : sorted) {
            if (mouseX >= canvasX + c.x && mouseX < canvasX + c.x + c.w
                    && mouseY >= canvasY + c.y && mouseY < canvasY + c.y + c.h) {
                return c;
            }
        }
        return null;
    }

    /** 点到了选中组件的右下角把手？ */
    private boolean onHandle(double mouseX, double mouseY) {
        if (sel == null) {
            return false;
        }
        int hx = canvasX + sel.x + sel.w;
        int hy = canvasY + sel.y + sel.h;
        return Math.abs(mouseX - hx) <= HANDLE && Math.abs(mouseY - hy) <= HANDLE;
    }

    /** 组件层的鼠标按下；返回 true = 已处理 */
    private boolean compMouseClicked(double mouseX, double mouseY, int button) {
        if (compFile == null || button != 0) {
            return false;
        }
        if (mouseX < canvasX || mouseX > canvasX + canvasW
                || mouseY < canvasY || mouseY > canvasY + canvasH) {
            return false;
        }
        if (onHandle(mouseX, mouseY)) {
            resizing = true;
            return true;
        }
        Comp hit = compAt(mouseX, mouseY);
        sel = hit;
        if (hit != null) {
            dragging = true;
            pickedNode = null;
            grabDx = (int) Math.round(mouseX) - (canvasX + hit.x);
            grabDy = (int) Math.round(mouseY) - (canvasY + hit.y);
            return true;
        }
        // 点中"已选中元素"的右下角把手 → 拉伸
        if (pickedNode != null && pickedNode.box != null) {
            int hx = canvasX + Math.round(pickedNode.box.x + pickedNode.box.w);
            int hy = canvasY + Math.round(pickedNode.box.y + pickedNode.box.h);
            if (Math.abs(mouseX - hx) <= HANDLE && Math.abs(mouseY - hy) <= HANDLE) {
                resizingNode = true;
                return true;
            }
        }
        // 没点到加进去的组件 → 选主题里已有的元素（可以拖动它）
        if (previewDoc != null && previewDoc.root != null) {
            var n = hitNode(previewDoc.root, (float) (mouseX - canvasX), (float) (mouseY - canvasY));
            pickedNode = n;
            draggingNode = n != null;
            if (n != null) {
                grabDx = (int) Math.round(mouseX) - (canvasX + Math.round(n.box.x));
                grabDy = (int) Math.round(mouseY) - (canvasY + Math.round(n.box.y));
            }
        }
        return true;
    }

    private void compMouseDragged(double mouseX, double mouseY) {
        if (compFile == null) {
            return;
        }
        if (resizingNode && pickedNode != null && pickedNode.box != null) {
            int nw = Math.max(8, (int) Math.round(mouseX) - canvasX - Math.round(pickedNode.box.x));
            int nh = Math.max(8, (int) Math.round(mouseY) - canvasY - Math.round(pickedNode.box.y));
            pickedNode.box.w = nw;
            pickedNode.box.h = nh;
            nodeEdits.put(pickedNode, new int[]{Math.round(pickedNode.box.x),
                    Math.round(pickedNode.box.y), nw, nh});
            return;
        }
        if (draggingNode && pickedNode != null && pickedNode.box != null) {
            int nx = (int) Math.round(mouseX) - canvasX - grabDx;
            int ny = (int) Math.round(mouseY) - canvasY - grabDy;
            pickedNode.box.x = nx;
            pickedNode.box.y = ny;
            nodeEdits.put(pickedNode, new int[]{nx, ny,
                    Math.round(pickedNode.box.w), Math.round(pickedNode.box.h)});
            return;
        }
        if (sel == null) {
            return;
        }
        if (dragging) {
            sel.x = Math.max(-sel.w + 4, (int) Math.round(mouseX) - canvasX - grabDx);
            sel.y = Math.max(-sel.h + 4, (int) Math.round(mouseY) - canvasY - grabDy);
        } else if (resizing) {
            sel.w = Math.max(8, (int) Math.round(mouseX) - canvasX - sel.x);
            sel.h = Math.max(8, (int) Math.round(mouseY) - canvasY - sel.y);
        }
    }

    private void compMouseReleased() {
        dragging = false;
        resizing = false;
        draggingNode = false;
        resizingNode = false;
    }

    /** 新增一个组件（摆到画布左上角，图层默认 0） */
    private void addComp(String type) {
        if (compFile == null) {
            return;
        }
        Comp c = new Comp();
        c.type = type;
        c.x = 40;
        c.y = 40;
        c.layer = comps.isEmpty() ? 0 : comps.get(comps.size() - 1).layer + 1;
        switch (type) {
            case "button" -> {
                c.w = 60;
                c.h = 18;
                c.text = "按钮";
            }
            case "slot" -> {
                c.w = 18;
                c.h = 18;
            }
            case "image" -> {
                c.w = 32;
                c.h = 32;
            }
            default -> {
                c.w = 80;
                c.h = 12;
                c.text = "文本";
            }
        }
        comps.add(c);
        sel = c;
    }

    /** 从 html 里读出 {@code data-editor} 组件 */
    private void loadComps(Path p) {
        comps.clear();
        sel = null;
        compFile = p;
        try {
            String t = Files.readString(p, StandardCharsets.UTF_8);
            var m = java.util.regex.Pattern
                    .compile("<div data-editor=\"([^\"]+)\"([^>]*)>([^<]*)</div>")
                    .matcher(t);
            while (m.find()) {
                Comp c = new Comp();
                c.type = m.group(1);
                String attrs = m.group(2);
                c.x = intAttr(attrs, "data-x", 10);
                c.y = intAttr(attrs, "data-y", 10);
                c.w = intAttr(attrs, "data-w", 80);
                c.h = intAttr(attrs, "data-h", 18);
                c.layer = intAttr(attrs, "data-layer", 0);
                c.text = m.group(3);
                c.src = strAttr(attrs, "data-src");
                c.action = strAttr(attrs, "data-action");
                comps.add(c);
            }
        } catch (Throwable t) {
            DeepSpaceDeployer.LOGGER.warn("[深空铸造体][编辑器] 读组件失败：{}", t.toString());
        }
    }

    private static int intAttr(String attrs, String name, int fallback) {
        var m = java.util.regex.Pattern.compile(name + "=\"(-?\\d+)\"").matcher(attrs);
        return m.find() ? Integer.parseInt(m.group(1)) : fallback;
    }

    private static String strAttr(String attrs, String name) {
        var m = java.util.regex.Pattern.compile(name + "=\"([^\"]*)\"").matcher(attrs);
        return m.find() ? m.group(1) : "";
    }

    /** 把组件写回 html（放在 <!-- editor:begin/end --> 之间，其余内容不动） */
    private void saveComps() {
        if (compFile == null) {
            return;
        }
        try {
            String t = Files.readString(compFile, StandardCharsets.UTF_8);
            t = t.replaceAll("(?s)\\s*<!-- editor:begin -->.*?<!-- editor:end -->", "");
            StringBuilder sb = new StringBuilder();
            sb.append("\n<!-- editor:begin -->\n");
            for (Comp c : comps) {
                sb.append("  <div data-editor=\"").append(c.type).append('"')
                        .append(" data-x=\"").append(c.x).append('"')
                        .append(" data-y=\"").append(c.y).append('"')
                        .append(" data-w=\"").append(c.w).append('"')
                        .append(" data-h=\"").append(c.h).append('"')
                        .append(" data-layer=\"").append(c.layer).append('"')
                        .append(" data-src=\"").append(c.src == null ? "" : c.src).append('"')
                        .append(c.action == null || c.action.isBlank() ? "" : " data-action=\"" + c.action + "\"")
                        .append(" style=\"").append(styleOf(c)).append("\">")
                        .append(c.text == null ? "" : c.text)
                        .append("</div>\n");
            }
            sb.append("<!-- editor:end -->\n");
            int at = t.lastIndexOf("</body>");
            if (at < 0) {
                at = t.length();
            }
            t = t.substring(0, at) + sb + t.substring(at);
            Files.writeString(compFile, t, StandardCharsets.UTF_8);
            int moved = saveNodeEdits();
            DeepSpaceDeployer.LOGGER.info("[深空铸造体][编辑器] 已保存 {} 个组件、{} 个元素位置到 {}",
                    comps.size(), moved, relOf(compFile));
            refreshLiveTheme();
        } catch (Throwable t) {
            DeepSpaceDeployer.LOGGER.warn("[深空铸造体][编辑器] 保存组件失败：{}", t.toString());
        }
    }

    /**
     * 把拖动过的已有元素写回源码：改它的开始标签里的 {@code style}。
     *
     * <p>从后往前改（{@code srcTagStart} 降序），这样前面的偏移不会被后面的修改带歪。
     * 只处理从源码解析出来的节点（{@code srcTagStart >= 0}），
     * {@code data-repeat} 克隆出来的节点没有源码位置，跳过。</p>
     *
     * @return 实际改了几个
     */
    private int saveNodeEdits() {
        if (compFile == null || nodeEdits.isEmpty()) {
            return 0;
        }
        int changed = 0;
        try {
            String src = Files.readString(compFile, StandardCharsets.UTF_8);
            var list = new ArrayList<>(nodeEdits.entrySet());
            list.removeIf(e -> e.getKey().srcTagStart < 0
                    || e.getKey().srcTagEnd <= e.getKey().srcTagStart);
            list.sort((a, b) -> Integer.compare(
                    b.getKey().srcTagStart, a.getKey().srcTagStart));
            for (var e : list) {
                var n = e.getKey();
                int[] v = e.getValue();
                if (n.srcTagEnd > src.length()) {
                    continue;
                }
                String tag = src.substring(n.srcTagStart, n.srcTagEnd);
                String style = "position:absolute;left:" + v[0] + ";top:" + v[1]
                        + ";width:" + v[2] + ";height:" + v[3];
                String newTag = setStyleAttr(tag, style);
                src = src.substring(0, n.srcTagStart) + newTag + src.substring(n.srcTagEnd);
                changed++;
            }
            if (changed > 0) {
                Files.writeString(compFile, src, StandardCharsets.UTF_8);
                nodeEdits.clear();
            }
        } catch (Throwable t) {
            DeepSpaceDeployer.LOGGER.warn("[深空铸造体][编辑器] 写回元素位置失败：{}", t.toString());
        }
        return changed;
    }

    /** 在开始标签里合并 style 属性（有就追加，没有就插在 '>' 前） */
    private static String setStyleAttr(String tag, String extra) {
        int close = tag.lastIndexOf('>');
        if (close < 0) {
            return tag;
        }
        int styleAt = tag.indexOf("style=\"");
        if (styleAt >= 0 && styleAt < close) {
            int valueStart = styleAt + 7;
            int valueEnd = tag.indexOf('"', valueStart);
            if (valueEnd > 0) {
                String old = tag.substring(valueStart, valueEnd);
                String merged = old.isBlank() ? extra : old + (";" ) + extra;
                return tag.substring(0, valueStart) + merged + tag.substring(valueEnd);
            }
        }
        String insert = " style=\"" + extra + "\"";
        return tag.substring(0, close) + insert + tag.substring(close);
    }

    /**
     * 一个组件的内联样式（写进 html，游戏里就按这个画）。
     *
     * <p>绝对定位 + 显式宽高，这样它不会参与文档流、也不会把别的元素挤走。
     * 图层用 {@code z-index}（渲染器支持的话就按它盖）——当前版本渲染器**不支持**
     * {@code z-index}，所以即使存了也不影响；等渲染器支持了立刻就生效。</p>
     */
    private static String styleOf(Comp c) {
        StringBuilder s = new StringBuilder();
        s.append("position:absolute;left:").append(c.x)
                .append(";top:").append(c.y)
                .append(";width:").append(c.w)
                .append(";height:").append(c.h)
                .append(";z-index:").append(c.layer);
        switch (c.type) {
            case "button" -> s.append(";background:#1B2233;border:1;border-color:#4C7DFF")
                    .append(";color:#E6ECF5");
            case "slot" -> s.append(";background:#0C0C12;border:1;border-color:#2A3550");
            case "image" -> {
                if (c.src != null && !c.src.isBlank()) {
                    s.append(";background-image:url('").append(c.src).append("')");
                } else {
                    s.append(";background:#14161F");
                }
                s.append(";border:1;border-color:#8A97AD");
            }
            default -> s.append(";color:#E6ECF5");
        }
        return s.toString();
    }

    // ---------------------------------------------------------------- 已有元素（第 5 步）

    /** 把拖动过的位置写回布局（只改屏幕坐标，不改文档流） */
    private void applyNodeEdits(com.deepspacedeployer.ui.theme.HtmlNode root) {
        if (root == null || nodeEdits.isEmpty()) {
            return;
        }
        root.walk(n -> {
            int[] e = nodeEdits.get(n);
            if (e != null && n.box != null) {
                n.box.x = e[0];
                n.box.y = e[1];
                n.box.w = e[2];
                n.box.h = e[3];
            }
        });
    }

    /** 画选中元素的框 + 标签 */
    private void drawPickedNode(GuiGraphics g) {
        var n = pickedNode;
        if (n == null || n.box == null) {
            return;
        }
        int bx = canvasX + Math.round(n.box.x);
        int by = canvasY + Math.round(n.box.y);
        int bw = Math.max(2, Math.round(n.box.w));
        int bh = Math.max(2, Math.round(n.box.h));
        drawBorder(g, bx - 1, by - 1, bw + 2, bh + 2, 0xFFFFC24B);
        String label = (n.tag == null || n.tag.isEmpty() ? "?" : n.tag) + " " + bw + "×" + bh;
        g.drawString(this.font, label, bx, Math.max(canvasY, by - 10), 0xFFFFC24B, false);
    }

    /** 在渲染结果里找鼠标下的元素（子元素优先、后画的优先） */
    private com.deepspacedeployer.ui.theme.HtmlNode hitNode(
            com.deepspacedeployer.ui.theme.HtmlNode node, float px, float py) {
        if (node == null || node.box == null || !node.box.visible) {
            return null;
        }
        for (int i = node.children.size() - 1; i >= 0; i--) {
            var hit = hitNode(node.children.get(i), px, py);
            if (hit != null) {
                return hit;
            }
        }
        var b = node.box;
        if (px >= b.x && px < b.x + b.w && py >= b.y && py < b.y + b.h) {
            return node;
        }
        return null;
    }

    // ---------------------------------------------------------------- 文件操作

    private void runMenu(String item) {
        Path p = selectedPath();
        if (p == null) {
            return;
        }
        try {
            switch (item) {
                case "复制" -> {
                    clipboard = p;
                    clipboardCut = false;
                }
                case "剪切" -> {
                    clipboard = p;
                    clipboardCut = true;
                }
                case "粘贴" -> pasteInto(p.getParent() == null ? themeDir() : p.getParent());
                case "重命名" -> startRename(p);
                case "删除" -> {
                    deleteRecursive(p);
                    reloadEntries();
                }
                default -> {
                }
            }
        } catch (Throwable t) {
            DeepSpaceDeployer.LOGGER.warn("[深空铸造体][编辑器] 文件操作失败（{}）：{}", item, t.toString());
        }
    }

    private void pasteInto(Path targetDir) throws Exception {
        if (clipboard == null || !Files.exists(clipboard)) {
            return;
        }
        Path dest = targetDir.resolve(clipboard.getFileName().toString());
        if (Files.exists(dest)) {
            dest = targetDir.resolve("副本-" + clipboard.getFileName());
        }
        if (clipboardCut) {
            Files.move(clipboard, dest, StandardCopyOption.REPLACE_EXISTING);
            clipboard = null;
            clipboardCut = false;
        } else {
            copyRecursive(clipboard, dest);
        }
        reloadEntries();
    }

    private void startRename(Path p) {
        importSource = null;
        renameTarget = p;
        renaming = true;
        clearWidgets();
        renameBox = new EditBox(this.font, 0, 0, 200, 18,
                Component.translatable("gui.deepspacedeployer.editor_rename"));
        renameBox.setValue(p.getFileName().toString());
        renameBox.setMaxLength(120);
        addRenderableWidget(renameBox);
        addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.editor_save"),
                        b -> finishRename())
                .bounds(this.width / 2 - 100, this.height / 2 + 22, 96, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.editor_cancel"),
                        b -> {
                            renaming = false;
                            rebuildFooter();
                        })
                .bounds(this.width / 2 + 4, this.height / 2 + 22, 96, 20).build());
    }

    /**
     * 开始"导入资源"：把系统里选的一张图复制进主题根目录，再挂到选中的图片组件上。
     *
     * <p>输入框里**只显示不带后缀的文件名**（老板的要求），保存时自动补原后缀。</p>
     */
    private void startImport(Path src) {
        if (src == null || sel == null) {
            return;
        }
        importSource = src;
        renaming = true;
        clearWidgets();
        renameBox = new EditBox(this.font, 0, 0, 200, 18,
                Component.translatable("gui.deepspacedeployer.editor_import_title"));
        renameBox.setValue(baseName(src.getFileName().toString()));
        renameBox.setMaxLength(120);
        addRenderableWidget(renameBox);
        addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.editor_save"),
                        b -> finishRename())
                .bounds(this.width / 2 - 100, this.height / 2 + 22, 96, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.editor_cancel"),
                        b -> {
                            renaming = false;
                            importSource = null;
                            rebuildFooter();
                        })
                .bounds(this.width / 2 + 4, this.height / 2 + 22, 96, 20).build());
    }

    /** "a.b.png" → "a.b" */
    private static String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }

    /** "a.b.png" → ".png" */
    private static String extOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot);
    }

    private void finishRename() {
        try {
            String name = renameBox == null ? "" : renameBox.getValue().trim();
            if (importSource != null && sel != null) {
                // ★ 导入资源：复制到主题根目录，再把组件指向它
                String fileName = name + extOf(importSource.getFileName().toString());
                Path dest = themeDir().resolve(fileName);
                Files.copy(importSource, dest, StandardCopyOption.REPLACE_EXISTING);
                sel.src = fileName;
                ThemeImages.releaseAll();
                DeepSpaceDeployer.LOGGER.info("[深空铸造体][编辑器] 已导入资源：{}", fileName);
            } else if (!name.isEmpty() && renameTarget != null) {
                Path dest = renameTarget.resolveSibling(name);
                Files.move(renameTarget, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable t) {
            DeepSpaceDeployer.LOGGER.warn("[深空铸造体][编辑器] 重命名/导入失败：{}", t.toString());
        }
        importSource = null;
        renaming = false;
        renameBox = null;
        reloadEntries();
        rebuildFooter();
    }

    private void openInExplorer() {
        try {
            Path dir2 = themeDir();
            Files.createDirectories(dir2);
            Util.getPlatform().openFile(dir2.toFile());
        } catch (Throwable t) {
            DeepSpaceDeployer.LOGGER.warn("[深空铸造体][编辑器] 打开目录失败：{}", t.toString());
        }
    }

    private static void deleteRecursive(Path p) throws Exception {
        if (Files.isDirectory(p)) {
            try (Stream<Path> walk = Files.list(p)) {
                for (Path child : walk.toList()) {
                    deleteRecursive(child);
                }
            }
        }
        Files.deleteIfExists(p);
    }

    private static void copyRecursive(Path from, Path to) throws Exception {
        if (Files.isDirectory(from)) {
            Files.createDirectories(to);
            try (Stream<Path> walk = Files.list(from)) {
                for (Path child : walk.toList()) {
                    copyRecursive(child, to.resolve(child.getFileName().toString()));
                }
            }
        } else {
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String parentDir(String dir) {
        if (dir == null || dir.isEmpty()) {
            return "";
        }
        int slash = dir.lastIndexOf('/');
        return slash < 0 ? "" : dir.substring(0, slash);
    }

    /** 读 PNG 的 IHDR 拿宽高（不引第三方库；读不到返回 1×1） */
    private static int[] imageSize(Path p) {
        try {
            byte[] head = new byte[24];
            try (var in = Files.newInputStream(p)) {
                int n = in.read(head);
                if (n < 24 || head[0] != (byte) 0x89 || head[1] != 'P') {
                    return new int[]{1, 1};
                }
            }
            int w = ((head[16] & 0xFF) << 24) | ((head[17] & 0xFF) << 16)
                    | ((head[18] & 0xFF) << 8) | (head[19] & 0xFF);
            int h = ((head[20] & 0xFF) << 24) | ((head[21] & 0xFF) << 16)
                    | ((head[22] & 0xFF) << 8) | (head[23] & 0xFF);
            return new int[]{Math.max(1, w), Math.max(1, h)};
        } catch (Throwable t) {
            return new int[]{1, 1};
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
