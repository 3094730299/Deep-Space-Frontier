package com.deepspacedeployer.ui.theme;

import com.deepspacedeployer.Config;
import com.deepspacedeployer.DeepSpaceDeployer;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * <b>界面主题的总入口</b>：找目录、列主题、把内置默认主题释放到磁盘、记住当前用的是哪套。
 *
 * <p>目录约定（老板定稿）：</p>
 * <pre>
 * &lt;版本文件夹&gt;/deepspacedeployer/theme/
 * ├── default/        ← 模组内置，启动时自动释放/更新；玩家可以改，但会被版本更新覆盖
 * └── custom/&lt;名字&gt;/  ← 玩家自定义主题，模组永不改动
 * </pre>
 *
 * <p><b>为什么 default 会被覆盖、custom 不会</b>：
 * default 是"模组自带的预设"，改了它下次更新就没了 —— 想长期保留就复制到 custom 里。
 * 释放时用 {@code version.txt} 做版本比对，内容不同才重写，避免每次启动都动磁盘。</p>
 *
 * <p>整套是<b>纯客户端</b>的：服务端不会去读这些文件。</p>
 */
public final class ThemeManager {

    /** 内置默认主题的名字（也是资源兜底的来源） */
    public static final String DEFAULT_THEME = "default";
    /** custom 主题所在子目录名 */
    public static final String CUSTOM_DIR = "custom";
    /** 主题入口文件名（老板定稿：模组默认加载 index.html） */
    public static final String INDEX_FILE = "index.html";
    /** 主题选择菜单的文件名（每套主题自带一份） */
    public static final String CHOICE_FILE = "choice.html";

    private static volatile Path cachedRoot;

    private ThemeManager() {
    }

    // ---------------------------------------------------------------- 目录

    /** {@code <版本文件夹>/deepspacedeployer/theme} */
    public static Path root() {
        Path r = cachedRoot;
        if (r == null) {
            r = FMLPaths.GAMEDIR.get().resolve(DeepSpaceDeployer.MOD_ID).resolve("theme");
            cachedRoot = r;
        }
        return r;
    }

    public static Path defaultDir() {
        return root().resolve(DEFAULT_THEME);
    }

    public static Path customDir() {
        return root().resolve(CUSTOM_DIR);
    }

    /** 确保两个目录存在（第一次进游戏就建好，玩家能直接看到） */
    public static void ensureDirs() {
        try {
            Files.createDirectories(defaultDir());
            Files.createDirectories(customDir());
        } catch (IOException e) {
            DeepSpaceDeployer.LOGGER.warn("[深空铸造体][主题] 建目录失败：{}", e.toString());
        }
    }

    // ---------------------------------------------------------------- 内置默认主题的释放

    /**
     * 把模组 jar 里内置的默认主题释放到磁盘（必要时更新）。
     *
     * <p>策略：比对 {@code version.txt}；磁盘上没有、或版本和 jar 里不一致 → 整份重写。
     * 这样我改默认主题时老板能自动拿到，而 {@code custom/} 永远不会被动。</p>
     */
    public static void ensureDefaultTheme() {
        ensureDirs();
        Path bundled = bundledDefaultDir();
        if (bundled == null) {
            DeepSpaceDeployer.LOGGER.warn("[深空铸造体][主题] jar 里没找到内置默认主题，界面将用原生回退");
            return;
        }
        try {
            String jarVersion = readTextOrNull(bundled.resolve("version.txt"));
            Path diskVersionFile = defaultDir().resolve("version.txt");
            String diskVersion = Files.isRegularFile(diskVersionFile)
                    ? Files.readString(diskVersionFile).trim() : null;
            if (diskVersion != null && diskVersion.equals(jarVersion == null ? "" : jarVersion.trim())) {
                return;                            // 已经是最新，不动磁盘
            }
            int n = copyTree(bundled, defaultDir());
            DeepSpaceDeployer.LOGGER.info(
                    "[深空铸造体][主题] 已释放内置默认主题（{} 个文件）→ {}",
                    n, defaultDir());
        } catch (Throwable t) {
            DeepSpaceDeployer.LOGGER.warn("[深空铸造体][主题] 释放默认主题失败：{}", t.toString());
        }
    }

    /** 模组 jar 内 {@code assets/<modid>/theme/default} 的真实路径（开发环境就是源码目录） */
    private static Path bundledDefaultDir() {
        try {
            var container = net.neoforged.fml.ModList.get()
                    .getModContainerById(DeepSpaceDeployer.MOD_ID);
            if (container.isEmpty()) {
                return null;
            }
            var file = container.get().getModInfo().getOwningFile().getFile();
            Path p = file.findResource("assets", DeepSpaceDeployer.MOD_ID, "theme", DEFAULT_THEME);
            return p != null && Files.isDirectory(p) ? p : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int copyTree(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        int[] count = {0};
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path src : walk.toList()) {
                Path rel = from.relativize(src);
                Path dst = to.resolve(rel.toString());
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else {
                    Files.createDirectories(dst.getParent());
                    try (InputStream in = Files.newInputStream(src)) {
                        Files.copy(in, dst, StandardCopyOption.REPLACE_EXISTING);
                    }
                    count[0]++;
                }
            }
        }
        return count[0];
    }

    private static String readTextOrNull(Path p) {
        try {
            return Files.isRegularFile(p) ? Files.readString(p).trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 主题枚举与选择

    /** 可选主题：永远包含 default；custom 下每个带 index.html 的文件夹算一套 */
    public static List<String> availableThemes() {
        List<String> out = new ArrayList<>();
        out.add(DEFAULT_THEME);
        Path c = customDir();
        if (Files.isDirectory(c)) {
            try (Stream<Path> list = Files.list(c)) {
                list.filter(Files::isDirectory)
                        .filter(p -> Files.isRegularFile(p.resolve(INDEX_FILE)))
                        .map(p -> p.getFileName().toString())
                        .sorted()
                        .forEach(out::add);
            } catch (IOException ignored) {
                // 读不了就当只有 default
            }
        }
        return out;
    }

    /** 当前主题名：读配置，并在不可用时安静回退到 default */
    public static String activeName() {
        String want = null;
        try {
            want = Config.UI_THEME.get();
        } catch (Throwable ignored) {
            // 配置还没加载（极早期）—— 用 default
        }
        if (want == null || want.isBlank()) {
            return DEFAULT_THEME;
        }
        String name = want.trim();
        if (DEFAULT_THEME.equals(name)) {
            return DEFAULT_THEME;
        }
        Path p = customDir().resolve(name).normalize();
        // 只允许 custom 下的直接子目录（防止 ../ 跑出去）
        if (!p.startsWith(customDir().normalize()) || !Files.isRegularFile(p.resolve(INDEX_FILE))) {
            return DEFAULT_THEME;
        }
        return name;
    }

    /** 切主题（写配置 + 落盘），返回是否成功 */
    public static boolean setActive(String name) {
        if (name == null || !availableThemes().contains(name)) {
            return false;
        }
        try {
            Config.UI_THEME.set(name);
            Config.UI_THEME.save();
            return true;
        } catch (Throwable t) {
            DeepSpaceDeployer.LOGGER.warn("[深空铸造体][主题] 保存主题选择失败：{}", t.toString());
            return false;
        }
    }

    /** 当前主题的资源根 */
    public static ThemeSource active() {
        return source(activeName());
    }

    /**
     * 把<b>任意一个目录</b>当成一套主题（给离线工具用：直接指到
     * {@code src/main/resources/assets/deepspacedeployer/theme/default} 就能预览）。
     *
     * <p>目录的父目录被视为 {@code theme/} 根，回退目录是 {@code theme/default/}；
     * 父目录不存在时就地兜底（不回退）。</p>
     */
    public static ThemeSource dirSource(Path themeDir) {
        Path dir = themeDir.toAbsolutePath().normalize();
        Path parent = dir.getParent();
        Path root = parent == null ? dir : parent;
        return new ThemeSource(dir.getFileName() == null ? DEFAULT_THEME : dir.getFileName().toString(),
                dir, root, root.resolve(DEFAULT_THEME));
    }

    /** 指定名字的资源根（不存在也返回一个可用的 default 兜底） */
    public static ThemeSource source(String name) {
        String n = name == null ? DEFAULT_THEME : name.trim();
        Path dir = DEFAULT_THEME.equals(n)
                ? defaultDir()
                : customDir().resolve(n).normalize();
        if (!DEFAULT_THEME.equals(n) && !dir.startsWith(customDir().normalize())) {
            dir = defaultDir();                    // 越界 → 退回 default
            n = DEFAULT_THEME;
        }
        return new ThemeSource(n, dir, root(), defaultDir());
    }

    /** 主题的入口 HTML 文本；没有就返回 null（调用方回退原生界面） */
    public static String indexHtml(ThemeSource src) {
        return src.readText(INDEX_FILE, "");
    }

    /** 主题自带的选择菜单 HTML；没有则返回 null（用内置的兜底菜单） */
    public static String choiceHtml(ThemeSource src) {
        return src.readText(CHOICE_FILE, "");
    }

    /** 当前主题的入口文档（index.html + 数据绑定）；没有就返回 null → 调用方回退原生界面 */
    public static ThemeDocument loadActive(DataBinder.Data data) {
        return ThemeDocument.load(active(), INDEX_FILE, data);
    }

    /** 某套主题的选择菜单（choice.html）；没有就返回 null → 用内置兜底菜单 */
    public static ThemeDocument loadChoice(String themeName, DataBinder.Data data) {
        return ThemeDocument.load(source(themeName), CHOICE_FILE, data);
    }

    /** 调试用：把当前状态拼成一行 */
    public static String describe() {
        return String.format(Locale.ROOT, "theme=%s root=%s 可用=%s",
                activeName(), root(), availableThemes());
    }

    // ---------------------------------------------------------------- 元数据 / 图标

    /** index.json 缓存（键 = 主题名；玩家改文件后由 {@link #invalidateMeta()} 清掉） */
    private static final java.util.Map<String, ThemeMeta> META_CACHE = new java.util.HashMap<>();

    /** 清掉元数据缓存（主题选择界面"刷新"时调） */
    public static void invalidateMeta() {
        synchronized (META_CACHE) {
            META_CACHE.clear();
        }
    }

    /**
     * 读一套主题的 {@code index.json}（名称 / 作者 / 版本 / 介绍 / 图标）。
     *
     * <p>读不到、读坏了都返回 {@link ThemeMeta#EMPTY} —— 主题文件是玩家可以随便改的，
     * 一个坏 JSON 不能影响界面打开。</p>
     */
    public static ThemeMeta metaOf(String themeName) {
        String name = themeName == null ? DEFAULT_THEME : themeName.trim();
        synchronized (META_CACHE) {
            ThemeMeta cached = META_CACHE.get(name);
            if (cached != null) {
                return cached;
            }
        }
        ThemeMeta meta = ThemeMeta.EMPTY;
        try {
            Path file = source(name).dir().resolve("index.json");
            if (Files.isRegularFile(file)) {
                meta = ThemeMeta.parse(Files.readString(file));
            }
        } catch (Throwable t) {
            meta = ThemeMeta.EMPTY;
        }
        synchronized (META_CACHE) {
            META_CACHE.put(name, meta);
        }
        return meta;
    }

    /**
     * 一套主题的<b>图标字节</b>（PNG）。
     *
     * <p>路径来自 {@code index.json} 的 {@code icon}（默认 {@code icon.png}），
     * 相对主题文件夹、和 index.json 同级。自定义主题没放图标时，
     * {@link ThemeSource#read} 会自动回退到 {@code theme/default/} 的同名文件，
     * 所以默认主题放了 {@code icon.png}，所有没配图标的主题都会显示它。</p>
     *
     * @return PNG 字节；找不到返回 null（界面自己画占位方块）
     */
    public static byte[] iconBytes(String themeName) {
        String name = themeName == null ? DEFAULT_THEME : themeName.trim();
        try {
            String icon = metaOf(name).iconFile();
            return source(name).read(icon, "");
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 启用列表（资源包式）

    /** 已启用的主题（按配置里的顺序；过滤掉已经不存在的；空表 = 只有 default） */
    public static List<String> enabledThemes() {
        List<String> available = availableThemes();
        List<String> out = new ArrayList<>();
        String raw = readConfig(Config.UI_THEME_LIST, "");
        for (String part : raw.split("[,;]")) {
            String n = part.trim();
            if (!n.isEmpty() && available.contains(n) && !out.contains(n)) {
                out.add(n);
            }
        }
        if (out.isEmpty()) {
            out.add(DEFAULT_THEME);
        }
        return out;
    }

    /** 写回启用列表（同时把 {@code ui.theme} 同步成"最后一个"，保持旧配置兼容） */
    public static void setEnabledThemes(List<String> names) {
        List<String> clean = new ArrayList<>();
        for (String n : names) {
            if (n != null && !n.isBlank() && !clean.contains(n.trim())) {
                clean.add(n.trim());
            }
        }
        if (clean.isEmpty()) {
            clean.add(DEFAULT_THEME);
        }
        writeConfig(Config.UI_THEME_LIST, String.join(",", clean));
        writeConfig(Config.UI_THEME, clean.get(clean.size() - 1));
    }

    // ---------------------------------------------------------------- 高级：每界面指定

    /** 每个界面的主题指定（key = {@code WebScreenId.key()}）；空表 = 没开高级设置 */
    public static java.util.Map<String, String> assignments() {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        String raw = readConfig(Config.UI_THEME_ASSIGN, "");
        if (raw.isBlank()) {
            return out;
        }
        List<String> available = availableThemes();
        for (String part : raw.split("[;]")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = part.substring(0, eq).trim();
            String theme = part.substring(eq + 1).trim();
            if (!key.isEmpty() && available.contains(theme)) {
                out.put(key, theme);
            }
        }
        return out;
    }

    /** 写回高级指定（空表 = 关掉高级设置，所有界面回到启用列表的最后一个） */
    public static void setAssignments(java.util.Map<String, String> assign) {
        StringBuilder sb = new StringBuilder();
        for (var e : assign.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue().isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(e.getKey().trim()).append('=').append(e.getValue().trim());
        }
        writeConfig(Config.UI_THEME_ASSIGN, sb.toString());
    }

    public static boolean hasAssignments() {
        return !assignments().isEmpty();
    }

    // ---------------------------------------------------------------- 生效主题

    /**
     * 某个界面<b>实际</b>该用哪套主题。
     *
     * <p>规则（老板 2026-10-18 定）：</p>
     * <ol>
     *   <li>开了"高级界面设置"（{@code ui.theme_assign} 非空）→ 该界面指定的主题；
     *       没指定 / 指定的不存在 → {@code default}；</li>
     *   <li>没开高级设置 → 启用列表的<b>最后一个</b>。</li>
     * </ol>
     *
     * @param screenKey {@code WebScreenId.key()}，如 {@code buyer}
     */
    public static String themeFor(String screenKey) {
        java.util.Map<String, String> assign = assignments();
        if (!assign.isEmpty()) {
            String t = screenKey == null ? null : assign.get(screenKey);
            return t == null ? DEFAULT_THEME : t;
        }
        List<String> en = enabledThemes();
        return en.get(en.size() - 1);
    }

    /**
     * 启用一套主题（资源包式的"点左边的加进右边"）。
     *
     * <p>⚠️ 老板定的规则：<b>启用其他主题会清空高级界面设置</b> ——
     * 否则"某个界面还指着别的主题"和"所有界面都由新主题渲染"会互相打架。</p>
     */
    public static boolean enable(String name) {
        if (name == null || !availableThemes().contains(name.trim())) {
            return false;
        }
        String n = name.trim();
        setAssignments(java.util.Map.of());          // 清空高级设置
        List<String> list = enabledThemes();
        list.remove(n);
        list.add(n);                                  // 放到最后 = 生效
        setEnabledThemes(list);
        return true;
    }

    /**
     * 禁用一套主题。
     *
     * <p>规则：它负责的界面<b>回落到 default</b>（把高级设置里指向它的条目删掉）；
     * 如果它是"最后一个"（正在全局生效），生效的变成剩下那个（或 default）。</p>
     */
    public static boolean disable(String name) {
        if (name == null) {
            return false;
        }
        String n = name.trim();
        List<String> list = enabledThemes();
        if (!list.contains(n)) {
            return false;
        }
        list.remove(n);
        if (list.isEmpty()) {
            list.add(DEFAULT_THEME);
        }
        // 高级设置里指向它的条目 → 删掉（= 该界面回到 default）
        java.util.Map<String, String> assign = assignments();
        boolean changed = assign.values().removeIf(n::equals);
        if (changed) {
            setAssignments(assign);
        }
        setEnabledThemes(list);
        return true;
    }

    // ---------------------------------------------------------------- 配置读写小工具

    private static String readConfig(net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<String> value,
                                     String fallback) {
        try {
            String v = value.get();
            return v == null ? fallback : v;
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static void writeConfig(net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<String> value,
                                    String v) {
        try {
            value.set(v);
            value.save();
        } catch (Throwable t) {
            DeepSpaceDeployer.LOGGER.warn("[深空铸造体][主题] 保存主题配置失败：{}", t.toString());
        }
    }
}
