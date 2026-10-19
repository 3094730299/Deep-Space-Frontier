package com.deepspacedeployer.ui.webview;

import com.deepspacedeployer.DeepSpaceDeployer;
import com.deepspacedeployer.ui.theme.ThemeManager;
import com.deepspacedeployer.ui.theme.ThemeMeta;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>界面主题选择</b>（原生实现，样式参考 Minecraft 的资源包界面）。
 *
 * <p>老板 2026-10-18 的要求：</p>
 * <ul>
 *   <li><b>左右两栏</b>：左边 = 未启用（可用），右边 = 已启用。点左边的加到右边，点右边的移回去；</li>
 *   <li>每套主题显示 <b>图标 / 名称 / 版本 / 作者 / 介绍</b>（来自 {@code index.json}，
 *       图标是新增的 {@code icon} 字段，相对主题文件夹）；</li>
 *   <li>列表下方有<b>「高级界面设置」</b>：可以给每个界面单独指定主题；</li>
 *   <li>规则：启用其他主题 → 清空高级配置（所有界面由它渲染）；
 *       禁用某个主题 → 它负责的界面回落到 {@code default}。</li>
 * </ul>
 *
 * <p><b>为什么不用主题里的 {@code choice.html} 画</b>：这个界面需要滚动列表、
 * 多选、下拉这种交互，自研的 HTML 子集做不可靠（也不想为了它把渲染器堆成浏览器）。
 * 所以主题选择界面用原生控件画，配色照默认主题来。
 * {@code choice.html} 仍保留在默认主题里（离线预览 / 主题作者参考用），游戏内不再加载它。</p>
 *
 * <p>主题加载/读取任何一步出错都只会少显示一套主题，界面本身永远能打开。</p>
 */
public class ThemeChoiceScreen extends Screen {

    // ---------------- 配色（照默认主题的深色工业风） ----------------
    private static final int COL_PANEL = 0xF012161F;
    private static final int COL_PANEL_HEAD = 0xFF1B2233;
    private static final int COL_BORDER = 0xFF2A3550;
    private static final int COL_ROW = 0xFF161C28;
    private static final int COL_ROW_HOVER = 0xFF22304A;
    private static final int COL_TEXT = 0xFFE6ECF5;
    private static final int COL_DIM = 0xFF8A97AD;
    private static final int COL_ACCENT = 0xFF4C7DFF;
    private static final int COL_WARN = 0xFFFFC24B;

    private static final int ROW_H = 44;
    private static final int ICON = 32;
    private static final int HEAD_H = 18;

    /** 左栏（可启用）/ 右栏（已启用）/ 高级界面设置 */
    private enum Mode { LIST, ASSIGN }

    private Mode mode = Mode.LIST;

    private int paneTop;
    private int paneH;
    private int paneW;
    private int leftX;
    private int rightX;

    private float scrollLeft;
    private float scrollRight;

    /** 高级设置里正在编辑的内容：界面 key → 主题名（不点"应用"不落盘） */
    private final Map<String, String> pending = new LinkedHashMap<>();

    /**
     * 当前<b>选中</b>的主题（用来亮起「编辑」按钮）。
     *
     * <p>点左边/右边的任意一行 = 选中它（同时按资源包规则启用/禁用）。
     * 选中和启用状态是两回事：可以选中一个"未启用"的包去编辑它。</p>
     */
    private String selectedTheme;

    public ThemeChoiceScreen() {
        super(Component.translatable("gui.deepspacedeployer.theme_title"));
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void init() {
        super.init();
        paneTop = 34;
        paneH = Math.max(60, this.height - paneTop - 62);
        paneW = Math.max(120, Math.min(210, (this.width - 28) / 2));
        leftX = this.width / 2 - paneW - 4;
        rightX = this.width / 2 + 4;
        rebuildButtons();
    }

    /** 按当前模式重建底部按钮 */
    private void rebuildButtons() {
        clearWidgets();
        int by = this.height - 26;
        if (mode == Mode.LIST) {
            boolean anySelected = selectedTheme != null;
            Button edit = Button.builder(
                            Component.translatable("gui.deepspacedeployer.theme_edit"),
                            b -> editSelected())
                    .bounds(this.width / 2 - 84, by, 76, 20).build();
            // 选中一个包（不管启用/未启用）之后才亮起
            edit.active = anySelected;
            addRenderableWidget(edit);

            addRenderableWidget(Button.builder(
                            Component.translatable("gui.deepspacedeployer.theme_advanced"),
                            b -> {
                                pending.clear();
                                pending.putAll(cleanAssignments());
                                mode = Mode.ASSIGN;
                                rebuildButtons();
                            })
                    .bounds(this.width / 2 - 206, by, 120, 20).build());

            addRenderableWidget(Button.builder(
                            Component.translatable("gui.deepspacedeployer.theme_open_folder"),
                            b -> openThemeFolder())
                    .bounds(this.width / 2 - 4, by, 88, 20).build());

            addRenderableWidget(Button.builder(
                            Component.translatable("gui.deepspacedeployer.theme_done"),
                            b -> this.onClose())
                    .bounds(this.width / 2 + 88, by, 60, 20).build());
        } else {
            addRenderableWidget(Button.builder(
                            Component.translatable("gui.deepspacedeployer.theme_apply"),
                            b -> applyAssignments())
                    .bounds(this.width / 2 - 152, by, 96, 20).build());

            addRenderableWidget(Button.builder(
                            Component.translatable("gui.deepspacedeployer.theme_reset"),
                            b -> {
                                pending.clear();
                                rebuildButtons();
                            })
                    .bounds(this.width / 2 - 50, by, 96, 20).build());

            addRenderableWidget(Button.builder(
                            Component.translatable("gui.deepspacedeployer.theme_cancel"),
                            b -> {
                                pending.clear();
                                mode = Mode.LIST;
                                rebuildButtons();
                            })
                    .bounds(this.width / 2 + 52, by, 96, 20).build());
        }
    }

    @Override
    public void onClose() {
        ThemeIcons.releaseAll();
        super.onClose();
    }

    // ---------------------------------------------------------------- 数据

    /** 可用主题（全部） */
    private List<String> available() {
        try {
            return ThemeManager.availableThemes();
        } catch (Throwable t) {
            return List.of(ThemeManager.DEFAULT_THEME);
        }
    }

    /** 已启用主题 */
    private List<String> enabled() {
        try {
            return ThemeManager.enabledThemes();
        } catch (Throwable t) {
            return List.of(ThemeManager.DEFAULT_THEME);
        }
    }

    /** 未启用 = 全部 - 已启用（保持目录顺序） */
    private List<String> notEnabled() {
        List<String> en = enabled();
        List<String> out = new ArrayList<>();
        for (String n : available()) {
            if (!en.contains(n)) {
                out.add(n);
            }
        }
        return out;
    }

    /** 已落到盘上的高级配置（过滤掉不存在的主题） */
    private Map<String, String> cleanAssignments() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            List<String> av = available();
            for (var e : ThemeManager.assignments().entrySet()) {
                if (av.contains(e.getValue())) {
                    out.put(e.getKey(), e.getValue());
                }
            }
        } catch (Throwable ignored) {
            // 读不到就当没有
        }
        return out;
    }

    private void applyAssignments() {
        try {
            Map<String, String> assign = new LinkedHashMap<>();
            for (var e : pending.entrySet()) {
                if (e.getValue() != null && !e.getValue().equals(ThemeManager.DEFAULT_THEME)) {
                    assign.put(e.getKey(), e.getValue());
                }
            }
            ThemeManager.setAssignments(assign);
            // 被指定的主题自动"启用"
            List<String> list = enabled();
            for (String t : assign.values()) {
                if (!list.contains(t)) {
                    list.add(t);
                }
            }
            ThemeManager.setEnabledThemes(list);
            DeepSpaceDeployer.LOGGER.info("[深空铸造体][主题] 已应用高级界面设置：{}", assign);
        } catch (Throwable t) {
            DeepSpaceDeployer.LOGGER.warn("[深空铸造体][主题] 保存高级界面设置失败：{}", t.toString());
        }
        mode = Mode.LIST;
        rebuildButtons();
    }

    private void openThemeFolder() {
        try {
            Path dir = ThemeManager.root();
            Files.createDirectories(dir);
            Util.getPlatform().openFile(dir.toFile());
        } catch (Throwable t) {
            DeepSpaceDeployer.LOGGER.warn("[深空铸造体][主题] 打开主题目录失败：{}", t.toString());
        }
    }

    // ---------------------------------------------------------------- 渲染

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g, mouseX, mouseY, partialTick);   // 压暗世界（原版资源包界面也是这样）
        if (mode == Mode.LIST) {
            drawListMode(g, mouseX, mouseY);
        } else {
            drawAssignMode(g, mouseX, mouseY);
        }
        for (Renderable r : this.renderables) {
            r.render(g, mouseX, mouseY, partialTick);
        }
    }

    private void drawListMode(GuiGraphics g, int mouseX, int mouseY) {
        g.drawCenteredString(this.font, this.title, this.width / 2, 12, COL_TEXT);

        List<String> left = notEnabled();
        List<String> right = enabled();

        clampScroll(left, right);
        drawPane(g, leftX, Component.translatable("gui.deepspacedeployer.theme_available"),
                left, (int) scrollLeft, mouseX, mouseY, true);
        drawPane(g, rightX, Component.translatable("gui.deepspacedeployer.theme_enabled_pane"),
                right, (int) scrollRight, mouseX, mouseY, false);

        // 提示：正在用自定义配置
        int hintY = this.height - 42;
        if (ThemeManager.hasAssignments()) {
            g.drawCenteredString(this.font,
                    Component.translatable("gui.deepspacedeployer.theme_custom_hint"),
                    this.width / 2, hintY, COL_WARN);
        }
    }

    private void drawPane(GuiGraphics g, int x, Component head, List<String> names,
                          int scroll, int mouseX, int mouseY, boolean fromLeft) {
        g.fill(x, paneTop - HEAD_H, x + paneW, paneTop + paneH, COL_PANEL);
        g.fill(x, paneTop - HEAD_H, x + paneW, paneTop, COL_PANEL_HEAD);
        g.drawString(this.font, head, x + 6, paneTop - HEAD_H + 5, COL_TEXT, false);
        drawBorder(g, x, paneTop - HEAD_H, paneW, paneH + HEAD_H, COL_BORDER);

        if (names.isEmpty()) {
            Component empty = Component.translatable(fromLeft
                    ? "gui.deepspacedeployer.theme_all_enabled"
                    : "gui.deepspacedeployer.theme_none_enabled");
            g.drawCenteredString(this.font, empty, x + paneW / 2, paneTop + paneH / 2 - 4, COL_DIM);
            return;
        }

        for (int i = 0; i < names.size(); i++) {
            int rowY = paneTop + i * ROW_H - scroll;
            if (rowY + ROW_H < paneTop || rowY > paneTop + paneH) {
                continue;                                   // 不在可视区
            }
            boolean hover = mouseX >= x && mouseX < x + paneW
                    && mouseY >= rowY && mouseY < rowY + ROW_H
                    && mouseY >= paneTop && mouseY < paneTop + paneH;
            // 裁到列表框里：滚动到一半的行不要画到表头/表格外面
            g.enableScissor(x + 1, paneTop, x + paneW - 1, paneTop + paneH);
            drawRow(g, x, rowY, names.get(i), hover);
            g.disableScissor();
        }
    }

    /** 一行主题：图标 + 名称 + 版本 + 作者 + 介绍 */
    private void drawRow(GuiGraphics g, int x, int y, String theme, boolean hover) {
        g.fill(x + 1, y + 1, x + paneW - 1, y + ROW_H - 1, hover ? COL_ROW_HOVER : COL_ROW);
        drawBorder(g, x + 1, y + 1, paneW - 2, ROW_H - 2, hover ? COL_ACCENT : COL_BORDER);

        ThemeMeta meta = meta(theme);
        int iconX = x + 6;
        int iconY = y + 6;
        drawIcon(g, theme, iconX, iconY);

        int textX = iconX + ICON + 6;
        int textW = x + paneW - textX - 6;

        String name = meta.displayName(theme);
        String version = meta.version().isBlank() ? "" : "v" + meta.version();
        int verW = version.isEmpty() ? 0 : this.font.width(version);
        g.drawString(this.font, this.font.plainSubstrByWidth(name, Math.max(10, textW - verW - 4)),
                textX, y + 5, COL_TEXT, false);
        if (!version.isEmpty()) {
            g.drawString(this.font, version, x + paneW - 6 - verW, y + 5, COL_DIM, false);
        }

        String author = meta.author().isBlank()
                ? Component.translatable("gui.deepspacedeployer.theme_unknown_author").getString()
                : meta.author();
        g.drawString(this.font, this.font.plainSubstrByWidth(author, textW), textX, y + 16, COL_DIM, false);

        if (!meta.description().isBlank()) {
            g.drawString(this.font, this.font.plainSubstrByWidth(meta.description(), textW),
                    textX, y + 27, COL_DIM, false);
        }

        // 内置标记
        if (ThemeManager.DEFAULT_THEME.equals(theme)) {
            Component tag = Component.translatable("gui.deepspacedeployer.theme_builtin");
            int tw = this.font.width(tag);
            g.drawString(this.font, tag, x + paneW - 6 - tw, y + ROW_H - 13, COL_ACCENT, false);
        }
    }

    /** 画图标；没有图标就画一个带首字母的占位方块 */
    private void drawIcon(GuiGraphics g, String theme, int x, int y) {
        ResourceLocation loc = ThemeIcons.get(theme);
        if (loc != null) {
            g.blit(loc, x, y, 0f, 0f, ICON, ICON, ICON, ICON);
            return;
        }
        g.fill(x, y, x + ICON, y + ICON, COL_PANEL_HEAD);
        drawBorder(g, x, y, ICON, ICON, COL_BORDER);
        String letter = meta(theme).displayName(theme);
        if (!letter.isEmpty()) {
            letter = letter.substring(0, 1);
        }
        g.drawCenteredString(this.font, letter, x + ICON / 2, y + ICON / 2 - 4, COL_DIM);
    }

    private void drawAssignMode(GuiGraphics g, int mouseX, int mouseY) {
        g.drawCenteredString(this.font,
                Component.translatable("gui.deepspacedeployer.theme_advanced_title"),
                this.width / 2, 12, COL_TEXT);
        g.drawCenteredString(this.font,
                Component.translatable("gui.deepspacedeployer.theme_advanced_hint"),
                this.width / 2, 24, COL_DIM);

        // 两列 × 5 行：9 个可主题化界面在小窗口（GUI 高度 240）也放得下，不用滚动
        WebScreenId[] ids = WebScreenId.themeable();
        int perCol = (ids.length + 1) / 2;                 // 5
        int rowH = 18;
        int colW = Math.min(170, (this.width - 20) / 2);
        int x0 = this.width / 2 - colW - 4;
        int x1 = this.width / 2 + 4;
        int top = 40;
        int boxH = perCol * rowH + 8;

        for (int col = 0; col < 2; col++) {
            int x = col == 0 ? x0 : x1;
            g.fill(x, top - 4, x + colW, top - 4 + boxH, COL_PANEL);
            drawBorder(g, x, top - 4, colW, boxH, COL_BORDER);
        }

        for (int i = 0; i < ids.length; i++) {
            WebScreenId id = ids[i];
            int col = i / perCol;
            int row = i % perCol;
            int x = col == 0 ? x0 : x1;
            int y = top + row * rowH;
            String theme = pending.getOrDefault(id.key(), ThemeManager.DEFAULT_THEME);
            g.drawString(this.font, id.cnName(), x + 6, y + 5, COL_TEXT, false);
            String label = Component.translatable("gui.deepspacedeployer.theme_assign_value", theme)
                    .getString();
            int lw = this.font.width(label);
            g.drawString(this.font, label, x + colW - 6 - lw, y + 5,
                    ThemeManager.DEFAULT_THEME.equals(theme) ? COL_DIM : COL_ACCENT, false);
        }
    }

    private static void drawBorder(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    private ThemeMeta meta(String theme) {
        try {
            return ThemeManager.metaOf(theme);
        } catch (Throwable t) {
            return ThemeMeta.EMPTY;
        }
    }

    // ---------------------------------------------------------------- 交互

    private void clampScroll(List<String> left, List<String> right) {
        float maxL = Math.max(0, left.size() * ROW_H - paneH);
        float maxR = Math.max(0, right.size() * ROW_H - paneH);
        scrollLeft = Math.max(0f, Math.min(scrollLeft, maxL));
        scrollRight = Math.max(0f, Math.min(scrollRight, maxR));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mode == Mode.LIST) {
            int row = rowAt(mouseX, mouseY, leftX, (int) scrollLeft, notEnabled().size());
            if (row >= 0) {
                enable(notEnabled().get(row));
                return true;
            }
            int rowR = rowAt(mouseX, mouseY, rightX, (int) scrollRight, enabled().size());
            if (rowR >= 0) {
                disable(enabled().get(rowR));
                return true;
            }
        } else if (button == 0 && mode == Mode.ASSIGN) {
            int idx = assignRowAt(mouseX, mouseY);
            if (idx >= 0 && idx < WebScreenId.all().length) {
                cycleAssignment(WebScreenId.all()[idx]);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 命中左/右栏的第几行（没命中返回 -1） */
    private int rowAt(double mouseX, double mouseY, int paneX, int scroll, int count) {
        if (mouseX < paneX || mouseX >= paneX + paneW || mouseY < paneTop || mouseY >= paneTop + paneH) {
            return -1;
        }
        int idx = (int) ((mouseY - paneTop + scroll) / ROW_H);
        return idx >= 0 && idx < count ? idx : -1;
    }

    /** 高级设置里命中的行号（-1 = 没命中） */
    private int assignRowAt(double mouseX, double mouseY) {
        WebScreenId[] ids = WebScreenId.all();
        int perCol = (ids.length + 1) / 2;
        int rowH = 18;
        int colW = Math.min(170, (this.width - 20) / 2);
        int x0 = this.width / 2 - colW - 4;
        int x1 = this.width / 2 + 4;
        int top = 40;
        int col;
        if (mouseX >= x0 && mouseX < x0 + colW) {
            col = 0;
        } else if (mouseX >= x1 && mouseX < x1 + colW) {
            col = 1;
        } else {
            return -1;
        }
        if (mouseY < top) {
            return -1;
        }
        int row = (int) ((mouseY - top) / rowH);
        if (row < 0 || row >= perCol) {
            return -1;
        }
        int idx = col * perCol + row;
        return idx >= 0 && idx < ids.length ? idx : -1;
    }

    /** 打开选中主题的编辑器（编辑器是模组内置界面，不受主题影响） */
    private void editSelected() {
        if (selectedTheme == null) {
            return;
        }
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            mc.setScreen(new ThemeEditorScreen(selectedTheme, this));
        } catch (Throwable t) {
            DeepSpaceDeployer.LOGGER.warn("[深空铸造体][主题] 打开编辑器失败：{}", t.toString());
        }
    }

    private void enable(String theme) {
        selectedTheme = theme;
        try {
            if (ThemeManager.enable(theme)) {
                // 启用其他主题会清空高级配置（规则），这里把编辑中的草稿也清掉
                pending.clear();
                DeepSpaceDeployer.LOGGER.info("[深空铸造体][主题] 已启用：{}", theme);
            }
        } catch (Throwable t) {
            DeepSpaceDeployer.LOGGER.warn("[深空铸造体][主题] 启用失败：{}", t.toString());
        }
    }

    private void disable(String theme) {
        selectedTheme = theme;
        try {
            if (ThemeManager.disable(theme)) {
                pending.values().removeIf(theme::equals);
                DeepSpaceDeployer.LOGGER.info("[深空铸造体][主题] 已禁用：{}", theme);
            }
        } catch (Throwable t) {
            DeepSpaceDeployer.LOGGER.warn("[深空铸造体][主题] 禁用失败：{}", t.toString());
        }
    }

    /** 高级设置里点一行：在"默认 → 各已启用主题"之间循环 */
    private void cycleAssignment(WebScreenId id) {
        List<String> options = new ArrayList<>();
        options.add(ThemeManager.DEFAULT_THEME);
        options.addAll(enabled());
        if (options.size() <= 1) {
            options.addAll(available());            // 只有 default 时也能选别的（会一起启用）
        }
        String cur = pending.getOrDefault(id.key(), ThemeManager.DEFAULT_THEME);
        int at = options.indexOf(cur);
        String next = options.get((at + 1) % options.size());
        if (ThemeManager.DEFAULT_THEME.equals(next)) {
            pending.remove(id.key());
        } else {
            pending.put(id.key(), next);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mode == Mode.LIST) {
            double delta = -scrollY * 16;
            if (mouseX >= leftX && mouseX < leftX + paneW && mouseY >= paneTop && mouseY < paneTop + paneH) {
                scrollLeft = (float) Math.max(0, scrollLeft + delta);
                return true;
            }
            if (mouseX >= rightX && mouseX < rightX + paneW && mouseY >= paneTop && mouseY < paneTop + paneH) {
                scrollRight = (float) Math.max(0, scrollRight + delta);
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;                    // 和原版资源包界面一样：不暂停游戏
    }
}
