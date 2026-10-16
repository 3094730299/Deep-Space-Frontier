package com.deepspacedeployer.ui.webview;

import com.deepspacedeployer.DeepSpaceDeployer;
import com.deepspacedeployer.ui.theme.ThemeManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.Util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>主题选择菜单</b>：ESC →「界面主题」进来的那个界面。
 *
 * <p>它本身也是主题化的：读当前主题里的 {@code choice.html}。
 * 主题没写这个文件（或者写坏了）→ <b>退回这里的内置按钮列表</b>，功能不会丢。</p>
 *
 * <p>支持的动作（写 choice.html 的人照这个来）：</p>
 * <table>
 *   <tr><td>{@code use:主题名}</td><td>切换到这个主题（立刻生效并写进配置）</td></tr>
 *   <tr><td>{@code open_folder}</td><td>在系统文件管理器里打开 theme 目录</td></tr>
 *   <tr><td>{@code close}</td><td>关闭界面</td></tr>
 * </table>
 *
 * <p>给主题用的数据键：{@code 当前主题}、{@code 主题数量}、{@code 默认主题}，
 * 以及列表 {@code 主题()}（每项：{@code 名字 / 是当前 / 名称 / 作者 / 版本 / 介绍}）。</p>
 */
public class ThemeChoiceScreen extends Screen {

    private final WebViewScreen webview = new WebViewScreen(WebScreenId.CHOICE);
    /** 内置回退列表用的按钮 */
    private final List<Button> fallbackButtons = new ArrayList<>();
    /** 记住上次的数据摘要：变了才 refresh（refresh 要重解析一遍 html） */
    private String lastSignature = "";
    /** 主题元数据（index.json）缓存，键 = 主题名 */
    private static final Map<String, ThemeMeta> META_CACHE = new LinkedHashMap<>();

    public ThemeChoiceScreen() {
        super(Component.translatable("gui.deepspacedeployer.theme_title"));
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void init() {
        super.init();
        webview.setEnabled(WebViewManager.enabled());
        if (webview.enabled()) {
            webview.setActionSink(this::onThemeAction);
            webview.size(WebScreenId.CHOICE.width(), WebScreenId.CHOICE.height());
            webview.at((this.width - WebScreenId.CHOICE.width()) / 2,
                    (this.height - WebScreenId.CHOICE.height()) / 2);
            webview.reload(this::buildData);
        }
        rebuildFallback();
    }

    /** 主题里没写 choice.html（或者主题系统关了）时，用原生按钮列表兜底 */
    private void rebuildFallback() {
        clearWidgets();
        fallbackButtons.clear();
        if (webview.visible()) {
            return;                       // 主题能用，就不摆原生按钮了
        }
        List<String> themes = ThemeManager.availableThemes();
        int y = this.height / 2 - themes.size() * 12;
        for (String name : themes) {
            if (y > this.height - 40) {
                break;
            }
            boolean current = name.equals(ThemeManager.activeName());
            String label = (current ? "✔ " : "") + name;
            Button b = Button.builder(Component.literal(label), btn -> {
                useTheme(name);
                rebuildFallback();
            }).bounds(this.width / 2 - 100, y, 200, 20).build();
            fallbackButtons.add(addRenderableWidget(b));
            y += 24;
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.theme_close"),
                        b -> this.onClose())
                .bounds(this.width / 2 - 100, y + 8, 200, 20).build());
    }

    // ---------------------------------------------------------------- 渲染

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 主题换了（玩家点了别的主题）→ 重新加载一次
        if (webview.visible() && webview.themeChanged()) {
            webview.refresh();
            rebuildFallback();
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        if (webview.visible()) {
            webview.render(graphics, mouseX, mouseY, partialTick);
        } else {
            graphics.drawCenteredString(this.font, this.title, this.width / 2, 24, 0xFFFFFFFF);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (webview.mouseClicked(mouseX, mouseY, button)) {
            // 动作可能改了主题 → 立刻重算数据（列表里的"是当前"要跟着变）
            webview.refresh();
            rebuildFallback();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (webview.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ---------------------------------------------------------------- 动作

    /**
     * 主题菜单的动作。
     *
     * @return true = 处理了
     */
    private boolean onThemeAction(String action, com.deepspacedeployer.ui.theme.HtmlNode source) {
        if (action == null) {
            return false;
        }
        if (action.startsWith("use:")) {
            return useTheme(action.substring(4).trim());
        }
        switch (action) {
            case "close" -> {
                this.onClose();
                return true;
            }
            case "open_folder" -> {
                openThemeFolder();
                return true;
            }
            case "reload" -> {
                META_CACHE.clear();
                webview.refresh();
                rebuildFallback();
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** 切换主题：写配置 + 立刻重载本界面 */
    private boolean useTheme(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        if (!ThemeManager.setActive(name.trim())) {
            DeepSpaceDeployer.LOGGER.warn("[深空铸造体][WebView] 切主题失败：{}", name);
            return false;
        }
        webview.refresh();
        rebuildFallback();
        DeepSpaceDeployer.LOGGER.info("[深空铸造体][WebView] 已切换到主题：{}", name);
        return true;
    }

    private void openThemeFolder() {
        try {
            Path dir = ThemeManager.root();
            Files.createDirectories(dir);
            Util.getPlatform().openFile(dir.toFile());
        } catch (Throwable t) {
            DeepSpaceDeployer.LOGGER.warn("[深空铸造体][WebView] 打开主题目录失败：{}", t.toString());
        }
    }

    // ---------------------------------------------------------------- 数据

    /** 喂给 choice.html 的数据 */
    private ThemeData buildData() {
        ThemeData data = new ThemeData();
        String current = ThemeManager.activeName();
        List<String> names = ThemeManager.availableThemes();
        String signature = current + "|" + String.join(",", names);
        lastSignature = signature;

        data.put("当前主题", current);
        data.put("主题数量", names.size());
        data.put("默认主题", ThemeManager.DEFAULT_THEME);

        List<Map<String, ?>> rows = new ArrayList<>();
        for (String name : names) {
            ThemeMeta meta = metaOf(name);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("名字", name);
            row.put("是当前", name.equals(current) ? "1" : "");
            row.put("名称", meta.name().isEmpty() ? name : meta.name());
            row.put("作者", meta.author());
            row.put("版本", meta.version());
            row.put("介绍", meta.description());
            row.put("内置", ThemeManager.DEFAULT_THEME.equals(name) ? "1" : "");
            rows.add(row);
        }
        data.list("主题", rows);
        return data;
    }

    // ---------------------------------------------------------------- 主题元数据

    /**
     * 读一套主题的 {@code index.json}（名称 / 作者 / 版本 / 介绍）。
     *
     * <p>不引第三方 JSON 库：这里的结构极简单（四个字符串字段），
     * 用原版自带的 {@code Gson}? —— 不，客户端不保证有；所以手写一个最小解析，
     * 只认 {@code "key": "value"} 这一种形式，解析失败就当没有元数据（不报错）。</p>
     */
    private static ThemeMeta metaOf(String themeName) {
        ThemeMeta cached = META_CACHE.get(themeName);
        if (cached != null) {
            return cached;
        }
        ThemeMeta meta = ThemeMeta.EMPTY;
        try {
            Path file = ThemeManager.source(themeName).dir().resolve("index.json");
            if (Files.isRegularFile(file)) {
                meta = parseMeta(Files.readString(file));
            }
        } catch (Throwable t) {
            meta = ThemeMeta.EMPTY;
        }
        META_CACHE.put(themeName, meta);
        return meta;
    }

    /** 极小的 JSON 取值：找 {@code "key"} 后面的字符串值 */
    static ThemeMeta parseMeta(String json) {
        return new ThemeMeta(
                stringValue(json, "name"),
                stringValue(json, "author"),
                stringValue(json, "version"),
                stringValue(json, "description"));
    }

    private static String stringValue(String json, String key) {
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
                sb.append(switch (n) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case '"' -> '"';
                    case '\\' -> '\\';
                    default -> n;
                });
                continue;
            }
            if (c == '"') {
                break;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 一套主题的元数据（index.json；缺字段就是空串） */
    public record ThemeMeta(String name, String author, String version, String description) {
        public static final ThemeMeta EMPTY = new ThemeMeta("", "", "", "");
    }

    @Override
    public void onClose() {
        // 关界面即可（临时贴图每帧都会释放，不留跨帧引用）
        super.onClose();
    }
}
