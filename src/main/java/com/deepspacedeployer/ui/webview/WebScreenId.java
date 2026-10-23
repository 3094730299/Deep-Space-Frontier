package com.deepspacedeployer.ui.webview;

import com.deepspacedeployer.DeepSpaceDeployer;

/**
 * <b>界面登记表</b>：模组里每一个"要画界面的地方"在这里登记一次。
 *
 * <p>老板定的规矩：<b>一个界面 = 一个 html 文件</b>。枚举里的 {@code file} 就是那个文件名，
 * 主题目录里的对应关系见 {@code docs/开发文档/界面清单.md}。</p>
 *
 * <p>缺文件不会崩：加载失败 → 该界面退回原来的原生界面
 * （{@link #file()} 返回空串的那些界面本来就没有主题化）。</p>
 */
public enum WebScreenId {

    /** 工厂终端（商店：买商品 / 卖蓝图凭证） */
    FACTORY_TERMINAL("factory_terminal.html", "工厂终端", 288, 300),
    /** 收购投送机（投料换钱） */
    BUYER("buyer.html", "收购投送机", 288, 300),
    /** 载具打印机（选蓝图 + 材料 + 组装） */
    FACTORY_CORE("factory_core.html", "载具打印机", 288, 300),
    /** 模块打印机（只读服务端模块预设；主题文件缺失时自动回退原生界面） */
    MODULE_PRINTER("module_printer.html", "模块打印机", 288, 300),
    /** 寄售市场（逛市场 / 我要卖） */
    MARKET("market.html", "寄售市场", 288, 300),
    /** 资金转化（卡↔卡、卡↔公司账户） */
    TRANSFER("transfer.html", "资金转化", 288, 300),
    /** 投放点选择（俯视图点地图） */
    ASSEMBLY_MAP("assembly_map.html", "投放点选择", 320, 300),
    /** 银行卡绑定 */
    CARD_BIND("card_bind.html", "银行卡绑定", 260, 300),
    /** 通用终端（装饰机） */
    UNIVERSAL_TERMINAL("universal_terminal.html", "通用终端", 268, 300),
    /** 价目表编辑器（OP） */
    SHOP_EDITOR("shop_editor.html", "价目表编辑器", 340, 340),
    /** 主题选择菜单（每套主题自带一份，缺了用 default 的） */
    CHOICE("choice.html", "界面主题", 260, 270);

    private final String file;
    private final String cnName;
    private final int width;
    private final int height;

    WebScreenId(String file, String cnName, int width, int height) {
        this.file = file;
        this.cnName = cnName;
        this.width = width;
        this.height = height;
    }

    /** 对应主题目录里的文件名 */
    public String file() {
        return file;
    }

    /**
     * 配置里用的短名（= 文件名去掉 {@code .html}）。
     *
     * <p>"高级界面设置"就是按这个 key 存 {@code ui.theme_assign} 的：
     * {@code buyer=我的主题;market=默认主题}。</p>
     */
    public String key() {
        return file.endsWith(".html") ? file.substring(0, file.length() - 5) : file;
    }

    public String cnName() {
        return cnName;
    }

    /** 建议的主题画布宽度（逻辑像素） */
    public int width() {
        return width;
    }

    /** 建议的主题画布高度（逻辑像素） */
    public int height() {
        return height;
    }

    /** 登记表里所有界面（自检用） */
    public static WebScreenId[] all() {
        return values();
    }

    /**
     * <b>真正会用主题渲染</b>的界面（给"高级界面设置"列出来用）。
     *
     * <p>排除 {@link #CHOICE}：主题选择界面从 0.1.6 起是原生实现，不再读 html，
     * 把它列进"这个界面用哪套主题"只会让玩家困惑。</p>
     */
    public static WebScreenId[] themeable() {
        return java.util.Arrays.stream(values())
                .filter(id -> id != CHOICE)
                .toArray(WebScreenId[]::new);
    }

    /**
     * 按文件名找回登记项（找不到返回 null）。
     *
     * <p>给自检工具用：扫主题目录里的 html，看有没有登记过、有没有漏登记的。</p>
     */
    public static WebScreenId byFile(String file) {
        if (file == null) {
            return null;
        }
        for (WebScreenId id : values()) {
            if (id.file.equalsIgnoreCase(file.trim())) {
                return id;
            }
        }
        return null;
    }

    /** 日志前缀统一走这里，方便一眼搜 */
    public static String logPrefix() {
        return "[" + DeepSpaceDeployer.MOD_ID + "][界面]";
    }
}
