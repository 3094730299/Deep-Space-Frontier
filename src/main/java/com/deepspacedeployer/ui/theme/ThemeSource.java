package com.deepspacedeployer.ui.theme;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * <b>一套主题的"资源根"</b> —— 负责把 HTML/CSS 里写的路径解析成真正的字节。
 *
 * <p>老板定的资源约定（2026-10-15）：</p>
 * <pre>
 * &lt;版本文件夹&gt;/deepspacedeployer/theme/
 * ├── default/                 ← 模组内置的默认 UI（首次启动释放到磁盘）
 * │   ├── index.html
 * │   ├── choice.html          ← 主题选择菜单（也是主题化的）
 * │   ├── *.css  *.png
 * └── custom/
 *     └── AUI/                 ← 一套自定义主题 = 一个文件夹
 *         ├── index.html       ← 默认加载这个
 *         ├── *.css  *.png
 *         └── resources/1.ico
 * </pre>
 *
 * <p><b>路径解析规则</b>（三条，缺一不可）：</p>
 * <ol>
 *   <li>以 {@code /} 开头 → 相对 <b>theme/ 根</b>。例：{@code /default/a.png}
 *       → {@code theme/default/a.png}（老板原话："如何引用？直接 /default/a.png"）</li>
 *   <li>否则 → 相对<b>引用它的那个文件所在目录</b>。例：{@code ./x.png}、{@code a.png}、
 *       {@code resources/1.ico}</li>
 *   <li><b>找不到就回退</b>：自定义主题里缺的资源，去 {@code theme/default/} 找同名文件
 *       （老板原话："其他主题使用默认资源"）</li>
 * </ol>
 *
 * <p>安全：任何解析结果都必须留在 theme 根目录内 —— {@code ../} 之类想往外跑的一律拒绝。
 * 这样玩家放一个恶意/写错的主题也不会让模组去读游戏以外的文件。</p>
 */
public final class ThemeSource {

    /** 主题名（default 或 custom 下的文件夹名） */
    private final String name;
    /** 这套主题自己的文件夹：…/theme/default 或 …/theme/custom/AUI */
    private final Path dir;
    /** theme 根目录：…/deepspacedeployer/theme */
    private final Path root;
    /** 找不到时去哪儿兜底（永远是 theme/default） */
    private final Path defaultDir;

    ThemeSource(String name, Path dir, Path root, Path defaultDir) {
        this.name = name;
        this.dir = dir;
        this.root = root;
        this.defaultDir = defaultDir;
    }

    public String name() {
        return name;
    }

    public Path dir() {
        return dir;
    }

    public boolean isDefault() {
        return "default".equals(name);
    }

    // ---------------------------------------------------------------- 读文件

    /**
     * 读一个资源。
     *
     * @param requestPath HTML/CSS 里写的原样路径（可能带 / 开头、./ 前缀、查询串）
     * @param documentDir 引用它的文件所在目录（相对 theme 根，用 / 分隔；根目录传 ""）
     * @return 文件字节；找不到返回 null（调用方自己决定是画黑紫格还是跳过）
     */
    public byte[] read(String requestPath, String documentDir) {
        Path found = resolve(requestPath, documentDir);
        if (found == null) {
            return null;
        }
        try {
            return Files.readAllBytes(found);
        } catch (IOException e) {
            return null;
        }
    }

    /** 读文本（HTML/CSS），UTF-8。找不到返回 null */
    public String readText(String requestPath, String documentDir) {
        byte[] b = read(requestPath, documentDir);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    /**
     * 把请求路径解析成磁盘上的真实文件。
     *
     * @return 找到的文件；找不到返回 null
     */
    public Path resolve(String requestPath, String documentDir) {
        String clean = normalize(requestPath);
        if (clean == null) {
            return null;                       // 空路径 / 非法路径
        }
        for (Path candidate : candidates(clean, documentDir)) {
            if (candidate != null && Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** 按优先级列出所有可能的位置：主题自己 → default → （以后可加 jar 内置） */
    private List<Path> candidates(String clean, String documentDir) {
        List<Path> out = new ArrayList<>(3);

        if (clean.startsWith("/")) {
            // ① / 开头：相对 theme 根，例如 /default/a.png
            Path p = safeResolve(root, clean.substring(1));
            if (p != null) {
                out.add(p);
                // 回退：万一是 "/AUI/xxx" 这种写法而 AUI 缺文件，也去 default 试同名
                if (!clean.startsWith("/default/")) {
                    Path alt = safeResolve(defaultDir, stripThemePrefix(clean));
                    if (alt != null && !alt.equals(p)) {
                        out.add(alt);
                    }
                }
            }
            return out;
        }

        // ② 相对路径：先相对"引用者所在目录"
        String base = documentDir == null ? "" : documentDir;
        Path rel = safeResolve(dir, joinRel(base, clean));
        if (rel != null) {
            out.add(rel);
        }
        // 也试一下主题根（有些人习惯从根写 custom/AUI/x.png）
        Path relRoot = safeResolve(root, clean);
        if (relRoot != null) {
            out.add(relRoot);
        }

        // ③ 回退到 default：同一相对路径
        if (!isDefault()) {
            Path fb = safeResolve(defaultDir, joinRel(base, clean));
            if (fb != null) {
                out.add(fb);
            }
            Path fbRoot = safeResolve(root, joinRel("default", clean));
            if (fbRoot != null) {
                out.add(fbRoot);
            }
        }
        return out;
    }

    /** "/AUI/x.png" → "x.png"；"/default/x.png" → "x.png" */
    private static String stripThemePrefix(String clean) {
        String s = clean.startsWith("/") ? clean.substring(1) : clean;
        int slash = s.indexOf('/');
        return slash < 0 ? s : s.substring(slash + 1);
    }

    private static String joinRel(String base, String rel) {
        if (base == null || base.isEmpty() || ".".equals(base)) {
            return rel;
        }
        return base + "/" + rel;
    }

    /**
     * 把路径规范化成"用 / 分隔、去掉 ./ 和多余 /"的形式。
     *
     * @return 规范化结果；如果包含 {@code ..} 想往外跑，返回 null（拒绝）
     */
    static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        // 去掉查询串/锚点（HTML 里常写 a.png?v=2）
        int cut = s.indexOf('?');
        if (cut >= 0) {
            s = s.substring(0, cut);
        }
        cut = s.indexOf('#');
        if (cut >= 0) {
            s = s.substring(0, cut);
        }
        // 反斜杠一律当分隔符（有人从 Windows 复制路径过来）
        s = s.replace('\\', '/');
        // 协议前缀一律拒绝（我们只读本地主题目录，不联网）
        if (s.contains("://") || s.startsWith("data:") || s.startsWith("http")) {
            return null;
        }

        boolean absolute = s.startsWith("/");
        Deque<String> parts = new ArrayDeque<>();
        for (String seg : s.split("/")) {
            if (seg.isEmpty() || ".".equals(seg)) {
                continue;
            }
            if ("..".equals(seg)) {
                if (parts.isEmpty()) {
                    return null;               // 想跑出主题根 → 拒绝
                }
                parts.removeLast();
                continue;
            }
            parts.addLast(seg);
        }
        if (parts.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (absolute) {
            sb.append('/');
        }
        sb.append(String.join("/", parts));
        return sb.toString();
    }

    /** 把相对路径挂到 base 下，并确保结果仍在 base 之内（越界返回 null） */
    private static Path safeResolve(Path base, String relative) {
        if (base == null || relative == null) {
            return null;
        }
        Path resolved = base.resolve(relative).normalize();
        return resolved.startsWith(base.normalize()) ? resolved : null;
    }

    /** 某个路径所在的目录（相对 theme 根，用 / 分隔）；根目录返回 "" */
    public static String dirOf(String requestPath) {
        String clean = normalize(requestPath);
        if (clean == null) {
            return "";
        }
        if (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        int slash = clean.lastIndexOf('/');
        return slash < 0 ? "" : clean.substring(0, slash);
    }

    /** 列出一个子目录下的文件名（给主题列表这类用途；找不到返回空表） */
    public List<String> list(String subDir, String suffix) {
        List<String> out = new ArrayList<>();
        Path d = safeResolve(isDefault() ? dir : root, subDir);
        if (d == null || !Files.isDirectory(d)) {
            return out;
        }
        try (var stream = Files.list(d)) {
            stream.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> suffix == null || n.toLowerCase(java.util.Locale.ROOT)
                            .endsWith(suffix.toLowerCase(java.util.Locale.ROOT)))
                    .sorted()
                    .forEach(out::add);
        } catch (IOException ignored) {
            // 目录读不了就当空的
        }
        return out;
    }

    /** 直接从磁盘读（不做回退），主要给"释放默认主题"这类内部用途 */
    static boolean exists(Path p) {
        return p != null && Files.isRegularFile(p);
    }

    InputStream open(Path p) throws IOException {
        return Files.newInputStream(p);
    }
}
