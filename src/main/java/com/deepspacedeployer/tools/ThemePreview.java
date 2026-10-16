package com.deepspacedeployer.tools;

import com.deepspacedeployer.ui.theme.Canvas;
import com.deepspacedeployer.ui.theme.LayoutEngine;
import com.deepspacedeployer.ui.theme.PreviewCanvas;
import com.deepspacedeployer.ui.theme.ThemeDocument;
import com.deepspacedeployer.ui.theme.ThemeRenderer;
import com.deepspacedeployer.ui.theme.ThemeSource;
import com.deepspacedeployer.ui.webview.WebScreenId;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>主题离线验证工具</b>（不启动游戏，但要用游戏的字宽度量）。
 *
 * <p>为什么需要它：主题是"玩家会自己改的文件"，改错了的症状往往是
 * "界面里某一行跑到框外面"或者"某个界面打不开"。这两种问题都能在
 * <b>不开游戏</b>的情况下查出来：</p>
 *
 * <ol>
 *   <li><b>--check</b>：把登记表里的每个 html 都加载一遍（解析 + 绑定 + 布局），
 *       报告元素数、规则数、内容高度、用到的动作，以及有没有报错；</li>
 *   <li><b>--render 输出目录</b>：把每个界面渲染成 PNG（和游戏里同一段布局/绘制代码），
 *       肉眼就能看出"排版有没有崩"。</li>
 * </ol>
 *
 * <p>命令行由 Gradle 任务 {@code checkThemes} / {@code renderThemes} 转发，
 * 见 {@code build.gradle}。</p>
 */
public final class ThemePreview {

    /** 每个界面的示例数据（假数据，只为看到排版） */
    private static Map<String, String> sampleData(WebScreenId id) {
        Map<String, String> d = new HashMap<>();
        switch (id) {
            case BUYER -> {
                d.put("公司", "星联动力联盟");
                d.put("账户", "老板");
                d.put("余额", "12,480");
                d.put("卡余额", "3,200");
                d.put("等级", "中级");
                d.put("声望", "860");
                d.put("在线", "1");
                d.put("自动收购", "开");
                d.put("收购价.count", "4");
                d.put("收购价.0.名称", "铁锭");
                d.put("收购价.0.单价", "8");
                d.put("收购价.1.名称", "铜锭");
                d.put("收购价.1.单价", "6");
                d.put("收购价.2.名称", "安山合金");
                d.put("收购价.2.单价", "42");
                d.put("收购价.3.名称", "精密构件");
                d.put("收购价.3.单价", "120");
            }
            case FACTORY_TERMINAL -> {
                d.put("公司", "掠影推进公司");
                d.put("账户", "老板");
                d.put("余额", "5,000");
                d.put("卡余额", "980");
                d.put("等级", "高级");
                d.put("声望", "1,240");
                d.put("在线", "1");
                d.put("页", "1");
                d.put("总页", "3");
                d.put("商品.count", "3");
                d.put("商品.0.名称", "安山岩螺旋桨");
                d.put("商品.0.单价", "190");
                d.put("商品.1.名称", "精密构件");
                d.put("商品.1.单价", "260");
                d.put("商品.1.锁定", "1");
                d.put("商品.1.锁定原因", "需声望 2000");
                d.put("商品.2.名称", "推进器外壳");
                d.put("商品.2.单价", "75");
            }
            case FACTORY_CORE -> {
                d.put("公司", "磐基重工工业");
                d.put("账户", "老板");
                d.put("余额", "9,120");
                d.put("卡余额", "0");
                d.put("等级", "中级");
                d.put("声望", "400");
                d.put("在线", "1");
                d.put("蓝图页", "1");
                d.put("已选蓝图", "基础小车");
                d.put("组装费", "860");
                d.put("投放点", "已在图上选定");
                d.put("蓝图.count", "2");
                d.put("蓝图.0.名称", "基础小车");
                d.put("蓝图.0.尺寸", "5×3×7");
                d.put("蓝图.0.方块数", "48");
                d.put("蓝图.1.名称", "露营车");
                d.put("蓝图.1.尺寸", "7×4×9");
                d.put("蓝图.1.方块数", "96");
            }
            case MARKET -> {
                d.put("账户", "老板");
                d.put("余额", "2,000");
                d.put("卡余额", "150");
                d.put("在线", "1");
                d.put("市场页", "1");
                d.put("挂单.count", "2");
                d.put("挂单.0.物品", "铁锭");
                d.put("挂单.0.数量", "64");
                d.put("挂单.0.价格", "600");
                d.put("挂单.0.货币", "余额");
                d.put("挂单.0.卖家", "邻居");
                d.put("挂单.1.物品", "基础小车");
                d.put("挂单.1.数量", "1");
                d.put("挂单.1.价格", "8,000");
                d.put("挂单.1.货币", "余额");
                d.put("挂单.1.卖家", "老板");
                d.put("挂单.1.我的", "1");
            }
            case TRANSFER -> {
                d.put("公司", "星联动力联盟");
                d.put("卡A主", "老板");
                d.put("卡A余额", "3,200");
                d.put("卡B主", "未绑定");
                d.put("卡B余额", "0");
                d.put("金额", "100");
                d.put("公司账户", "1");
            }
            case ASSEMBLY_MAP -> {
                d.put("半径", "48");
                d.put("图就绪", "1");
                d.put("已选", "1");
                d.put("选中X", "128");
                d.put("选中Z", "-64");
            }
            case CARD_BIND -> {
                d.put("有卡主", "1");
                d.put("卡主", "老板");
                d.put("卡名", "公户卡");
                d.put("是副卡", "1");
            }
            case UNIVERSAL_TERMINAL -> {
                d.put("公司", "低语环带科技");
                d.put("有公司", "1");
                d.put("页", "2");
                d.put("总页", "4");
                d.put("商品.count", "2");
                d.put("商品.0.名称", "信号解码器");
                d.put("商品.0.单价", "310");
                d.put("商品.1.名称", "隐形涂层");
                d.put("商品.1.单价", "540");
            }
            case SHOP_EDITOR -> {
                d.put("当前公司", "skimmer");
                d.put("表名", "公司出售");
                d.put("公司.count", "6");
                String[][] cs = {{"threshold", "阈限实验集团"}, {"whisperbelt", "低语环带科技"},
                        {"brokenedge", "断锷军械财团"}, {"skimmer", "掠影推进公司"},
                        {"bedrock", "磐基重工工业"}, {"astralink", "星联动力联盟"}};
                for (int i = 0; i < cs.length; i++) {
                    d.put("公司." + i + ".代号", cs[i][0]);
                    d.put("公司." + i + ".名称", cs[i][1]);
                }
                d.put("条目.count", "3");
                d.put("条目.0.物品", "aeronautics:andesite_propeller");
                d.put("条目.0.价格", "190");
                d.put("条目.0.等级", "low");
                d.put("条目.1.物品", "create:precision_mechanism");
                d.put("条目.1.价格", "260");
                d.put("条目.1.等级", "mid");
                d.put("条目.2.物品", "minecraft:iron_ingot");
                d.put("条目.2.价格", "8");
                d.put("条目.2.等级", "low");
            }
            case CHOICE -> {
                d.put("当前主题", "default");
                d.put("默认主题", "default");
                d.put("主题.count", "3");
                d.put("主题.0.名字", "default");
                d.put("主题.0.名称", "默认主题");
                d.put("主题.0.作者", "深空铸造体");
                d.put("主题.0.版本", "2.0.0");
                d.put("主题.0.是当前", "1");
                d.put("主题.1.名字", "AUI");
                d.put("主题.1.名称", "我的主题");
                d.put("主题.1.作者", "老板");
                d.put("主题.1.版本", "0.1");
                d.put("主题.2.名字", "dark");
                d.put("主题.2.名称", "暗色");
                d.put("主题.2.作者", "-");
                d.put("主题.2.版本", "1.0");
                d.put("主题数量", "3");
            }
            default -> {
                // 没有示例数据的界面（理论上不会有）：留空也照样能验排版
            }
        }
        return d;
    }

    /**
     * 每个界面渲染时用的画布尺寸 —— <b>就是登记表里的尺寸</b>。
     *
     * <p>刻意不做"给高一点免得内容被裁"：那样会把溢出藏起来。
     * 预览出图和游戏里用同一套尺寸，溢出才会在预览里露出来（并在 --check 里报警）。</p>
     */
    private static int renderHeight(WebScreenId id) {
        return id.height();
    }

    // ---------------------------------------------------------------- 入口

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "--check";
        Path themeDir = args.length > 1
                ? Path.of(args[1]).toAbsolutePath().normalize()
                : Path.of("src/main/resources/assets/deepspacedeployer/theme/default")
                        .toAbsolutePath().normalize();

        ThemeSource src = sourceFor(themeDir);
        System.out.println("主题目录：" + src.dir());
        System.out.println("运行环境：" + (clientAvailable() ? "客户端类可用（字宽度量 = 游戏内）"
                : "纯 JDK（字宽度量 = 公式，仅供参考）"));
        System.out.println();

        switch (mode) {
            case "--check" -> check(src);
            case "--dump" -> {
                // 把每个元素的 y 和高度打出来：排查"内容高得离谱"时用
                String file = args.length > 2 ? args[2] : "buyer.html";
                dump(src, file);
            }
            case "--render" -> {
                Path outDir = args.length > 2 ? Path.of(args[2]) : Path.of("build/theme-preview");
                render(src, outDir);
            }
            default -> {
                System.out.println("用法：ThemePreview [--check|--dump|--render] [主题目录] [文件名/输出目录]");
            }
        }
    }

    /** 布局分解：谁把内容撑高了，一眼看出来 */
    private static void dump(ThemeSource src, String file) {
        WebScreenId id = WebScreenId.byFile(file);
        int w = id == null ? 288 : id.width();
        int h = id == null ? 600 : id.height();
        ThemeDocument doc = ThemeDocument.load(src, file, sampleData(
                id == null ? WebScreenId.BUYER : id)::get);
        if (doc == null) {
            System.out.println("找不到 " + file);
            return;
        }
        Canvas canvas = measuringCanvas(w, h);
        new LayoutEngine(canvas).layout(doc.root, w, h);

        System.out.println("=== " + file + " 布局分解（画布 " + w + "x" + h + "）===");
        doc.root.walk(n -> {
            if (!n.isElement() || n.box == null) {
                return;
            }
            var b = n.box;
            // 只打"有实际面积"的元素，减少噪音
            if (b.h < 1f && b.w < 1f) {
                return;
            }
            System.out.printf("  y=%-6s h=%-6s used=%-6s w=%-5s vis=%-5s %s%s%n",
                    Math.round(b.y), Math.round(b.h), Math.round(b.usedHeight),
                    Math.round(b.w), b.visible, n.brief(),
                    n.hasClass("player") ? "   ← 背包占位" : "");
        });
    }

    /** 主题资源根：交给 ThemeManager 的工厂（它负责把父目录当 theme/ 根） */
    private static ThemeSource sourceFor(Path themeDir) {
        return com.deepspacedeployer.ui.theme.ThemeManager.dirSource(themeDir);
    }

    /**
     * 客户端类在不在（决定能不能用游戏的字宽度量）。
     *
     * <p>用反射探一下：{@code Minecraft.getInstance()} 在专用服务器/纯 JDK 下会抛异常。</p>
     */
    private static boolean clientAvailable() {
        try {
            Class.forName("net.minecraft.client.Minecraft");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 校验

    private static void check(ThemeSource src) {
        int ok = 0;
        int missing = 0;
        List<String> lines = new ArrayList<>();
        for (WebScreenId id : WebScreenId.all()) {
            Map<String, String> data = sampleData(id);
            try {
                ThemeDocument doc = ThemeDocument.load(src, id.file(), data::get);
                if (doc == null) {
                    missing++;
                    lines.add("  ✗ " + pad(id.file()) + "没有这个文件");
                    continue;
                }
                Canvas canvas = measuringCanvas(id.width(), renderHeight(id));
                LayoutEngine engine = new LayoutEngine(canvas);
                float contentH = engine.layout(doc.root, id.width(), renderHeight(id));

                List<String> actions = new ArrayList<>();
                List<String> slots = new ArrayList<>();
                doc.root.walk(n -> {
                    if (!n.isElement()) {
                        return;
                    }
                    String a = n.attr("data-action");
                    if (a != null && !a.isBlank() && !actions.contains(a)) {
                        actions.add(a.trim());
                    }
                    String s = n.attr("data-slot");
                    if (s != null && !s.isBlank() && !slots.contains(s.trim())) {
                        slots.add(s.trim());
                    }
                });
                ok++;
                lines.add("  ✓ " + pad(id.file()) + "元素 " + doc.elementCount()
                        + " 规则 " + doc.rules.size()
                        + " 内容高 " + Math.round(contentH) + "/" + renderHeight(id)
                        + (contentH > renderHeight(id) + 0.5f ? " ⚠溢出" : "")
                        + "\n      动作 " + actions
                        + "\n      占位 " + slots);
                for (String w : doc.warnings) {
                    lines.add("      ⚠ " + w);
                }
            } catch (Throwable t) {
                lines.add("  ✗ " + pad(id.file()) + "出错：" + t);
            }
        }
        System.out.println("=== 界面主题校验（" + WebScreenId.all().length + " 个界面）===");
        lines.forEach(System.out::println);
        System.out.println();
        System.out.println("可用 " + ok + " 个，缺文件 " + missing + " 个"
                + (missing > 0 ? "（缺的界面会自动退回原生界面，不算错误）" : ""));
    }

    private static String pad(String s) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < 26) {
            sb.append(' ');
        }
        return sb.toString();
    }

    /**
     * 量文字用的画布。
     *
     * <p>第一优先用游戏的字宽度量（{@code GuiCanvas.measureOnly()}）—— 那是唯一能保证
     * "离线看到的 = 游戏里看到的"的做法；拿不到客户端就退回公式度量。</p>
     */
    private static Canvas measuringCanvas(int w, int h) {
        try {
            return com.deepspacedeployer.ui.webview.GuiCanvas.measureOnly();
        } catch (Throwable t) {
            return PreviewCanvas.of(noopGraphics(w, h), PreviewCanvas.pickCjkFont(), w, h);
        }
    }

    private static Graphics2D noopGraphics(int w, int h) {
        BufferedImage img = new BufferedImage(Math.max(1, w), Math.max(1, h),
                BufferedImage.TYPE_INT_ARGB);
        return img.createGraphics();
    }

    // ---------------------------------------------------------------- 出图

    private static void render(ThemeSource src, Path outDir) throws Exception {
        Files.createDirectories(outDir);
        for (WebScreenId id : WebScreenId.all()) {
            int w = id.width();
            int h = renderHeight(id);
            Map<String, String> data = sampleData(id);

            ThemeDocument doc = ThemeDocument.load(src, id.file(), data::get);
            if (doc == null) {
                System.out.println("  ✗ " + id.file() + "（没有这个文件）");
                continue;
            }

            BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            g.setColor(new Color(0x0B0D12));
            g.fillRect(0, 0, w, h);

            PreviewCanvas canvas = PreviewCanvas.of(g, PreviewCanvas.pickCjkFont(), w, h);
            new LayoutEngine(canvas).layout(doc.root, w, h);
            new ThemeRenderer(src, canvas).render(doc.root);
            g.dispose();

            File out = outDir.resolve(id.file().replace(".html", ".png")).toFile();
            ImageIO.write(image, "png", out);
            System.out.println("  ✔ " + out.getPath() + "  " + w + "x" + h
                    + "（元素 " + doc.elementCount() + "）");
        }
        System.out.println();
        System.out.println("出图完成 → " + outDir.toAbsolutePath());
        System.out.println("注：布局用原版字宽规则算（半角 6px / 全角 9px），"
                + "笔画由系统字体绘制，字形细节和游戏内有差别 —— 位置对不上才是问题。");
    }

    private static String pickFont() {
        return PreviewCanvas.pickCjkFont();
    }

    private ThemePreview() {
    }
}
