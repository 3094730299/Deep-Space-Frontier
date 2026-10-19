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

    /** 文本预览的滚动 */
    private float textScroll;

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
                .bounds(this.width / 2 - 100, by, 96, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.editor_open_folder"),
                        b -> openInExplorer())
                .bounds(this.width / 2 + 4, by, 96, 20).build());
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
        } else {
            drawTextPreview(g, p, x, top, w, h);
        }
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
            GuiCanvas canvas = new GuiCanvas(g, x + 4, top + 4);
            LayoutEngine engine = new LayoutEngine(canvas);
            // 预览按界面本身的建议尺寸布局，再用左上角对齐（不做缩放，保持 1:1 像素）
            engine.layout(doc.root, Math.min(w - 8, 340), Math.min(h - 8, 340));
            ThemeRenderer renderer = new ThemeRenderer(source, canvas);
            renderer.render(doc.root);
            canvas.finishFrame();
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
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
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

    private void finishRename() {
        try {
            String name = renameBox.getValue().trim();
            if (!name.isEmpty() && renameTarget != null) {
                Path dest = renameTarget.resolveSibling(name);
                Files.move(renameTarget, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable t) {
            DeepSpaceDeployer.LOGGER.warn("[深空铸造体][编辑器] 重命名失败：{}", t.toString());
        }
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
