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
}
