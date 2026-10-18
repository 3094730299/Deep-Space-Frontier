package com.deepspacedeployer.ui.webview;

import com.deepspacedeployer.Config;
import com.deepspacedeployer.DeepSpaceDeployer;
import com.deepspacedeployer.ui.theme.ThemeManager;
import com.deepspacedeployer.ui.theme.ThemeSource;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>内置 WebView 的总开关（客户端专属）</b>：什么时候释放默认主题、现在能不能用主题、
 * 以及给自检命令用的诊断信息。
 *
 * <p>"WebView"在这里的含义是：<b>不依赖任何浏览器内核、玩家零安装</b>的 HTML/CSS 渲染器
 * （实现在 {@code com.deepspacedeployer.ui.theme} 包里）。
 * 为什么不用真正的浏览器内核（Chromium/JavaFX），见
 * {@code docs/开发文档/界面主题系统.md} 的选型说明 —— 一句话：要玩家联网下上百 MB 原生库，
 * 或者把模组拆成三个操作系统各一个包，都不符合"内置"的要求。</p>
 *
 * <p>这个类<b>只在客户端</b>被加载（{@code ClientSetup} 里调），服务器永远不会碰。</p>
 */
public final class WebViewManager {

    private static volatile boolean available;
    private static volatile String lastError = "";
    /** 上一次见到的主题名（每 tick 比对，发现玩家换了主题就重载界面） */
    private static String lastTheme = "";

    private WebViewManager() {
    }

    /**
     * 客户端启动时调一次：建目录 + 把 jar 里内置的默认主题释放到磁盘。
     *
     * <p>⚠️ 这个调用以前<b>没有人做</b>（旧的待办清单第 5 条），所以
     * {@code theme/default/} 一直没被释放到磁盘、玩家看不到这个目录 —— 现在补上了。</p>
     */
    public static void bootstrap() {
        try {
            ThemeManager.ensureDefaultTheme();
            available = true;
            lastTheme = ThemeManager.activeName();
            DeepSpaceDeployer.LOGGER.info("[深空铸造体][WebView] 界面主题已就绪：{}",
                    ThemeManager.describe());
        } catch (Throwable t) {
            available = false;
            lastError = t.toString();
            DeepSpaceDeployer.LOGGER.warn("[深空铸造体][WebView] 初始化失败，界面将全部回退原生：{}",
                    t.toString());
        }
    }

    /** 主题系统现在可用吗（初始化失败 = 用原生界面，不影响游戏） */
    public static boolean available() {
        return available;
    }

    /**
     * 总开关：现在要不要用主题画界面。
     *
     * <p>两个条件都要满足：配置里开着 + 主题系统初始化成功。
     * 任何一个不满足 → 所有界面走原生（功能完全不受影响）。</p>
     */
    public static boolean enabled() {
        if (!available) {
            return false;
        }
        try {
            return Config.UI_THEME_ENABLED.get();
        } catch (Throwable t) {
            return true;                 // 配置读不到时默认开（行为更接近"主题有用"）
        }
    }

    /** 当前主题的资源根；不可用时返回 null */
    public static ThemeSource active() {
        if (!enabled()) {
            return null;
        }
        try {
            return ThemeManager.active();
        } catch (Throwable t) {
            lastError = t.toString();
            return null;
        }
    }

    /**
     * <b>某个界面</b>该用的主题资源根（支持"高级界面设置"里按界面指定）。
     *
     * <p>没开高级设置时，所有界面都用启用列表的最后一个 —— 和 {@link #active()} 一致。</p>
     */
    public static ThemeSource activeFor(WebScreenId id) {
        if (!enabled() || id == null) {
            return null;
        }
        try {
            return ThemeManager.source(ThemeManager.themeFor(id.key()));
        } catch (Throwable t) {
            lastError = t.toString();
            return null;
        }
    }

    /** 某个界面<b>实际</b>用的主题名（界面换皮时比对这个） */
    public static String themeNameFor(WebScreenId id) {
        try {
            return ThemeManager.themeFor(id == null ? null : id.key());
        } catch (Throwable t) {
            return ThemeManager.DEFAULT_THEME;
        }
    }

    /**
     * 每 tick 检查一次主题有没有被换掉（玩家在主题菜单里点了别的主题）。
     *
     * <p>这里只更新"上一次见到的主题名"，真正让界面重载由各界面自己看
     * {@link WebViewScreen#themeChanged()} —— 免得这里持有界面引用导致泄漏。</p>
     *
     * @return true = 这一 tick 刚发现主题变了
     */
    public static boolean pollThemeChange() {
        if (!available) {
            return false;
        }
        String now = ThemeManager.activeName();
        if (!now.equals(lastTheme)) {
            lastTheme = now;
            DeepSpaceDeployer.LOGGER.info("[深空铸造体][WebView] 主题已切换为：{}", now);
            return true;
        }
        return false;
    }

    /** 自检用：把每个界面的加载情况列出来（数据留空，只验"文件在不在、能不能解析"） */
    public static List<String> diagnose() {
        List<String> out = new ArrayList<>();
        out.add("可用=" + available + " 开关=" + enabled() + " " + ThemeManager.describe());
        if (!available) {
            out.add("初始化失败原因：" + lastError);
            return out;
        }
        ThemeSource src = ThemeManager.active();
        if (src == null) {
            out.add("没有可用主题");
            return out;
        }
        for (WebScreenId id : WebScreenId.all()) {
            try {
                ThemeContext ctx = ThemeContext.load(src, id, ThemeData.empty(),
                        GuiCanvas.measureOnly());
                if (ctx == null) {
                    out.add("  ✗ " + id.file() + "（没有这个文件 → 用原生界面）");
                    continue;
                }
                ctx.layout(id.width(), id.height());
                out.add("  ✓ " + id.file() + " " + ctx.document().summary());
            } catch (Throwable t) {
                out.add("  ✗ " + id.file() + " 出错：" + t);
            }
        }
        return out;
    }
}
