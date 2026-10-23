package com.deepspacedeployer;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 通用配置（config/deepspacedeployer-common.toml）
 *
 * <p>这里只放"服务器全局"开关；价格、商品、收购清单都在数据包里改，不在这里。</p>
 */
public final class Config {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // ---------------- 世界生成 ----------------
    public static final ModConfigSpec.BooleanValue ENABLE_FACTORY_WORLDGEN = BUILDER
            .comment("是否让六家公司的工厂在世界里自然生成", "关掉之后只能用指令/刷怪蛋生成，方便测试")
            .define("worldgen.enable_factory_worldgen", true);

    public static final ModConfigSpec.DoubleValue WORLDGEN_RARITY_MULTIPLIER = BUILDER
            .comment("工厂生成稀有度倍率：1.0 = 默认；2.0 = 少一半；0.5 = 多一倍")
            .defineInRange("worldgen.rarity_multiplier", 1.0D, 0.1D, 20.0D);

    // ---------------- 经济 ----------------
    public static final ModConfigSpec.IntValue CROSS_COMPANY_EXCHANGE_FEE = BUILDER
            .comment("跨公司兑换手续费（百分比，0~50）。20 表示换 100 票据要交 20 的手续费")
            .defineInRange("economy.exchange_fee_percent", 20, 0, 50);

    public static final ModConfigSpec.IntValue REPUTATION_PER_1000_SPENT = BUILDER
            .comment("在该公司每消费多少票据，增加 1 点声望")
            .defineInRange("economy.scrip_per_reputation_point", 1000, 1, 1_000_000);

    // ---------------- 工厂 ----------------
    public static final ModConfigSpec.IntValue ASSEMBLY_SECONDS_LOW = BUILDER
            .comment("低级工厂组装一个物理结构需要多少秒")
            .defineInRange("factory.assembly_seconds_low", 20, 1, 3600);

    public static final ModConfigSpec.IntValue ASSEMBLY_SECONDS_MID = BUILDER
            .comment("中级工厂组装秒数")
            .defineInRange("factory.assembly_seconds_mid", 12, 1, 3600);

    public static final ModConfigSpec.IntValue ASSEMBLY_SECONDS_HIGH = BUILDER
            .comment("高级工厂组装秒数")
            .defineInRange("factory.assembly_seconds_high", 6, 1, 3600);

    public static final ModConfigSpec.BooleanValue REQUIRE_CARD_FOR_MACHINES = BUILDER
            .comment("机器是否必须插着银行卡才能「花钱」（买商品 / 组装 / 收购结算）。",
                    "界面本身不再要求插卡（老板要求随时能打开界面）；关掉这项等于取消所有插卡限制。")
            .define("factory.require_bank_card", true);

    public static final ModConfigSpec.IntValue ASSEMBLY_FEE_PER_BLOCK = BUILDER
            .comment("组装费：蓝图里每个方块收多少票据（材料另算）")
            .defineInRange("factory.assembly_fee_per_block", 20, 0, 100000);

    public static final ModConfigSpec.IntValue MAX_BLUEPRINT_BLOCKS = BUILDER
            .comment("单份蓝图允许的最大方块数（防止有人放超大蓝图卡服务器）")
            .defineInRange("factory.max_blueprint_blocks", 8000, 16, 200000);

    public static final ModConfigSpec.IntValue ASSEMBLY_MAX_RADIUS = BUILDER
            .comment("组装好的结构最多离工厂多远（格）；地图选投放点的范围也是它")
            .defineInRange("factory.assembly_max_radius", 96, 4, 256);

    public static final ModConfigSpec.BooleanValue ASSEMBLE_AS_PHYSICS = BUILDER
            .comment("工厂组装完成后是否直接变成物理结构（需要装 Sable；关掉就只放方块）")
            .define("factory.assemble_as_physics", true);

    public static final ModConfigSpec.BooleanValue CREATIVE_CRATE_INFINITE = BUILDER
            .comment("工厂装配核心附近有【机械动力·创造板条箱】时：视为材料无限、不收组装费、立刻放置",
                    "关掉就恢复成「要材料 + 要钱 + 要等」的正常流程")
            .define("factory.creative_crate_infinite", true);

    public static final ModConfigSpec.IntValue CREATIVE_CRATE_RANGE = BUILDER
            .comment("找创造板条箱的半径（格）：以工厂装配核心为中心的正方体范围")
            .defineInRange("factory.creative_crate_range", 5, 1, 32);

    // ---------------- 收购站 ----------------
    /**
     * 收购节奏：每多少 tick 收一批（一格里的一批）。
     *
     * <p>老板定稿 = 10（每 0.5 秒收一批），这样投送动画（0.21 秒）放得完再收下一批。</p>
     */
    public static final ModConfigSpec.IntValue BUYER_TICKS_PER_BATCH = BUILDER
            .comment("收购站每多少 tick 收一批（一格里的一批）",
                    "10 = 每 0.5 秒收一批（老板定稿，配合投送动画）",
                    "1 = 每 tick 都收（很快，但动画会跟不上）")
            .defineInRange("buyer.ticks_per_batch", 10, 1, 200);

    public static final ModConfigSpec.IntValue BUYER_BATCHES_PER_TICK = BUILDER
            .comment("【已弃用】旧参数，保留只为兼容老配置文件；实际节奏看 buyer.ticks_per_batch")
            .defineInRange("buyer.batches_per_tick", 1, 1, 100);

    /** 单台收购机每 tick 最多处理多少个物品；超出的留在输入格排队。 */
    public static final ModConfigSpec.IntValue BUYER_ITEMS_PER_TICK = BUILDER
            .comment("单台收购投送机每 tick 最多处理多少个物品",
                    "64 = 每秒最多 1280 个；超出的留在输入格排队，不会丢")
            .defineInRange("buyer.items_per_tick", 64, 1, 10000);

    /** 全服所有收购机每 tick 合计最多处理多少个物品；超出的排队。 */
    public static final ModConfigSpec.IntValue BUYER_GLOBAL_ITEMS_PER_TICK = BUILDER
            .comment("全服所有收购投送机每 tick 合计最多处理多少个物品",
                    "2000 = 防多个收购机同时抢服务器；超出的留在各自输入格排队")
            .defineInRange("buyer.global_items_per_tick", 2000, 1, 1_000_000);

    public static final ModConfigSpec.IntValue PHYSICS_MAX_BLOCKS = BUILDER
            .comment("物理化时最多收集多少方块（太大的结构不物理化，避免卡服）")
            .defineInRange("factory.physics_max_blocks", 4096, 64, 100000);

    // ---------------- 焊接台 ----------------
    public static final ModConfigSpec.IntValue WELDER_MAX_BLOCKS = BUILDER
            .comment("焊接台一次最多把多少方块焊成一个物理结构")
            .defineInRange("welder.max_blocks", 512, 8, 20000);

    public static final ModConfigSpec.IntValue WELDER_RADIUS = BUILDER
            .comment("焊接台的搜索范围（格）")
            .defineInRange("welder.radius", 24, 2, 128);

    // ---------------- 界面主题（客户端） ----------------
    /**
     * 用哪套界面主题。
     *
     * <p>目录约定见 {@code ThemeManager}：{@code default} 是模组内置的预设，
     * {@code theme/custom/<名字>/} 是玩家自定义主题（每个文件夹一套，里面要有 index.html）。
     * 填的名字不存在时<b>安静回退到 default</b>。</p>
     */
    public static final ModConfigSpec.ConfigValue<String> UI_THEME = BUILDER
            .comment("界面主题：default = 模组内置预设；也可以填 theme/custom/ 下的文件夹名",
                    "找不到就自动回退到 default（不会报错）")
            .define("ui.theme", "default");

    /**
     * 界面主题总开关。
     *
     * <p>关掉 = 所有界面都用原来的原生界面，主题文件完全不读
     * （排查"到底是主题的问题还是模组的问题"时很有用）。</p>
     */
    public static final ModConfigSpec.BooleanValue UI_THEME_ENABLED = BUILDER
            .comment("要不要用 HTML 主题画界面",
                    "true = 用主题（默认）；false = 全部界面用原生界面",
                    "主题写坏了导致界面打不开时，把它改成 false 就能先进游戏")
            .define("ui.theme_enabled", true);

    /**
     * 「界面主题」按钮（背包界面里那颗圆形按钮）的位置。
     *
     * <p>老板 2026-10-17：默认放在<b>屏幕底部居中</b>（左上角会和 FTB 这类模组的按钮打架）；
     * 右键长按可以拖、<b>鼠标中键</b>可以把按钮一把搬到鼠标位置，都会记在这里。</p>
     *
     * <p>{@code -1} = <b>还没摆过</b>，按屏幕尺寸自动放底部居中
     * （见 {@code ThemeButton.loadPosition}）。换了分辨率也不会跑到屏幕外 ——
     * {@code ThemeButton.clampTo} 会把它拉回可视范围。</p>
     */
    public static final ModConfigSpec.IntValue THEME_BUTTON_X = BUILDER
            .comment("「界面主题」按钮的 X 坐标（-1 = 自动放底部居中；拖拽/中键摆放会自动写入）")
            .defineInRange("ui.theme_button_x", -1, -10000, 10000);

    public static final ModConfigSpec.IntValue THEME_BUTTON_Y = BUILDER
            .comment("「界面主题」按钮的 Y 坐标（-1 = 自动放底部居中；拖拽/中键摆放会自动写入）")
            .defineInRange("ui.theme_button_y", -1, -10000, 10000);

    /**
     * <b>已启用的主题列表</b>（顺序有意义：<b>最后一个</b>渲染那些没有单独指定的界面）。
     *
     * <p>资源包式的"启用"逻辑：在主题选择界面里点左边的主题 → 它跑到右边（启用）；
     * 点右边已启用的 → 取消。逗号分隔，由主题选择界面自己维护，手改也可以。</p>
     */
    public static final ModConfigSpec.ConfigValue<String> UI_THEME_LIST = BUILDER
            .comment("已启用的主题（逗号分隔；最后一个负责渲染没有单独指定的界面）",
                    "由「界面主题」界面自动维护，一般不用手改")
            .define("ui.theme_list", "default");

    /**
     * <b>高级：每个界面单独指定主题</b>。
     *
     * <p>格式 {@code 界面key=主题名;界面key=主题名}，例如
     * {@code buyer=我的主题;market=默认主题}。界面 key 见 {@code WebScreenId.key()}
     * （{@code factory_terminal} / {@code buyer} / … ）。</p>
     *
     * <p><b>非空时</b>：没列出来的界面一律用 {@code default}（这正是"高级设置"的语义）；
     * <b>为空时</b>：所有界面用 {@link #UI_THEME_LIST} 的最后一个。</p>
     *
     * <p>⚠️ 在主题选择界面里"启用其他主题"会<b>清空</b>这一项（老板 2026-10-18 定的规则）。</p>
     */
    public static final ModConfigSpec.ConfigValue<String> UI_THEME_ASSIGN = BUILDER
            .comment("高级界面设置：界面key=主题名，用分号分隔（非空时未列出的界面一律用 default）",
                    "由「界面主题 → 高级界面设置」自动维护")
            .define("ui.theme_assign", "");

    /** SPEC 必须在所有配置项之后（下面这行必须在最后） */
    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }
}
