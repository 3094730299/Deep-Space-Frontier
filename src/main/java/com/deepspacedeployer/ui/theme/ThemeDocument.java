package com.deepspacedeployer.ui.theme;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>一份加载好的主题文档</b>：HTML 树 + 算好的样式。
 *
 * <p>游戏内渲染和离线预览都从这里取东西，保证"预览看到的"和"游戏里看到的"是同一套结果。</p>
 *
 * <p>加载流程：读 HTML → 解析 → 收集 CSS（{@code <style>} 块 + {@code <link rel=stylesheet>}）
 * → 层叠 → 得到每个元素最终样式。</p>
 */
public final class ThemeDocument {

    public final ThemeSource source;
    /** 入口文件相对 theme 根的名字（如 "index.html" / "choice.html"） */
    public final String file;
    /** 该文件所在目录（相对 theme 根） */
    public final String docDir;
    public final HtmlNode root;
    public final String title;
    public final List<CssParser.Rule> rules = new ArrayList<>();

    /** 加载过程中的问题（给日志和离线预览提示用，不影响渲染） */
    public final List<String> warnings = new ArrayList<>();

    private ThemeDocument(ThemeSource source, String file, String docDir,
                          HtmlNode root, String title) {
        this.source = source;
        this.file = file;
        this.docDir = docDir;
        this.root = root;
        this.title = title;
    }

    /**
     * 加载主题里的某个 HTML 文件并按 CSS 算好样式。
     *
     * @return 加载好的文档；文件不存在返回 null（调用方回退原生界面）
     */
    public static ThemeDocument load(ThemeSource source, String file) {
        return load(source, file, null);
    }

    /**
     * 带数据的加载。
     *
     * <p>⚠️ <b>顺序不能反</b>：先绑定数据（会把 {@code data-repeat} 展开成一堆克隆节点），
     * <b>再</b>解析样式 —— 否则克隆出来的节点没有样式，布局会直接空指针。</p>
     */
    public static ThemeDocument load(ThemeSource source, String file, DataBinder.Data data) {
        if (source == null) {
            return null;
        }
        String html = source.readText(file, "");
        if (html == null) {
            return null;
        }
        String docDir = ThemeSource.dirOf(file);
        HtmlParser.Result parsed = HtmlParser.parse(html, docDir);
        ThemeDocument doc = new ThemeDocument(source, file, docDir, parsed.root, parsed.title);

        // ① <style> 块
        int order = 0;
        for (String css : parsed.styleSheets) {
            List<CssParser.Rule> rs = CssParser.parse(css, order);
            order += rs.size();
            doc.rules.addAll(rs);
        }
        // ② <link rel="stylesheet" href="...">（相对该 HTML 所在目录解析，带 default 回退）
        for (String href : parsed.linkedSheets) {
            String css = source.readText(href, docDir);
            if (css == null) {
                doc.warnings.add("找不到样式表：" + href);
                continue;
            }
            List<CssParser.Rule> rs = CssParser.parse(css, order);
            order += rs.size();
            doc.rules.addAll(rs);
        }

        // ③ 绑定数据（展开列表、替换占位符、求 data-if）—— 必须在解析样式之前
        DataBinder.bind(doc.root, data);

        // ④ 层叠出每个元素的最终样式
        new StyleResolver(doc.rules).resolve(doc.root);

        if (doc.warnings.isEmpty() && doc.rules.isEmpty()) {
            doc.warnings.add("这份主题没有任何 CSS 规则（只有内联样式？）");
        }
        return doc;
    }

    /** 按 id 找一个元素（给"按钮/数据绑定"用） */
    public HtmlNode byId(String id) {
        return root == null ? null : root.byId(id);
    }

    /** 元素个数（调试/自检用） */
    public int elementCount() {
        int[] n = {0};
        if (root != null) {
            root.walk(x -> {
                if (x.isElement()) {
                    n[0]++;
                }
            });
        }
        return n[0];
    }

    /**
     * 一行摘要（给游戏侧打日志用）。
     *
     * <p>⚠️ 本类<b>刻意不引用任何 MC/NeoForge 的类</b> —— 整个主题核心包只有
     * {@link ThemeManager} 碰 NeoForge（它要读配置和游戏目录）。
     * 这样离线预览工具才能用纯 javac 编译、直接跑出 PNG，不用启动游戏。</p>
     */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(file).append("（主题=").append(source.name())
                .append(" 元素=").append(elementCount())
                .append(" 规则=").append(rules.size()).append("）");
        for (String w : warnings) {
            sb.append(" ⚠ ").append(w);
        }
        return sb.toString();
    }
}
