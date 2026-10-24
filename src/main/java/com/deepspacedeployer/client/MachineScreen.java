package com.deepspacedeployer.client;

import com.deepspacedeployer.Company;
import com.deepspacedeployer.Tier;
import com.deepspacedeployer.blockentity.MachineBlockEntity;
import com.deepspacedeployer.blockentity.MachineKind;
import com.deepspacedeployer.economy.CardData;
import com.deepspacedeployer.factory.BlueprintInfo;
import com.deepspacedeployer.menu.MachineMenu;
import com.deepspacedeployer.net.ModuleListPayload;
import com.deepspacedeployer.shop.TradeEntry;
import com.deepspacedeployer.ui.webview.ThemeData;
import com.deepspacedeployer.ui.webview.ThemeSlotLayout;
import com.deepspacedeployer.ui.webview.WebScreenId;
import com.deepspacedeployer.ui.webview.WebViewManager;
import com.deepspacedeployer.ui.webview.WebViewScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 四种功能方块共用的界面。
 *
 * <ul>
 *   <li><b>工厂终端</b>：账户信息 + 商品列表（可翻页购买）</li>
 *   <li><b>收购站</b>：账户信息 + 3×3 投料格 + 本公司收购价</li>
 *   <li><b>工厂核心</b>：两页 —— 蓝图列表（选蓝图）/ 材料清单（投料 + 开始组装 + 进度条）</li>
 *   <li><b>寄售市场</b>：逛市场 / 我要卖</li>
 * </ul>
 *
 * <h2>界面主题（内置 WebView）怎么接进来的</h2>
 *
 * <p>老板 2026-10-16 的要求：这些界面的外观要能由玩家用 HTML+CSS 自己写。
 * 做法是<b>不动原来的原生界面</b>，而是在它上面叠一层主题：</p>
 *
 * <ul>
 *   <li>{@link #webview} 加载主题里对应的 html（四个方块四个文件，见 {@link WebScreenId}）；</li>
 *   <li>主题能用 → {@code render()} 里正常画完原生界面之后，把主题盖上去；</li>
 *   <li>主题<b>不能</b>用（文件缺了 / 写坏了 / 配置关了）→ {@link WebViewScreen#visible()} 为 false，
 *       一切照旧走原生界面 —— <b>绝不能因为主题坏了打不开界面</b>；</li>
 *   <li>点击：主题先处理（{@code data-action}），没命中才交给原生控件；</li>
 *   <li>数据变化：{@link #syncTheme} 里比对"上次喂给主题的快照"，变了才重新绑定
 *       （主题是静态快照，不是活动绑定）。</li>
 * </ul>
 */
public class MachineScreen extends AbstractContainerScreen<MachineMenu> {

    private static final int BG_OUTER = 0xFF11111A;
    private static final int BG_INNER = 0xFF262633;
    private static final int ROW_BG = 0xFF1C1C26;
    private static final int ROW_BG_LOCKED = 0xFF241A1A;
    private static final int ROW_BG_SELECTED = 0xFF1B2A1E;
    private static final int SLOT_BG = 0xFF0C0C12;
    private static final int TEXT = 0xFFE8F4FF;
    private static final int TEXT_DIM = 0xFF98A2B3;
    private static final int ACCENT = 0xFF54C8FF;
    private static final int WARN = 0xFFFF7A7A;
    private static final int GOLD = 0xFFFFD166;
    private static final int GOOD = 0xFF7CE38B;

    /** 列表一屏显示几行（商店 / 收购站共用，其余滚轮看） */
    private static final int LIST_ROWS = 3;
    /** 工厂蓝图一屏显示几行 */
    private static final int BP_ROWS = 2;
    /** 收购站收购价一屏显示几行 */
    private static final int SELL_ROWS = 3;

    /** 工厂核心：0 = 蓝图页，1 = 材料页（纯客户端状态） */
    private int corePage;
    /** 材料页一屏几行 + 列表底部（滑条/提示都在它下面） */
    private static final int MAT_ROWS = 6;
    private static final int MAT_BOTTOM = 84 + MAT_ROWS * 12;
    /** 市场"逛市场"页一屏几行（带图标，行高 20） */
    private static final int MARKET_ROWS = 4;
    /** 剪贴板格在界面上的位置（必须和 MachineMenu 里那个槽位一致） */
    private static final int CLIPBOARD_SLOT_X = 245;
    private static final int CLIPBOARD_SLOT_Y = 44;
    /** 上一次见到的蓝图数量：首次打开时数据还没到，到了要重建控件（否则「选」按钮不出现） */
    private int lastBlueprintCount = -1;

    // ---------------- 寄售市场的界面状态 ----------------
    /** 0 = 逛市场（挂单列表），1 = 我要卖（发单面板 + 图纸选择） */
    private int marketPage;
    /** 我要卖：挂的是不是载具（false = 挂待售格里的物品） */
    private boolean marketVehicle;
    /** 我要卖：定价是不是以物易物（false = 以币） */
    private boolean marketBarter;
    /** 「收哪家公司」按钮选中的公司序号（以币模式用） */
    private int payCompany;
    /** 价格输入框（以币模式） */
    private EditBox priceBox;
    /** 上一次见到的挂单版本，用来决定要不要重建列表控件 */
    private int lastMarketVersion = -1;
    /** 工厂核心蓝图页的搜索框 + 当前搜索词（按名字过滤图纸） */
    private EditBox blueprintFilter;
    private String blueprintQuery = "";

    /** 收购站的"自动收购"开关按钮：它的文字要跟着服务端状态变，所以得留引用 */
    private Button autoSellButton;
    private boolean lastAutoSell;
    /** 上一次见到的价目表版本（库存变化也要重建购买按钮） */
    private int lastShopVersion = -1;
    /** 模块打印机预设列表版本 */
    private int lastModuleVersion = -1;
    /** 收购站的"结算公司"按钮：3 个等级都不归公司，界面里选按哪家结算 */
    private Button buyerCompanyButton;
    private int lastBuyerCompany = -1;

    // ---------------- 界面主题 ----------------
    /** 主题宿主：四个方块共用这个类，但读的 html 不一样（按 kind 挑） */
    private WebViewScreen webview;
    /** 上一次喂给主题的数据快照（变了才重新绑定，免得每帧重解析 HTML） */
    private String themeSnapshot = "";

    private static String labelFor(boolean autoSell) {
        return autoSell
                ? "gui.deepspacedeployer.buyer_auto_on_label"
                : "gui.deepspacedeployer.buyer_auto_off_label";
    }

    /** 收购机的“按哪家公司结算”按钮：文案不写死，服务端切换后要能更新。 */
    private Component buyerCompanyLabel() {
        String text = Component.translatable("gui.deepspacedeployer.buyer_company",
                company().displayName()).getString();
        return Component.literal(fit(text, 110));
    }

    public MachineScreen(MachineMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 288;
        // 界面高度：
        //   ★ 2026-10-16 从 270 加到 300 —— 一个窗口同时要装"原版背包（固定在最下面 76px）"
        //     和"主题画的上半部分"。270 高时上面只剩 186px，主题的标题条+信息栏+槽位行+
        //     按钮行+列表塞不下（会压到背包上、最后一行被裁）。加到 300 后上面有 216px，
        //     默认主题正好排得下（见 theme.default 的 theme.css 里"版面预算"那段注释）。
        //   ⚠️ 主题里的 .player 用 bottom:8 定位，所以改这里【不用】同步改主题。
        this.imageHeight = 300;
        // 不画"物品栏"那行字：终端底部的翻页按钮要用那块地方
        this.inventoryLabelY = -1000;
    }

    /** 这个方块种类对应主题里的哪个 html */
    private WebScreenId screenId() {
        return switch (this.menu.kind()) {
            case TERMINAL -> WebScreenId.FACTORY_TERMINAL;
            case BUYER -> WebScreenId.BUYER;
            case CORE -> WebScreenId.FACTORY_CORE;
            case MODULE_PRINTER -> WebScreenId.MODULE_PRINTER;
            case MARKET -> WebScreenId.MARKET;
        };
    }

    // ---------------------------------------------------------------- 主题：装配

    private void setupTheme() {
        // 模块打印机暂时用原生界面；没有 module_printer.html 时不要让主题层盖住原生控件
        if (this.menu.kind() == MachineKind.MODULE_PRINTER) {
            return;
        }
        if (this.webview == null) {
            this.webview = new WebViewScreen(screenId());
            this.webview.setActionSink(this::onThemeAction);
        }
        this.webview.setEnabled(WebViewManager.enabled());
        this.webview.size(this.imageWidth, this.imageHeight);
        this.webview.at(this.leftPos, this.topPos);
        this.themeSnapshot = "";
        syncTheme();
    }

    /**
     * 数据变了才重新绑定主题。
     *
     * <p>快照用字符串拼（够用且便宜）：内容一样就跳过，避免每帧重解析一遍 HTML。</p>
     */
    private void syncTheme() {
        if (this.webview == null || !this.webview.enabled()) {
            return;
        }
        if (this.webview.themeChanged()) {
            this.themeSnapshot = "";          // 主题换了 → 强制重绑
        }
        String snapshot = themeSignature();
        if (snapshot.equals(this.themeSnapshot) && this.webview.visible()) {
            return;
        }
        this.themeSnapshot = snapshot;
        this.webview.reload(this::buildThemeData);
        applyThemeSlotLayout();
    }

    /**
     * 把原版物品格摆到主题标记的位置上（{@code data-slot="…"}）。
     *
     * <p>⚠️ <b>不调这个就会错位</b>：主题画自己的框，原版槽位却停在 {@link MachineMenu}
     * 里写死的坐标，两边差几十像素 —— 物品飘在框外、3×3 格子和画的框对不齐
     * （老板 2026-10-19 实机截图里的"错位"就是这个）。</p>
     *
     * <p>槽位索引布局见 {@code MachineMenu.setupSlots()}：
     * 0 = 银行卡；1..9 = 3×3 投料格；CORE 的 10 = 剪贴板；
     * MARKET 的 10/11/12 = 待售/以物易物/取货；最后 36 格固定是玩家背包（3×9 + 快捷栏）。</p>
     */
    private void applyThemeSlotLayout() {
        if (this.webview == null || !this.webview.visible()) {
            return;
        }
        var slots = this.menu.slots;
        int playerStart = slots.size() - 36;
        if (playerStart < 1) {
            return;
        }
        ThemeSlotLayout.place(this.webview, slots.get(0), "card");

        if (playerStart >= 10) {
            ThemeSlotLayout.grid(this.webview, slots.subList(1, 10), "input9", 3);
        }
        switch (this.menu.kind()) {
            case CORE -> {
                if (playerStart > 10) {
                    ThemeSlotLayout.place(this.webview, slots.get(10), "clipboard");
                }
            }
            case MARKET -> {
                if (playerStart >= 13) {
                    ThemeSlotLayout.place(this.webview, slots.get(10), "market_sell");
                    ThemeSlotLayout.place(this.webview, slots.get(11), "market_barter");
                    ThemeSlotLayout.place(this.webview, slots.get(12), "market_collect");
                }
            }
            default -> {
            }
        }

        var playerSlots = slots.subList(playerStart, slots.size());
        if (playerSlots.size() == 36) {
            ThemeSlotLayout.player(this.webview, playerSlots.subList(0, 27),
                    playerSlots.subList(27, 36));
        }
    }

    /** 快照：把所有会显示出来的数据拼成一行（变了就重新绑定主题） */
    private String themeSignature() {
        StringBuilder sb = new StringBuilder(64);
        sb.append(this.menu.kind()).append('|')
                .append(this.menu.online()).append(this.menu.hasAccount()).append('|')
                .append(this.menu.localBalance()).append('|')
                .append(this.menu.tierRank()).append(this.menu.reputation()).append('|')
                .append(this.menu.autoSell()).append(this.menu.blockedAbove()).append('|')
                .append(this.menu.hasAssemblyTarget()).append('|')
                .append(this.menu.creative()).append('|')
                .append(this.corePage).append(this.marketPage).append(this.marketBarter)
                .append(this.marketVehicle).append(this.payCompany).append('|')
                .append(this.scroll).append('|')
                .append(buyOffers().size()).append(sellOffers().size())
                .append(blueprints().size()).append(materialRows().size())
                .append(marketListings().size());
        // 余额/进度这类数字直接进快照（变了要让主题跟着变）
        for (int i = 0; i < MachineMenu.DATA_COUNT; i++) {
            sb.append(',').append(this.menu.dataValue(i));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 主题：数据

    /** 拼一份喂给主题的数据（键名约定见 docs/开发文档/界面清单.md） */
    private ThemeData buildThemeData() {
        MachineKind kind = this.menu.kind();
        ThemeData d = new ThemeData();
        ItemStack card = this.menu.getSlot(0).getItem();
        Company company = company();
        boolean companyBound = kind.isCompanyBound();

        d.put("公司", kind == MachineKind.BUYER
                ? company.displayName().getString()
                : (companyBound ? company.displayName().getString() : "中立设施"));
        d.put("账户", CardData.accountName(card));
        d.put("余额", this.menu.localBalance());
        d.put("等级", Tier.byIndex(this.menu.tierRank()).displayName().getString());
        d.put("声望", this.menu.reputation());
        d.put("在线", this.menu.online());
        d.put("被挡", this.menu.blockedAbove());
        // 不工作的其它原因（没插卡 / 卡未绑定 / 自动收购关了 / 没有可收的东西）——
        // 以前全是静默的，主题这边也要能显示出来（buyer.html 用 data-if="未工作"）。
        boolean idle = this.menu.kind() == MachineKind.BUYER
                && this.menu.idleReason() != 0 && !this.menu.blockedAbove();
        d.put("未工作", idle);
        d.put("未工作原因", idle
                ? Component.translatable("gui.deepspacedeployer.idle_reason."
                        + this.menu.idleReason()).getString()
                : "");
        d.put("创意模式", this.menu.creative());
        d.put("有公司", companyBound || kind == MachineKind.BUYER);

        switch (kind) {
            case TERMINAL -> {
                d.put("页", this.scroll + 1);
                d.put("总页", Math.max(1, (buyOffers().size() + LIST_ROWS - 1) / LIST_ROWS));
                d.list("商品", shopRows(buyOffers()));
            }
            case BUYER -> {
                d.put("自动收购", this.menu.autoSell() ? "开" : "关");
                d.list("收购价", sellRows());
            }
            case CORE -> {
                d.put("蓝图页", this.corePage == 0);
                BlueprintInfo selected = selectedBlueprint();
                d.put("已选蓝图", selected == null ? "未选" : selected.name());
                d.put("组装费", "按机器上方那家公司的价格结算");
                d.put("投放点", this.menu.hasAssemblyTarget() ? "已选" : "默认（机器正面）");
                d.list("蓝图", blueprintRows());
                d.list("材料", materialRows());
            }
            case MODULE_PRINTER -> {
                d.put("投放点", this.menu.hasAssemblyTarget() ? "已选" : "默认（机器正面）");
                d.list("模块", moduleRows());
            }
            case MARKET -> {
                d.put("市场页", this.marketPage == 0);
                d.put("挂单数量", marketListings().size());
                d.put("是载具", this.marketVehicle);
                d.put("以物易物", this.marketBarter);
                d.put("收款方式", this.marketBarter ? "物品" : "余额");
                d.put("收款公司", Company.VALUES[Math.floorMod(this.payCompany, Company.VALUES.length)]
                        .displayName().getString());
                d.list("挂单", marketRows());
                d.list("图纸", blueprintRows());
            }
        }
        return d;
    }

    private List<Map<String, ?>> shopRows(List<TradeEntry> offers) {
        List<Map<String, ?>> rows = new ArrayList<>();
        for (int i = 0; i < offers.size(); i++) {
            TradeEntry e = offers.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("名称", itemName(e.itemId()).getString());
            row.put("单价", String.valueOf(e.price()));
            boolean locked = !isUnlocked(e);
            row.put("锁定", locked);
            row.put("锁定原因", locked ? lockReason(e) : "");
            row.put("下标", i);
            rows.add(row);
        }
        return rows;
    }

    /** 这一条商品为什么被锁（等级不够 / 声望不够） */
    private String lockReason(TradeEntry e) {
        Tier tier = Tier.byIndex(this.menu.tierRank());
        if (e.tier().rank() > this.menu.tierRank()) {
            return Component.translatable("gui.deepspacedeployer.lock_tier",
                    e.tier().displayName()).getString();
        }
        if (e.reputation() > this.menu.reputation()) {
            return Component.translatable("gui.deepspacedeployer.lock_rep",
                    e.reputation()).getString();
        }
        return Component.translatable(tier.displayName().getString()).getString();
    }

    private List<Map<String, ?>> sellRows() {
        List<Map<String, ?>> rows = new ArrayList<>();
        List<TradeEntry> offers = sellOffers();
        for (TradeEntry e : offers) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("名称", itemName(e.itemId()).getString());
            row.put("单价", String.valueOf(Math.max(1,
                    Math.round((double) e.price() / Math.max(1, e.count())))));
            row.put("库存", ClientShopCache.stockAt(company(), rows.size()));
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, ?>> moduleRows() {
        List<Map<String, ?>> rows = new ArrayList<>();
        for (ModuleListPayload.ModuleEntry module : moduleEntries()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("名称", module.display());
            row.put("价格", module.price());
            row.put("尺寸", module.sizeX() + "×" + module.sizeY() + "×" + module.sizeZ());
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, ?>> blueprintRows() {
        List<Map<String, ?>> rows = new ArrayList<>();
        List<BlueprintInfo> list = blueprints();
        BlueprintInfo selected = selectedBlueprint();
        for (int i = 0; i < list.size(); i++) {
            BlueprintInfo bp = list.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("名称", bp.name());
            row.put("尺寸", bp.sizeText());
            row.put("方块数", bp.totalBlocks());
            row.put("已选", selected != null && selected.name().equals(bp.name()));
            row.put("下标", i);
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, ?>> materialRows() {
        List<Map<String, ?>> rows = new ArrayList<>();
        BlueprintInfo bp = selectedBlueprint();
        if (bp == null) {
            return rows;
        }
        int index = 0;
        for (Map.Entry<String, Integer> e : bp.materials().entrySet()) {
            int need = e.getValue();
            int have = countInInput(e.getKey());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("名称", itemName(e.getKey()).getString());
            row.put("需要", need);
            row.put("已有", have);
            row.put("够", have >= need);
            row.put("下标", index++);
            rows.add(row);
        }
        return rows;
    }

    /** 3×3 投料格里现在有多少个这种物品（物品 id → 数量） */
    private int countInInput(String itemId) {
        Item want = itemName2Item(itemId);
        if (want == null || want == Items.AIR) {
            return 0;
        }
        int n = 0;
        for (int i = MachineBlockEntity.SLOT_INPUT_START;
             i < MachineBlockEntity.SLOT_INPUT_START + MachineBlockEntity.INPUT_SLOTS; i++) {
            if (i < this.menu.slots.size()) {
                ItemStack st = this.menu.getSlot(i).getItem();
                if (!st.isEmpty() && st.getItem() == want) {
                    n += st.getCount();
                }
            }
        }
        return n;
    }

    private static Item itemName2Item(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        return id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
    }

    private List<Map<String, ?>> marketRows() {
        List<Map<String, ?>> rows = new ArrayList<>();
        var player = Minecraft.getInstance().player;
        for (var listing : marketListings()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("物品", listing.title());
            row.put("数量", listing.kind() == com.deepspacedeployer.market.Listing.Kind.VEHICLE
                    ? 1 : listing.item().getCount());
            row.put("价格", listing.isBarter()
                    ? "⇄ " + listing.barterCount()
                    : String.valueOf(listing.price()));
            row.put("货币", listing.isBarter()
                    ? listing.barter().getHoverName().getString()
                    : (listing.company() == null ? "" : listing.company().cnName()));
            row.put("卖家", listing.sellerName());
            row.put("我的", player != null && listing.seller() != null
                    && listing.seller().equals(player.getUUID()));
            row.put("下标", rows.size());
            rows.add(row);
        }
        return rows;
    }

    // ---------------------------------------------------------------- 主题：动作

    /**
     * 主题里的按钮点下来了。
     *
     * <p>动作名约定（和 {@code theme/default/*.html} 里写的对齐）：
     * {@code buy:下标}、{@code pick:下标}、{@code cancel:下标}、{@code pick_blueprint:下标}、
     * {@code use:主题名}；不带参数的走 {@link #handleNamedAction}。</p>
     */
    private boolean onThemeAction(String action, com.deepspacedeployer.ui.theme.HtmlNode source) {
        if (action == null) {
            return false;
        }
        int colon = action.indexOf(':');
        String name = colon < 0 ? action : action.substring(0, colon);
        String argText = colon < 0 ? "" : action.substring(colon + 1).trim();
        int arg;
        try {
            arg = argText.isEmpty() ? -1 : Integer.parseInt(argText);
        } catch (NumberFormatException e) {
            arg = -1;
        }

        switch (name) {
            case "buy" -> {
                if (arg < 0) {
                    return false;
                }
                if (this.menu.kind() == MachineKind.MARKET) {
                    // 市场：「买」是一张挂单，走市场网络包（带挂单 id），不是菜单按钮通道
                    int index = this.scroll + arg;
                    List<com.deepspacedeployer.market.Listing> listings = marketListings();
                    if (index >= listings.size()) {
                        return false;
                    }
                    sendMarket(com.deepspacedeployer.net.MarketPayload.ACT_BUY,
                            listings.get(index).id(), 0, false, -1, 0L);
                    return true;
                }
                sendAction(MachineMenu.ACTION_BUY_BASE + this.scroll + arg);
                return true;
            }
            case "pick" -> {
                if (arg >= 0) {
                    sendAction(MachineMenu.ACTION_BLUEPRINT_BASE + this.scroll + arg);
                    return true;
                }
                return false;
            }
            case "cancel" -> {
                // 市场：撤自己的挂单（同一张挂单 id，动作换成撤单）
                int index = this.scroll + arg;
                List<com.deepspacedeployer.market.Listing> listings = marketListings();
                if (arg < 0 || index >= listings.size()) {
                    return false;
                }
                sendMarket(com.deepspacedeployer.net.MarketPayload.ACT_CANCEL,
                        listings.get(index).id(), 0, false, -1, 0L);
                return true;
            }
            case "pick_blueprint" -> {
                if (arg >= 0) {
                    sendAction(MachineMenu.ACTION_BLUEPRINT_BASE + arg);
                    return true;
                }
                return false;
            }
            case "page_prev" -> {
                shiftScroll(-1);
                return true;
            }
            case "page_next" -> {
                shiftScroll(1);
                return true;
            }
            case "switch_page" -> {
                if (this.menu.kind() == MachineKind.CORE) {
                    switchCorePage();
                } else if (this.menu.kind() == MachineKind.MARKET) {
                    this.marketPage = this.marketPage == 0 ? 1 : 0;
                    this.scroll = 0;
                    rebuildWidgets();
                }
                return true;
            }
            case "toggle_autosell" -> {
                sendAction(MachineMenu.ACTION_UI_BASE);
                return true;
            }
            case "sell_now" -> {
                sendAction(MachineMenu.ACTION_UI_BASE + 2);
                return true;
            }
            case "assemble" -> {
                sendAction(MachineMenu.ACTION_ASSEMBLE);
                return true;
            }
            case "open_transfer" -> {
                sendAction(MachineMenu.ACTION_OPEN_TRANSFER);
                return true;
            }
            case "pick_target" -> {
                openAssemblyMap();
                return true;
            }
            case "sell_item" -> {
                this.marketVehicle = false;
                rebuildWidgets();
                return true;
            }
            case "sell_vehicle" -> {
                this.marketVehicle = true;
                rebuildWidgets();
                return true;
            }
            case "toggle_barter" -> {
                this.marketBarter = !this.marketBarter;
                rebuildWidgets();
                return true;
            }
            case "pick_company" -> {
                this.payCompany = (this.payCompany + 1) % Company.VALUES.length;
                rebuildWidgets();
                return true;
            }
            case "post" -> {
                sendAction(MachineMenu.ACTION_UI_BASE + 3);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** 列表滚动一格（主题的「上一页/下一页」按钮，以及滚轮共用） */
    private void shiftScroll(int delta) {
        int max = maxScroll();
        if (max <= 0) {
            return;
        }
        int next = Math.max(0, Math.min(max, this.scroll + delta));
        if (next != this.scroll) {
            this.scroll = next;
            rebuildWidgets();
        }
    }

    @Override
    protected void init() {
        super.init();
        int x = this.leftPos;
        int y = this.topPos;
        // 滚轮位置统一在这里收进合法范围（列表变短时不会停在一个空位置）
        this.scroll = Math.max(0, Math.min(this.scroll, maxScroll()));

        switch (this.menu.kind()) {
            case TERMINAL -> {
                List<TradeEntry> offers = buyOffers();
                for (int row = 0; row < LIST_ROWS; row++) {
                    int index = this.scroll + row;
                    if (index >= offers.size()) {
                        break;
                    }
                    TradeEntry entry = offers.get(index);
                    int rowY = y + 92 + row * 22;
                    Button button = Button.builder(Component.translatable("gui.deepspacedeployer.buy_one"),
                                    b -> sendAction(MachineMenu.ACTION_BUY_BASE + index))
                            .bounds(x + 248, rowY, 32, 18)
                            .build();
                    button.active = isUnlocked(entry)
                            && ClientShopCache.stockAt(company(), index) > 0;
                    addRenderableWidget(button);
                }
                // 资金转化入口已经搬到收购站（老板要求）；商店这里不再放
                // 滑条模式：不再需要翻页按钮
            }
            case MODULE_PRINTER -> {
                addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.pick_target"),
                                b -> openAssemblyMap())
                        .bounds(x + 8, y + 50, 108, 18).build());
                List<ModuleListPayload.ModuleEntry> modules = moduleEntries();
                for (int row = 0; row < 5; row++) {
                    int index = this.scroll + row;
                    if (index >= modules.size()) {
                        break;
                    }
                    ModuleListPayload.ModuleEntry module = modules.get(index);
                    int rowY = y + 76 + row * 22;
                    Button print = Button.builder(Component.translatable(
                                    "gui.deepspacedeployer.module_print"),
                                    b -> sendAction(MachineMenu.ACTION_MODULE_PRINT_BASE + index))
                            .bounds(x + 240, rowY + 2, 40, 18).build();
                    print.active = this.menu.localBalance() >= module.price();
                    addRenderableWidget(print);
                }
            }
            case CORE -> {
                // 顶部横条（y+18..y+56）：页签 + 重读图纸。
                // 核心现在按公司分成 6 种方块，组装费直接从"这台核心所属公司"扣，
                // 所以这里不再需要"收/付哪家公司"按钮（老板要求去掉）。
                addRenderableWidget(Button.builder(Component.translatable(this.corePage == 0
                                ? "gui.deepspacedeployer.core_tab_material"
                                : "gui.deepspacedeployer.core_tab_blueprint"), b -> switchCorePage())
                        .bounds(x + 8, y + 18, 96, 16).build());                if (this.corePage == 0) {
                    // 「重读图纸」放顶部横条右边
                    addRenderableWidget(Button.builder(
                                    Component.translatable("gui.deepspacedeployer.blueprint_reload"),
                                    b -> sendAction(4))
                            .bounds(x + 210, y + 18, 70, 16).build());

                    // 搜索框：图纸多了以后按名字过滤（在提示行下面、列表上面）
                    this.blueprintFilter = new EditBox(this.font, x + 8, y + 76, 156, 14,
                            Component.translatable("gui.deepspacedeployer.blueprint_search_hint"));
                    this.blueprintFilter.setMaxLength(40);
                    this.blueprintFilter.setValue(this.blueprintQuery);
                    this.blueprintFilter.setResponder(text -> {
                        this.blueprintQuery = text == null ? "" : text;
                        this.scroll = 0;
                        rebuildWidgets();
                    });
                    addRenderableWidget(this.blueprintFilter);

                    List<BlueprintInfo> list = blueprints();
                    for (int row = 0; row < BP_ROWS; row++) {
                        int index = this.scroll + row;
                        if (index >= list.size()) {
                            break;
                        }
                        int rowY = y + 92 + row * 20;
                        addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.select"),
                                        b -> sendAction(MachineMenu.ACTION_BLUEPRINT_BASE + index))
                                .bounds(x + 248, rowY, 32, 18).build());
                    }
                } else {
                    // 一键组装：核心按公司分成 6 种方块，组装费直接按"这台核心所属公司"结算，
                    // 所以一个按钮就够（不再需要选公司）
                    addRenderableWidget(Button.builder(Component.translatable(
                                    "gui.deepspacedeployer.assemble"),
                                    b -> sendAction(MachineMenu.ACTION_ASSEMBLE))
                            .bounds(x + 8, y + 136, 68, 18).build());
                    addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.cancel"),
                                    b -> sendAction(3))
                            .bounds(x + 80, y + 136, 40, 18).build());

                    // 打开俯视图选投放点（纯客户端操作）
                    addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.pick_target"),
                                    b -> openAssemblyMap())
                            .bounds(x + 8, y + 158, 86, 18).build());
                }
            }

            case BUYER -> {
                // 收购机不归公司：3 个等级物品是固定的，按哪家公司结算在这里选。
                this.buyerCompanyButton = Button.builder(buyerCompanyLabel(),
                                b -> sendAction(3))
                        .bounds(x + 164, y + 18, 116, 18).build();
                addRenderableWidget(this.buyerCompanyButton);

                // 三个按钮必须分开摆：早先三个都放在 y+112，矩形互相包含，
                // 而原版命中是按"加入顺序"取第一个 → 视觉上最上层的「查看账户余额」点不到
                // （点下去落到自动收购开关上）。
                this.autoSellButton = button(labelFor(this.menu.autoSell()), x + 8, y + 96, 118, 1);
                addRenderableWidget(this.autoSellButton);
                addRenderableWidget(button("gui.deepspacedeployer.buyer_sell_now", x + 130, y + 96, 100, 2));
                // 「查看账户余额」按钮删掉了（老板说多余）：账户 / 余额 / 等级声望本来就一直显示在顶上
                // 「资金转化」按钮删掉了（老板 2026-10-19：换成货币银行，收购站不再有入口）
                // 收购清单从 y+140 开始画（在按钮下面），见 renderSellList
                this.lastAutoSell = this.menu.autoSell();
            }

            case MARKET -> {
                // 布局：左上角页签；右上角三个格子（x+199..239, y+25..83）；
                // 顶部 y+18..y+78 那一横条是留给「选公司」这类通用按钮的。
                addRenderableWidget(Button.builder(Component.translatable(this.marketPage == 0
                                ? "gui.deepspacedeployer.market_tab_sell"
                                : "gui.deepspacedeployer.market_tab_browse"), b -> switchMarketPage())
                        .bounds(x + 8, y + 18, 76, 16).build());
                // 「收哪家公司的余额」——无归属的市场靠这张挂单记的公司来结算
                addRenderableWidget(Button.builder(Component.translatable(
                                "gui.deepspacedeployer.pay_company", company().displayName()),
                                b -> {
                                    this.payCompany = (this.payCompany + 1) % Company.COUNT;
                                    rebuildWidgets();
                                })
                        .bounds(x + 88, y + 18, 108, 16).build());                if (this.marketPage == 0) {
                    List<com.deepspacedeployer.market.Listing> listings = marketListings();
                    for (int row = 0; row < MARKET_ROWS; row++) {
                        int index = this.scroll + row;
                        if (index >= listings.size()) {
                            break;
                        }
                        var listing = listings.get(index);
                        boolean mine = this.minecraft != null && this.minecraft.player != null
                                && listing.seller() != null
                                && listing.seller().equals(this.minecraft.player.getUUID());
                        int rowY = y + 88 + row * 20;
                        // 自己的单显示「撤单」，别人的显示「买」
                        addRenderableWidget(Button.builder(Component.translatable(mine
                                        ? "gui.deepspacedeployer.market_cancel"
                                        : "gui.deepspacedeployer.market_buy"),
                                        b -> sendMarket(mine
                                                ? com.deepspacedeployer.net.MarketPayload.ACT_CANCEL
                                                : com.deepspacedeployer.net.MarketPayload.ACT_BUY,
                                                listing.id(), 0, false, -1, 0L))
                                .bounds(x + 244, rowY, 36, 18).build());
                    }
                } else {
                    // 我要卖：挂物品 / 挂载具 + 收余额 / 收物品 + 价钱
                    // （"收余额"模式下，收款公司用上面那个「收哪家公司」按钮切换；
                    //   以物易物模式不看公司，但 marketMode() 照样带一个合法值）
                    addRenderableWidget(Button.builder(Component.translatable(this.marketVehicle
                                    ? "gui.deepspacedeployer.market_kind_vehicle"
                                    : "gui.deepspacedeployer.market_kind_item"),
                                    b -> {
                                        this.marketVehicle = !this.marketVehicle;
                                        this.scroll = 0;
                                        rebuildWidgets();
                                    })
                            .bounds(x + 8, y + 84, 92, 18).build());
                    addRenderableWidget(Button.builder(Component.translatable(this.marketBarter
                                    ? "gui.deepspacedeployer.market_pay_barter"
                                    : "gui.deepspacedeployer.market_pay_money"),
                                    b -> {
                                        this.marketBarter = !this.marketBarter;
                                        rebuildWidgets();
                                    })
                            .bounds(x + 104, y + 84, 92, 18).build());

                    addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.market_post_one"),
                                    b -> sendMarket(com.deepspacedeployer.net.MarketPayload.ACT_POST, 0L,
                                            marketMode(), false, -1, marketPrice()))
                            .bounds(x + 8, y + 106, 92, 18).build());

                    this.priceBox = new EditBox(this.font, x + 104, y + 106, 92, 18,
                            Component.translatable("gui.deepspacedeployer.market_price_hint"));
                    this.priceBox.setMaxLength(10);
                    this.priceBox.setFilter(text -> text.isEmpty() || text.matches("[0-9]{1,10}"));
                    this.priceBox.setValue("100");
                    addRenderableWidget(this.priceBox);

                    // 挂载具：列出图纸（点右边「挂这个」直接发单，用那份图纸）
                    if (this.marketVehicle) {
                        List<BlueprintInfo> blueprints = blueprints();
                        for (int row = 0; row < MARKET_ROWS; row++) {
                            int index = this.scroll + row;
                            if (index >= blueprints.size()) {
                                break;
                            }
                            int rowY = y + 130 + row * 16;
                            addRenderableWidget(Button.builder(Component.translatable(
                                            "gui.deepspacedeployer.market_pick"),
                                            b -> sendMarket(com.deepspacedeployer.net.MarketPayload.ACT_POST,
                                                    0L, marketMode(), true, index,
                                                    marketPrice()))
                                    .bounds(x + 244, rowY, 36, 14).build());
                        }
                    }
                }
            }
        }

        // ★ 原生控件建完了，最后装配界面主题：
        //   主题能用 → 它的绘制会盖在原生界面上面；主题不能用 → 什么都不发生。
        setupTheme();
    }

    // ---------------------------------------------------------------- 主题：接入渲染与输入

    /**
     * 画界面：原生画完，主题再盖上去。
     *
     * <p>顺序很重要：主题的框体和文字要盖住原生界面那些"老样式"的底板与标签，
     * 而<b>物品格</b>（背包、卡槽、投料格）仍然由原版画在下面 —— 所以主题里
     * 用 {@code data-slot} 标记位置，就能和这些格子对上。</p>
     */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        syncTheme();
        applyThemeSlotLayout();   // 每帧对齐（便宜；保证主题加载时机不影响）
        super.render(graphics, mouseX, mouseY, partialTick);
        if (this.webview != null && this.webview.visible()) {
            this.webview.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 主题先处理（data-action）；没命中才轮到原生控件 —— 这样主题里画的按钮
        // 才不会被原生按钮"抢"走点击。
        if (this.webview != null && this.webview.mouseClicked(mouseX, mouseY, button)) {
            syncTheme();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // 主题里的可滚动容器（overflow:scroll）先吃掉滚轮
        if (this.webview != null && this.webview.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        // 搜索框在鼠标底下时不抢滚轮（EditBox 自己要用）
        if (this.blueprintFilter != null && this.blueprintFilter.isMouseOver(mouseX, mouseY)) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        int max = maxScroll();
        if (max <= 0) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        int next = Math.max(0, Math.min(max, this.scroll - (int) Math.signum(scrollY)));
        if (next != this.scroll) {
            this.scroll = next;
            rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** 材料页一共几行（当前蓝图要的材料种数） */
    private int materialRowCount() {
        BlueprintInfo selected = selectedBlueprint();
        return selected == null ? 0 : selected.materials().size();
    }

    /** 市场"价格输入框"里的数字（空/非法就 0） */
    private long marketPrice() {
        try {
            if (this.priceBox == null || this.priceBox.getValue().trim().isEmpty()) {
                return 0L;
            }
            return Math.max(0L, Long.parseLong(this.priceBox.getValue().trim()));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 市场定价模式（发给服务端的一个数）：
     * {@code 0} = 以物易物；{@code 1..6} = 以币，值是"收款公司序号 + 1"。
     *
     * <p>市场本身无归属，所以"收哪家公司的余额"必须跟着每张挂单走。</p>
     */
    private int marketMode() {
        return this.marketBarter ? 0 : this.payCompany + 1;
    }

    private void switchMarketPage() {
        this.marketPage = this.marketPage == 0 ? 1 : 0;
        this.scroll = 0;
        rebuildWidgets();
    }

    /** 市场操作走自己的包（服务端权威：钱和货都从服务端容器/存档取） */
    private void sendMarket(int action, long listing, int mode, boolean vehicle, int index, long amount) {
        if (this.minecraft == null || this.menu.dataValue(MachineMenu.DATA_SYNCED) != 1) {
            return;
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.deepspacedeployer.net.MarketPayload(
                        this.menu.dataValue(MachineMenu.DATA_POS_X),
                        this.menu.dataValue(MachineMenu.DATA_POS_Y),
                        this.menu.dataValue(MachineMenu.DATA_POS_Z),
                        action, listing, mode, vehicle, index, amount));
    }

    /**
     * 按最新数据刷新界面上的"文字"。
     *
     * <p>踩过的坑：控件的文字是在 {@code init()} 建按钮那一刻定死的，而这个界面只在
     * 打开/滚轮/切页时会重建控件 —— 所以"自动收购"开关点完之后**功能生效了、按钮上的字却不变**
     * （服务端数据早就同步过来了，只是没人去读它）。</p>
     *
     * <p>这里每帧比对一次，只在<b>真的变了</b>时改那一个按钮的文字 ——
     * 不调 rebuildWidgets()，因为渲染过程中重建控件会踩"控件正在被遍历"的坑，
     * 而且会把玩家正在输入的价格框/搜索框重建掉。</p>
     */
    private void syncWidgetLabels() {
        // 「选」按钮消失的真原因：首次打开时方块实体的数据还没同步到客户端，
        // init() 建按钮那一刻读到"0 份蓝图" → 一个按钮都没建；等数据到了也没人重建控件。
        // 所以这里盯住"蓝图数量变了"就重建一次（只在真的变了才重建，不会每帧刷）。
        if (this.menu.kind() == MachineKind.CORE && this.corePage == 0) {
            int count = blueprints().size();
            if (count != this.lastBlueprintCount) {
                this.lastBlueprintCount = count;
                this.scroll = Math.max(0, Math.min(this.scroll, maxScroll()));
                rebuildWidgets();
                return;
            }
        }
        if (this.menu.kind() == MachineKind.MODULE_PRINTER
                && ModulePresetClient.version() != this.lastModuleVersion) {
            this.lastModuleVersion = ModulePresetClient.version();
            this.scroll = 0;
            rebuildWidgets();
            return;
        }
        if (this.menu.kind() == MachineKind.TERMINAL
                && ClientShopCache.version() != this.lastShopVersion) {
            this.lastShopVersion = ClientShopCache.version();
            this.scroll = Math.max(0, Math.min(this.scroll, maxScroll()));
            rebuildWidgets();
            return;
        }
        if (this.menu.kind() == MachineKind.BUYER && this.buyerCompanyButton != null) {
            int companyIndex = this.menu.dataValue(MachineMenu.DATA_COMPANY);
            if (companyIndex != this.lastBuyerCompany) {
                this.lastBuyerCompany = companyIndex;
                this.buyerCompanyButton.setMessage(buyerCompanyLabel());
            }
        }
        if (this.menu.kind() != MachineKind.BUYER || this.autoSellButton == null) {
            // 市场：挂单列表变了（别人挂/买/撤）就重建一次列表按钮
            if (this.menu.kind() == MachineKind.MARKET
                    && ClientMarketCache.version() != this.lastMarketVersion) {
                this.lastMarketVersion = ClientMarketCache.version();
                this.scroll = Math.max(0, Math.min(this.scroll, maxScroll()));
                rebuildWidgets();
            }
            return;
        }
        boolean autoSell = this.menu.autoSell();
        if (autoSell == this.lastAutoSell) {
            return;
        }
        this.lastAutoSell = autoSell;
        this.autoSellButton.setMessage(Component.translatable(labelFor(autoSell)));
    }

    /** 按像素宽度截断文字（超长加省略号），避免文字超出界面 */
    private String fit(String text, int maxWidth) {
        if (text == null) {
            return "";
        }
        if (this.font.width(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "…";
        int limit = maxWidth - this.font.width(ellipsis);
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (this.font.width(sb.toString() + c) > limit) {
                break;
            }
            sb.append(c);
        }
        return sb + ellipsis;
    }

    /** 右对齐画一段文字（给价格用，避免和左边的名字撞上） */
    private void drawRight(GuiGraphics graphics, Component text, int rightX, int y, int color) {
        graphics.drawString(this.font, text, rightX - this.font.width(text), y, color, false);
    }

    /** 列表滚动位置（滚轮控制，替代翻页） */
    private int scroll = 0;

    /** 当前列表最多能滚多少行 */
    private int maxScroll() {
        int size = switch (this.menu.kind()) {
            case TERMINAL -> buyOffers().size();
            case BUYER -> sellOffers().size();
            case MODULE_PRINTER -> moduleEntries().size();
            case CORE -> this.corePage == 0 ? blueprints().size() : materialRowCount();
            // 市场：0 = 逛市场（挂单列表），1 = 我要卖（可选图纸列表）
            case MARKET -> this.marketPage == 0 ? marketListings().size() : marketPickerRows();
        };
        return Math.max(0, size - visibleRows());
    }

    /** 市场"我要卖"页：图纸选择列表的行数（挂物品时是 0，不需要滑条） */
    private int marketPickerRows() {
        return this.marketVehicle ? blueprints().size() : 0;
    }

    /** 一屏能显示几行 */
    private int visibleRows() {
        return switch (this.menu.kind()) {
            case TERMINAL -> LIST_ROWS;
            case BUYER -> SELL_ROWS;
            case MODULE_PRINTER -> 5;
            case CORE -> this.corePage == 0 ? BP_ROWS : MAT_ROWS;
            case MARKET -> this.marketPage == 0 ? MARKET_ROWS : MARKET_ROWS;
        };
    }

    /** 在右侧画一根滑条（列表比一屏长时才画） */
    private void drawScrollbar(GuiGraphics graphics, int topY, int height, int total, int visible) {
        if (total <= visible) {
            return;
        }
        int x = this.imageWidth - 6;
        graphics.fill(x, topY, x + 3, topY + height, 0xFF101018);
        int thumb = Math.max(12, height * visible / total);
        int travel = height - thumb;
        int offset = this.scroll * travel / Math.max(1, total - visible);
        graphics.fill(x, topY + offset, x + 3, topY + offset + thumb, ACCENT);
    }

    private void switchCorePage() {
        this.corePage = this.corePage == 0 ? 1 : 0;
        this.scroll = 0;
        rebuildWidgets();
    }

    /** 蓝图页的"重读图纸"是走服务端按钮通道的（服务端重新扫文件夹） */

    /** 打开俯视图选投放点 */
    private void openAssemblyMap() {
        if (this.minecraft == null || this.menu.dataValue(MachineMenu.DATA_SYNCED) != 1) {
            return;
        }
        BlockPos pos = new BlockPos(this.menu.dataValue(MachineMenu.DATA_POS_X),
                this.menu.dataValue(MachineMenu.DATA_POS_Y),
                this.menu.dataValue(MachineMenu.DATA_POS_Z));
        this.minecraft.setScreen(new AssemblyMapScreen(pos));
    }

    private Button button(String translationKey, int x, int y, int width, int actionId) {
        return Button.builder(Component.translatable(translationKey), b -> sendAction(actionId))
                .bounds(x, y, width, 18)
                .build();
    }

    private void sendAction(int actionId) {
        if (this.minecraft != null && this.minecraft.gameMode != null) {
            this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, actionId);
        }
    }

    // ---------------- 服务端把卡递过来（从资金转化界面返回时） ----------------

    /**
     * 把服务端插回来的卡显示在卡槽里（只画，不碰容器）。
     *
     * <p>机器界面这一侧由服务端直接改方块实体，所以这里只是"补显示"，
     * 让玩家不用重新开关界面就能看到卡回来了。</p>
     */
    public void acceptCard(ItemStack card) {
        if (card == null || card.isEmpty()) {
            return;
        }
        this.menu.getSlot(0).set(card.copyWithCount(1));
    }

    // ---------------- 数据 ----------------

    private Company company() {
        return Company.byIndex(this.menu.dataValue(MachineMenu.DATA_COMPANY));
    }

    private List<TradeEntry> buyOffers() {
        return ClientShopCache.buyOffers(company());
    }

    private List<ModuleListPayload.ModuleEntry> moduleEntries() {
        return ModulePresetClient.visible(company(), Tier.byIndex(this.menu.tierRank()));
    }

    private List<TradeEntry> sellOffers() {
        return ClientShopCache.sellOffers(company());
    }

    /** 市场挂单列表（服务端推过来的那份） */
    private List<com.deepspacedeployer.market.Listing> marketListings() {
        return ClientMarketCache.listings();
    }

    private MachineBlockEntity clientBlockEntity() {
        if (this.menu.dataValue(MachineMenu.DATA_SYNCED) != 1) {
            return null;
        }
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        BlockPos pos = new BlockPos(this.menu.dataValue(MachineMenu.DATA_POS_X),
                this.menu.dataValue(MachineMenu.DATA_POS_Y),
                this.menu.dataValue(MachineMenu.DATA_POS_Z));
        return level.getBlockEntity(pos) instanceof MachineBlockEntity be ? be : null;
    }

    private List<BlueprintInfo> blueprints() {
        MachineBlockEntity be = clientBlockEntity();
        if (be == null) {
            return List.of();
        }
        List<BlueprintInfo> all = be.blueprints();
        if (this.blueprintQuery.isBlank()) {
            return all;
        }
        String needle = this.blueprintQuery.trim().toLowerCase(java.util.Locale.ROOT);
        List<BlueprintInfo> out = new ArrayList<>();
        for (BlueprintInfo info : all) {
            if (info.name().toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                out.add(info);
            }
        }
        return out;
    }

    /** 图纸总数（搜索框下面显示 "3 / 10" 用） */
    private int blueprintTotal() {
        MachineBlockEntity be = clientBlockEntity();
        return be == null ? 0 : be.blueprints().size();
    }

    private BlueprintInfo selectedBlueprint() {
        MachineBlockEntity be = clientBlockEntity();
        if (be == null || be.selectedBlueprint().isEmpty()) {
            return null;
        }
        for (BlueprintInfo info : be.blueprints()) {
            if (info.name().equals(be.selectedBlueprint())) {
                return info;
            }
        }
        return null;
    }

    private boolean isUnlocked(TradeEntry entry) {
        return entry.tier().rank() <= this.menu.tierRank() && entry.reputation() <= this.menu.reputation();
    }

    private static Component itemName(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
        return item == Items.AIR ? Component.literal(itemId) : item.getDescription();
    }

    // ---------------- 绘制 ----------------

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;
        graphics.fill(x, y, x + this.imageWidth, y + this.imageHeight, BG_OUTER);
        graphics.fill(x + 1, y + 1, x + this.imageWidth - 1, y + this.imageHeight - 1, BG_INNER);
        graphics.fill(x + 1, y + 15, x + this.imageWidth - 1, y + 16, ACCENT);

        graphics.fill(x + 7, y + 19, x + 25, y + 37, SLOT_BG);

        if (this.menu.kind() == MachineKind.BUYER || this.menu.kind() == MachineKind.CORE) {
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) {
                    int sx = x + 7 + col * 18;
                    int sy = y + 43 + row * 18;
                    graphics.fill(sx, sy, sx + 18, sy + 18, SLOT_BG);
                }
            }
        }

        // 市场：三个格子的底（待售 / 需求 / 取货）+ 列表行底
        if (this.menu.kind() == MachineKind.MARKET) {
            graphics.fill(x + 199, y + 25, x + 217, y + 43, SLOT_BG);
            graphics.fill(x + 199, y + 45, x + 217, y + 63, SLOT_BG);
            graphics.fill(x + 221, y + 65, x + 239, y + 83, SLOT_BG);
            for (int row = 0; row < MARKET_ROWS; row++) {
                if (this.marketPage == 0) {
                    int rowY = y + 87 + row * 20;
                    graphics.fill(x + 6, rowY, x + 244, rowY + 20, ROW_BG);
                } else if (this.marketVehicle) {
                    int rowY = y + 129 + row * 16;
                    graphics.fill(x + 6, rowY, x + 244, rowY + 16, ROW_BG);
                }
            }
        }

        if (this.menu.kind() == MachineKind.TERMINAL) {
            List<TradeEntry> offers = buyOffers();
            for (int row = 0; row < LIST_ROWS; row++) {
                int index = this.scroll + row;
                if (index >= offers.size()) {
                    break;
                }
                int rowY = y + 92 + row * 22;
                graphics.fill(x + 6, rowY, x + 244, rowY + 20,
                        isUnlocked(offers.get(index)) ? ROW_BG : ROW_BG_LOCKED);
            }
        }

        if (this.menu.kind() == MachineKind.CORE && this.corePage == 0) {
            List<BlueprintInfo> list = blueprints();
            BlueprintInfo selected = selectedBlueprint();
            for (int row = 0; row < BP_ROWS; row++) {
                int index = this.scroll + row;
                if (index >= list.size()) {
                    break;
                }
                int rowY = y + 92 + row * 20;
                boolean sel = selected != null && selected.name().equals(list.get(index).name());
                graphics.fill(x + 6, rowY, x + 136, rowY + 18, sel ? ROW_BG_SELECTED : ROW_BG);
            }
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderLabels(graphics, mouseX, mouseY);
        syncWidgetLabels();

        ItemStack card = this.menu.getSlot(0).getItem();
        Company company = company();
        boolean online = this.menu.online();
        String account = CardData.accountName(card);
        Tier tier = Tier.byIndex(this.menu.tierRank());

        boolean leftGrid = this.menu.kind() == MachineKind.BUYER || this.menu.kind() == MachineKind.CORE;
        int infoX = leftGrid ? 70 : 30;
        // 有归属的机器显示公司名；收购机显示当前结算公司；寄售市场显示"中立设施"
        graphics.drawString(this.font,
                (this.menu.kind().isCompanyBound() || this.menu.kind() == MachineKind.BUYER)
                        ? company.displayName()
                        : Component.translatable("gui.deepspacedeployer.market_unowned"),
                infoX, 19, ACCENT, false);
        graphics.drawString(this.font,
                Component.translatable(account.isEmpty()
                        ? "gui.deepspacedeployer.account_none"
                        : "gui.deepspacedeployer.account", account), infoX, 31, TEXT, false);
        graphics.drawString(this.font,
                Component.translatable("gui.deepspacedeployer.local_balance", this.menu.localBalance()),
                infoX, 43, TEXT, false);
        // 等级那一行：寄售市场没有等级，就只显示声望
        graphics.drawString(this.font, this.menu.kind().hasTier()
                        ? Component.translatable("gui.deepspacedeployer.tier_and_rep",
                        tier.displayName(), this.menu.reputation())
                        : Component.translatable("gui.deepspacedeployer.rep_and_card",
                        this.menu.reputation()),
                infoX, 55, TEXT, false);
        graphics.drawString(this.font,
                Component.translatable(online
                        ? "gui.deepspacedeployer.online"
                        : "gui.deepspacedeployer.offline"),
                8, 19 + 46, online ? TEXT : WARN, false);

        // ★ 收购投送机：头顶被方块挡住 → 不工作，这里明确告诉玩家为什么（老板要求）
        //   位置选在状态行（y=65）和按钮（y=96）之间的空档，不挡任何东西。
        if (this.menu.kind() == MachineKind.BUYER && this.menu.blockedAbove()) {
            graphics.drawString(this.font, Component.translatable(
                    "gui.deepspacedeployer.blocked_above"), 8, 78, WARN, false);
        } else if (this.menu.kind() == MachineKind.BUYER && this.menu.idleReason() != 0) {
            // ★ 不是"头顶被挡"的那些原因（没插卡 / 卡未绑定 / 自动收购关了 / 没有可收的东西）——
            //   这些以前全是静默的，玩家只能干瞪眼。现在直接写在同一个空档（y=78）里。
            graphics.drawString(this.font, Component.translatable(
                    "gui.deepspacedeployer.idle_reason",
                    Component.translatable("gui.deepspacedeployer.idle_reason."
                            + this.menu.idleReason())), 8, 78, WARN, false);
        }

        switch (this.menu.kind()) {
            case TERMINAL -> renderShopRows(graphics);
            case MODULE_PRINTER -> renderModules(graphics);
            case BUYER -> renderSellList(graphics);
            case CORE -> {
                renderCore(graphics);
                renderClipboardSlotHint(graphics);
            }
            case MARKET -> renderMarket(graphics);
        }
    }

    /** 核心界面的剪贴板格：画个底 + 一行说明（格子本身由原版画物品） */
    private void renderClipboardSlotHint(GuiGraphics graphics) {
        int sx = CLIPBOARD_SLOT_X;
        int sy = CLIPBOARD_SLOT_Y;
        graphics.fill(sx - 1, sy - 1, sx + 17, sy + 17, 0xFF101018);
        graphics.fill(sx, sy, sx + 16, sy + 16, 0xFF2A2A38);
        graphics.drawString(this.font, fit(Component.translatable(
                "gui.deepspacedeployer.clipboard_hint").getString(), 132),
                sx - 106, sy + 22, TEXT_DIM, false);
        graphics.drawString(this.font, fit(Component.translatable(
                "gui.deepspacedeployer.clipboard_export").getString(), 100),
                sx - 106, sy + 32, ACCENT, false);
    }

    private void renderModules(GuiGraphics graphics) {
        graphics.drawString(this.font, Component.translatable("gui.deepspacedeployer.target")
                        .append(": ")
                        .append(Component.translatable(this.menu.hasAssemblyTarget()
                                ? "gui.deepspacedeployer.target_chosen"
                                : "gui.deepspacedeployer.target_default")),
                122, 55, this.menu.hasAssemblyTarget() ? GOOD : TEXT_DIM, false);
        List<ModuleListPayload.ModuleEntry> modules = moduleEntries();
        if (modules.isEmpty()) {
            graphics.drawString(this.font, Component.translatable(
                    "gui.deepspacedeployer.module_empty"), 32, 82, TEXT_DIM, false);
            return;
        }
        for (int row = 0; row < 5; row++) {
            int index = this.scroll + row;
            if (index >= modules.size()) {
                break;
            }
            ModuleListPayload.ModuleEntry module = modules.get(index);
            int rowY = 76 + row * 22;
            boolean affordable = this.menu.localBalance() >= module.price();
            graphics.drawString(this.font, fit(module.display(), 116), 32, rowY + 3,
                    affordable ? TEXT : TEXT_DIM, false);
            graphics.drawString(this.font, module.sizeX() + "×" + module.sizeY() + "×" + module.sizeZ(),
                    148, rowY + 3, TEXT_DIM, false);
            drawRight(graphics, Component.translatable(
                    "gui.deepspacedeployer.price_tag", module.price()), 236, rowY + 3,
                    affordable ? GOLD : TEXT_DIM);
        }
    }

    private void renderShopRows(GuiGraphics graphics) {        List<TradeEntry> offers = buyOffers();
        if (offers.isEmpty()) {
            graphics.drawString(this.font, Component.translatable("gui.deepspacedeployer.shop_loading"),
                    8, 98, TEXT_DIM, false);
            return;
        }
        int start = this.scroll;
        for (int row = 0; row < LIST_ROWS; row++) {
            int index = start + row;
            if (index >= offers.size()) {
                break;
            }
            TradeEntry entry = offers.get(index);
            int rowY = 92 + row * 22;
            ItemStack stack = entry.createStack();
            graphics.renderItem(stack, 10, rowY + 2);
            graphics.renderItemDecorations(this.font, stack, 10, rowY + 2);

            boolean unlocked = isUnlocked(entry);
            graphics.drawString(this.font, fit(stack.getHoverName().getString(), 150), 32, rowY + 6,
                    unlocked ? TEXT : TEXT_DIM, false);
            drawRight(graphics, Component.translatable("gui.deepspacedeployer.price_tag", entry.price()),
                    244, rowY + 6, unlocked ? GOLD : TEXT_DIM);
            int stock = ClientShopCache.stockAt(company(), index);
            int maxStock = ClientShopCache.maxStock(company());
            int restock = ClientShopCache.restockRemaining(company());
            String stockText = Component.translatable(
                    "gui.deepspacedeployer.shop_stock", stock, maxStock).getString();
            if (restock > 0) {
                stockText += " · " + Component.translatable(
                        "gui.deepspacedeployer.restock_in",
                        String.format("%02d:%02d", restock / 60, restock % 60)).getString();
            }
            graphics.drawString(this.font, stockText,
                    190, rowY + 15, stock > 0 ? TEXT_DIM : WARN, false);

            if (!unlocked) {
                Component reason = entry.tier().rank() > this.menu.tierRank()
                        ? Component.translatable("gui.deepspacedeployer.lock_tier", entry.tier().displayName())
                        : Component.translatable("gui.deepspacedeployer.lock_rep", entry.reputation());
                graphics.drawString(this.font, reason, 32, rowY + 15, WARN, false);
            } else if (entry.reputation() > 0) {
                graphics.drawString(this.font, Component.translatable("gui.deepspacedeployer.rep_tag", entry.reputation()),
                        100, rowY + 15, TEXT_DIM, false);
            }
        }
        // 滑条（列表比一屏长时才有）
        drawScrollbar(graphics, 90, LIST_ROWS * 22, offers.size(), LIST_ROWS);
    }

    /**
     * 收购站：本公司按等级折算后的收购单价。
     *
     * <p>老板要求"其他两个方块也加上滑条" —— 这里也改成滚轮 + 右侧滑条，
     * 一屏 3 行，其余滚轮看。</p>
     */
    private void renderSellList(GuiGraphics graphics) {
        List<TradeEntry> offers = sellOffers();
        graphics.drawString(this.font, Component.translatable("gui.deepspacedeployer.sell_prices"),
                8, 140, ACCENT, false);
        if (offers.isEmpty()) {
            graphics.drawString(this.font, Component.translatable("gui.deepspacedeployer.shop_loading"),
                    8, 154, TEXT_DIM, false);
            return;
        }
        for (int row = 0; row < SELL_ROWS; row++) {
            int index = this.scroll + row;
            if (index >= offers.size()) {
                break;
            }
            TradeEntry entry = offers.get(index);
            ItemStack stack = entry.createStack();
            int lineY = 154 + row * 14;
            int unitPrice = Math.max(1, (int) Math.round((double) entry.price() / Math.max(1, entry.count())));
            graphics.renderItem(stack, 8, lineY - 2);
            graphics.drawString(this.font, fit(stack.getHoverName().getString(), 96), 28, lineY, TEXT, false);
            graphics.drawString(this.font, Component.translatable("gui.deepspacedeployer.price_tag", unitPrice),
                    132, lineY, GOLD, false);
        }
        // 滑条（清单比一屏长时才有）
        drawScrollbar(graphics, 152, SELL_ROWS * 14, offers.size(), SELL_ROWS);
    }

    // ---------------- 工厂核心 ----------------

    private void renderCore(GuiGraphics graphics) {
        MachineBlockEntity be = clientBlockEntity();
        if (be == null) {
            graphics.drawString(this.font, Component.translatable("gui.deepspacedeployer.shop_loading"),
                    8, 96, TEXT_DIM, false);
            return;
        }

        if (be.isAssembling()) {
            int total = Math.max(1, be.assemblyTotal());
            int done = Math.min(total, be.assemblyTicks());
            graphics.drawString(this.font, Component.translatable("gui.deepspacedeployer.assembling",
                    done * 100 / total), 8, 92, GOLD, false);
            graphics.fill(8, 104, 168, 112, 0xFF101018);
            graphics.fill(8, 104, 8 + (int) (160.0 * done / total), 112, ACCENT);
            graphics.drawString(this.font, fit(Component.translatable(
                    "gui.deepspacedeployer.assembling_hint").getString(), 272),
                    8, 118, TEXT_DIM, false);
            return;
        }
        BlueprintInfo selected = selectedBlueprint();
        if (this.corePage == 0) {
            // 提示一律截断到面板宽度：这几行原来能到 300~500px，会长过面板、
            // 直接压在右边的「选」按钮甚至玩家背包上（老板报的"按键文字重叠"就是这个）。
            drawShortHint(graphics, "gui.deepspacedeployer.core_hint_blueprint", HINT_Y, ACCENT);
            // 搜索框在 y+76，列表从 y+92 开始；这里标一下"筛出了几份 / 共几份"
            if (!this.blueprintQuery.isBlank()) {
                drawRight(graphics, Component.translatable("gui.deepspacedeployer.blueprint_search_count",
                        blueprints().size(), blueprintTotal()), 280, 79, TEXT_DIM);
            }
            List<BlueprintInfo> list = blueprints();
            if (list.isEmpty()) {
                graphics.drawString(this.font, fit(Component.translatable(
                        "gui.deepspacedeployer.blueprint_empty").getString(), 272),
                        8, 92, WARN, false);
                graphics.drawString(this.font, fit(Component.translatable(
                        "gui.deepspacedeployer.blueprint_empty_hint").getString(), 272),
                        8, 104, TEXT_DIM, false);
                return;
            }
            for (int row = 0; row < BP_ROWS; row++) {
                int index = this.scroll + row;
                if (index >= list.size()) {
                    break;
                }
                BlueprintInfo info = list.get(index);
                int rowY = 92 + row * 20;
                boolean sel = selected != null && selected.name().equals(info.name());
                // 左边 16×16：有缩略图就画缩略图（图纸自带的等距预览），没有就画纸的图标
                ResourceLocation thumb = ClientBlueprintThumbs.get(info.name());
                if (thumb != null) {
                    // 缩略图是 64×64 的独立纹理，贴图尺寸必须显式给（默认那套按 256×256 算，会画成一小点）
                    graphics.blit(thumb, 10, rowY + 1, 16, 16, 0.0F, 0.0F, 64, 64, 64, 64);
                    if (sel) {
                        graphics.renderOutline(9, rowY, 18, 18, GOOD);
                    }
                } else {
                    graphics.renderItem(new ItemStack(Items.PAPER), 10, rowY + 1);
                }
                graphics.drawString(this.font, fit(info.name(), 150), 32, rowY + 5, sel ? GOOD : TEXT, false);
                drawRight(graphics, Component.literal(info.sizeText() + " / " + info.totalBlocks()),
                        244, rowY + 5, TEXT_DIM);
            }
            // 滑条 + 滚轮提示（不再有"第 X/Y 页"）
            drawScrollbar(graphics, 90, BP_ROWS * 20, list.size(), BP_ROWS);
            graphics.drawString(this.font, Component.translatable("gui.deepspacedeployer.scroll_hint"),
                    8, 140, TEXT_DIM, false);
        } else {
            drawShortHint(graphics, "gui.deepspacedeployer.core_hint_material", HINT_Y, ACCENT);
            if (selected == null) {
                graphics.drawString(this.font, fit(Component.translatable(
                        "gui.deepspacedeployer.blueprint_none").getString(), 272),
                        8, 88, WARN, false);
                return;
            }
            List<Map.Entry<String, Integer>> needs = new ArrayList<>(selected.materials().entrySet());
            needs.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            // 旁边有创造板条箱 = 材料无限：需求那一列直接显示 ∞，免得看着"红字"以为不够
            boolean creativeCore = this.menu.creative();
            // 材料页也有滑条了（老板要求）：一屏 MAT_ROWS 行，剩下的滚轮翻
            int start = this.scroll;
            int shown = Math.min(needs.size() - start, MAT_ROWS);
            if (shown < 0) {
                shown = 0;
            }
            for (int i = 0; i < shown; i++) {
                Map.Entry<String, Integer> need = needs.get(start + i);
                int have = be.haveMaterial(need.getKey());
                int lineY = 84 + i * 12;
                ResourceLocation id = ResourceLocation.tryParse(need.getKey());
                Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
                if (item != Items.AIR) {
                    graphics.renderItem(new ItemStack(item), 8, lineY - 3);
                }
                graphics.drawString(this.font, fit(itemName(need.getKey()).getString(), 96), 28, lineY, TEXT, false);
                if (creativeCore) {
                    drawRight(graphics, Component.literal("∞ / " + need.getValue()), 200, lineY, GOOD);
                } else {
                    drawRight(graphics, Component.literal(have + " / " + need.getValue()),
                            200, lineY, have >= need.getValue() ? GOOD : WARN);
                }
            }
            if (needs.isEmpty()) {
                graphics.drawString(this.font, Component.translatable("gui.deepspacedeployer.material_none"),
                        8, 84, GOOD, false);
            }
            // 滑条 + 滚轮提示（和蓝图页一套）
            drawScrollbar(graphics, 81, MAT_ROWS * 12, needs.size(), MAT_ROWS);
            if (needs.size() > MAT_ROWS) {
                graphics.drawString(this.font, Component.translatable("gui.deepspacedeployer.scroll_hint"),
                        8, MAT_BOTTOM + 2, TEXT_DIM, false);
            }
            // 这几行也能很长（材料名 + 费用 + 投放点提示都挤在下面），一律截断
            if (creativeCore) {
                graphics.drawString(this.font,
                        Component.translatable("gui.deepspacedeployer.creative_crate_on"),
                        8, MAT_BOTTOM + 12, GOOD, false);
            } else {
                long fee = (long) com.deepspacedeployer.Config.ASSEMBLY_FEE_PER_BLOCK.get() * selected.totalBlocks();
                graphics.drawString(this.font, fit(Component.translatable(
                        "gui.deepspacedeployer.assembly_fee_short", fee).getString(), 72),
                        8, MAT_BOTTOM + 12, GOLD, false);
                graphics.drawString(this.font, fit(Component.translatable(this.menu.hasAssemblyTarget()
                                ? "gui.deepspacedeployer.target_chosen"
                                : "gui.deepspacedeployer.target_default").getString(), 176),
                        84, MAT_BOTTOM + 12, this.menu.hasAssemblyTarget() ? GOOD : TEXT_DIM, false);
            }
        }
    }

    /**
     * 界面最底部那条空位（玩家背包左边、按钮下面）—— 只能放短提示。
     *
     * <p>布局事实：玩家背包在 y=186..250 且只占中间（x 63..225），所以
     * <b>x+8 起、最多 52px 宽、y+194 与 y+204 两行</b>是不压任何东西的。
     * 长文案一律先 {@code fit(..., 52)} 再画。</p>
     */
    private static final int HINT_Y = 194;
    private static final int HINT_W = 52;

    /** 在市场"我要卖"页左上角重复画一遍当前的公司/价格提示（顶部横条上的按钮用同一条数据） */
    private void drawShortHint(GuiGraphics graphics, String key, int y, int color) {
        graphics.drawString(this.font, fit(Component.translatable(key).getString(), HINT_W),
                8, y, color, false);
    }

    private void renderMarket(GuiGraphics graphics) {
        if (this.marketPage == 0) {
            List<com.deepspacedeployer.market.Listing> listings = marketListings();
            if (listings.isEmpty()) {
                graphics.drawString(this.font, fit(Component.translatable(
                        "gui.deepspacedeployer.market_empty").getString(), 272),
                        8, 92, TEXT_DIM, false);
                return;
            }
            for (int row = 0; row < MARKET_ROWS; row++) {
                int index = this.scroll + row;
                if (index >= listings.size()) {
                    break;
                }
                var listing = listings.get(index);
                int rowY = 88 + row * 20;
                // 图标：物品单画物品，载具单画纸
                ItemStack icon = listing.kind() == com.deepspacedeployer.market.Listing.Kind.VEHICLE
                        ? new ItemStack(Items.PAPER)
                        : listing.item();
                graphics.renderItem(icon, 10, rowY + 1);
                // 标题 + 卖家
                graphics.drawString(this.font, fit(listing.title(), 118), 32, rowY + 2, TEXT, false);
                graphics.drawString(this.font, fit("@" + listing.sellerName(), 56), 32, rowY + 11, TEXT_DIM, false);
                // 价钱：以币显示"金额 + 哪家公司"，以物易物显示"要什么 × 几个"
                Component pay = listing.isBarter()
                        ? Component.literal("⇄ " + listing.barterCount() + " × "
                        + listing.barter().getHoverName().getString())
                        : Component.literal(listing.price() + " " + (listing.company() == null
                        ? "" : listing.company().cnName()));
                graphics.drawString(this.font, fit(pay.getString(), 116), 154, rowY + 6,
                        listing.isBarter() ? ACCENT : GOLD, false);
            }
            drawScrollbar(graphics, 86, MARKET_ROWS * 20, listings.size(), MARKET_ROWS);
        } else {
            // 我要卖：顶部横条上那两个按钮（挂什么/收什么、收哪家公司）已经表态了，
            // 这里只在底部空位补两句短提示
            drawShortHint(graphics, this.marketVehicle
                    ? "gui.deepspacedeployer.market_hint_pick_vehicle"
                    : "gui.deepspacedeployer.market_hint_pick_item", HINT_Y, ACCENT);
            drawShortHint(graphics, this.marketBarter
                    ? "gui.deepspacedeployer.market_hint_barter"
                    : "gui.deepspacedeployer.market_hint_pay", HINT_Y + 10, GOLD);
            // 挂载具时画图纸列表
            if (this.marketVehicle) {
                List<BlueprintInfo> blueprints = blueprints();
                if (blueprints.isEmpty()) {
                    graphics.drawString(this.font, Component.translatable(
                            "gui.deepspacedeployer.blueprint_none"), 8, 134, WARN, false);
                }
                for (int row = 0; row < MARKET_ROWS; row++) {
                    int index = this.scroll + row;
                    if (index >= blueprints.size()) {
                        break;
                    }
                    BlueprintInfo info = blueprints.get(index);
                    graphics.drawString(this.font, fit(info.name(), 224), 8, 134 + row * 16,
                            TEXT, false);
                    drawRight(graphics, Component.literal(info.totalBlocks() + ""), 240,
                            134 + row * 16, TEXT_DIM);
                }
                drawScrollbar(graphics, 130, MARKET_ROWS * 16, blueprints.size(), MARKET_ROWS);
            }
        }
    }
}

