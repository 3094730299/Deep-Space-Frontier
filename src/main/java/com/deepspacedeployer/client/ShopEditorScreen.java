package com.deepspacedeployer.client;

import com.deepspacedeployer.Company;
import com.deepspacedeployer.Tier;
import com.deepspacedeployer.net.ShopEditPayload;
import com.deepspacedeployer.shop.TradeEntry;
import com.deepspacedeployer.ui.webview.ThemeData;
import com.deepspacedeployer.ui.webview.WebScreenId;
import com.deepspacedeployer.ui.webview.WebViewManager;
import com.deepspacedeployer.ui.webview.WebViewScreen;
import net.minecraft.client.gui.GuiGraphics;
import com.deepspacedeployer.editor.ShopEditor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 价目表编辑器界面（只有 OP 能从 ESC 菜单左侧的按钮进来）。
 *
 * <p>左边一列是六家公司，中间是商品/收购清单，每行可以直接调价、改所需等级、删除；
 * 手里拿着物品点「加入商品 / 加入收购」就能把那个物品加进去。改完点「保存」写进世界数据包。</p>
 */
public class ShopEditorScreen extends Screen {

    private static final int PANEL_W = 340;
    private static final int PANEL_H = 232;
    private static final int ROWS = 6;
    private static final int DEFAULT_BUY_PRICE = 100;
    private static final int DEFAULT_SELL_PRICE = 10;

    private static final int BG = 0xFF101018;
    private static final int PANEL = 0xFF23232F;
    private static final int ROW_BG = 0xFF1A1A24;
    private static final int TEXT = 0xFFE8F4FF;
    private static final int DIM = 0xFF98A2B3;
    private static final int ACCENT = 0xFF54C8FF;
    private static final int GOLD = 0xFFFFD166;

    private Company company = Company.VALUES[0];
    /** 0 = 公司卖（玩家买） 1 = 公司收（玩家卖） */
    private int listKind;
    private int page;
    private int lastVersion = -1;
    /** 上一次见到的行数：用来判断"要不要重建控件"（行数没变就只刷新价格框） */
    private int lastListSize = -1;
    /** 每行的价格输入框（重建界面时要清掉重加） */
    private final java.util.List<EditBox> priceBoxes = new java.util.ArrayList<>();

    private int leftPos;
    private int topPos;

    /** 界面主题宿主 */
    private final WebViewScreen webview = new WebViewScreen(WebScreenId.SHOP_EDITOR);
    private String themeSnapshot = "";

    public ShopEditorScreen() {
        super(Component.translatable("gui.deepspacedeployer.editor_title"));
    }

    private List<TradeEntry> entries() {
        return this.listKind == 1
                ? ClientShopCache.sellOffers(this.company)
                : ClientShopCache.buyOffers(this.company);
    }

    @Override
    protected void init() {
        super.init();
        this.priceBoxes.clear();
        this.leftPos = (this.width - PANEL_W) / 2;
        this.topPos = (this.height - PANEL_H) / 2;
        this.lastListSize = entries().size();
        int x = this.leftPos;
        int y = this.topPos;

        // 六家公司
        for (int i = 0; i < Company.COUNT; i++) {
            Company target = Company.VALUES[i];
            Button button = Button.builder(shortName(target), b -> switchCompany(target))
                    .bounds(x + 6 + i * 54, y + 22, 52, 18).build();
            button.active = target != this.company;
            addRenderableWidget(button);
        }

        // 买 / 卖 切换
        Button buyTab = Button.builder(Component.translatable("gui.deepspacedeployer.editor_tab_buy"),
                        b -> switchList(0))
                .bounds(x + 4, y + 44, 80, 18).build();
        buyTab.active = this.listKind != 0;
        addRenderableWidget(buyTab);

        Button sellTab = Button.builder(Component.translatable("gui.deepspacedeployer.editor_tab_sell"),
                        b -> switchList(1))
                .bounds(x + 88, y + 44, 80, 18).build();
        sellTab.active = this.listKind != 1;
        addRenderableWidget(sellTab);

        // 每行的操作按钮
        List<TradeEntry> list = entries();
        int start = this.page * ROWS;
        for (int row = 0; row < ROWS; row++) {
            int index = start + row;
            if (index >= list.size()) {
                break;
            }
            int rowY = y + 68 + row * 20;
            // 价格：直接输入数字 + 确定
            EditBox priceBox = new EditBox(this.font, x + 128, rowY, 56, 18,
                    Component.translatable("gui.deepspacedeployer.editor_price"));
            priceBox.setMaxLength(7);
            priceBox.setFilter(text -> text.isEmpty() || text.matches("[0-9]{1,7}"));
            priceBox.setValue(String.valueOf(list.get(index).price()));
            addRenderableWidget(priceBox);
            priceBoxes.add(priceBox);

            addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.editor_set_price"),
                            b -> applyPrice(index, priceBox))
                    .bounds(x + 188, rowY, 30, 18).build());
            Button tierButton = Button.builder(Component.translatable("gui.deepspacedeployer.editor_tier_short"),
                            b -> edit(3, index, 0))
                    .bounds(x + 222, rowY, 32, 18).build();
            tierButton.active = this.listKind == 0;
            addRenderableWidget(tierButton);
            addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.editor_remove"),
                            b -> edit(4, index, 0))
                    .bounds(x + 286, rowY, 46, 18).build());
        }

        // 底部一排
        int bottom = y + 192;
        Button prev = Button.builder(Component.translatable("gui.deepspacedeployer.prev_page"), b -> changePage(-1))
                .bounds(x + 6, bottom, 50, 18).build();
        prev.active = this.page > 0;
        addRenderableWidget(prev);

        Button next = Button.builder(Component.translatable("gui.deepspacedeployer.next_page"), b -> changePage(1))
                .bounds(x + 60, bottom, 50, 18).build();
        next.active = (this.page + 1) * ROWS < list.size();
        addRenderableWidget(next);

        addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.editor_add_buy"),
                        b -> edit(1, 0, DEFAULT_BUY_PRICE))
                .bounds(x + 116, bottom, 62, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.editor_add_sell"),
                        b -> edit(1, 0, DEFAULT_SELL_PRICE))
                .bounds(x + 182, bottom, 62, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.editor_save"),
                        b -> edit(5, 0, 0))
                .bounds(x + 248, bottom, 44, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.editor_close"),
                        b -> this.onClose())
                .bounds(x + 296, bottom, 40, 18).build());

        // ★ 界面主题：原生控件建完之后装配
        this.webview.setEnabled(WebViewManager.enabled());
        this.webview.size(WebScreenId.SHOP_EDITOR.width(), WebScreenId.SHOP_EDITOR.height());
        this.webview.at(this.leftPos, this.topPos);
        this.webview.setActionSink(this::onThemeAction);
        this.themeSnapshot = "";
        syncTheme();
    }

    // ---------------------------------------------------------------- 界面主题

    private void syncTheme() {
        if (!this.webview.enabled()) {
            return;
        }
        if (this.webview.themeChanged()) {
            this.themeSnapshot = "";
        }
        String snapshot = this.company.id() + "|" + this.listKind + "|" + this.page
                + "|" + entries().size() + "|" + ClientShopCache.version();
        if (snapshot.equals(this.themeSnapshot) && this.webview.visible()) {
            return;
        }
        this.themeSnapshot = snapshot;
        this.webview.reload(this::buildThemeData);
    }

    /** 喂给 shop_editor.html 的数据 */
    private ThemeData buildThemeData() {
        ThemeData d = new ThemeData();
        d.put("当前公司", this.company.id());
        d.put("当前公司名", this.company.cnName());
        d.put("表名", this.listKind == 1 ? "公司收购" : "公司出售");

        // 六家公司：铺成两行、每行 3 个（一行 6 个会被画到画布外面 —— 真发生过）
        List<Map<String, ?>> row1 = new ArrayList<>();
        List<Map<String, ?>> row2 = new ArrayList<>();
        for (int i = 0; i < Company.COUNT; i++) {
            Company c = Company.VALUES[i];
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("代号", c.id());
            row.put("名称", c.cnName());
            row.put("是当前", c == this.company);
            (i < 3 ? row1 : row2).add(row);
        }
        d.list("公司行1", row1);
        d.list("公司行2", row2);

        List<Map<String, ?>> rows = new ArrayList<>();
        List<TradeEntry> list = entries();
        int start = this.page * ROWS;
        for (int row = 0; row < ROWS; row++) {
            int index = start + row;
            if (index >= list.size()) {
                break;
            }
            TradeEntry e = list.get(index);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("物品", itemLabel(e.itemId()));
            r.put("价格", e.price());
            r.put("等级", e.tier() == null ? "" : e.tier().name().toLowerCase(java.util.Locale.ROOT));
            r.put("下标", index);
            rows.add(r);
        }
        d.list("条目", rows);
        return d;
    }

    private static String itemLabel(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
        return item == Items.AIR ? itemId : item.getDescription().getString();
    }

    /** shop_editor.html 的动作 */
    private boolean onThemeAction(String action, com.deepspacedeployer.ui.theme.HtmlNode source) {
        if (action == null) {
            return false;
        }
        int colon = action.indexOf(':');
        String name = colon < 0 ? action : action.substring(0, colon);
        String arg = colon < 0 ? "" : action.substring(colon + 1).trim();
        switch (name) {
            case "pick_company" -> {
                // 参数是公司代号（如 "skimmer"）；也兼容"第几家公司"的数字写法
                Company target = companyByArg(arg);
                if (target == null) {
                    return false;
                }
                switchCompany(target);
                syncTheme();
                return true;
            }
            case "switch_list" -> {
                switchList(this.listKind == 0 ? 1 : 0);
                syncTheme();
                return true;
            }
            case "add_buy" -> {
                edit(ShopEditor.ACTION_ADD_HELD, 0, DEFAULT_BUY_PRICE);
                return true;
            }
            case "add_sell" -> {
                edit(ShopEditor.ACTION_ADD_HELD, 1, DEFAULT_SELL_PRICE);
                return true;
            }
            case "set_price" -> {
                int index = parseInt(arg, -1);
                if (index < 0) {
                    return false;
                }
                // 主题上只有一个"价格"输入位（data-input="价格"），所以取第一个框的值
                applyPrice(index, this.priceBoxes.isEmpty() ? null : this.priceBoxes.get(0));
                return true;
            }
            case "remove" -> {
                int index = parseInt(arg, -1);
                if (index < 0) {
                    return false;
                }
                edit(ShopEditor.ACTION_REMOVE, index, 0);
                return true;
            }
            case "save" -> {
                edit(ShopEditor.ACTION_SAVE, 0, 0);
                return true;
            }
            case "close" -> {
                this.onClose();
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** 动作参数 → 公司：先当代号找，再当序号找 */
    private static Company companyByArg(String arg) {
        if (arg == null || arg.isBlank()) {
            return null;
        }
        for (Company c : Company.VALUES) {
            if (c.id().equalsIgnoreCase(arg)) {
                return c;
            }
        }
        int index = parseInt(arg, -1);
        return index >= 0 && index < Company.COUNT ? Company.VALUES[index] : null;
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 把输入框里的数字发给服务端设价（box 为 null 说明主题上没摆输入框 → 用行内原价） */
    private void applyPrice(int index, EditBox box) {
        int price;
        try {
            price = Math.max(1, Integer.parseInt(box == null ? "" : box.getValue().trim()));
        } catch (NumberFormatException e) {
            List<TradeEntry> list = entries();
            price = index >= 0 && index < list.size() ? list.get(index).price() : 1;
        }
        edit(ShopEditor.ACTION_SET_PRICE, index, price);
    }

    private Component shortName(Company target) {
        String name = target.cnName();
        String shortName = name.length() > 4 ? name.substring(0, 4) : name;
        return Component.literal(shortName);
    }

    private void switchCompany(Company target) {
        this.company = target;
        this.page = 0;
        PacketDistributor.sendToServer(new ShopEditPayload(target.id(), 0, this.listKind, 0, 0));
        rebuildWidgets();
    }

    private void switchList(int kind) {
        this.listKind = kind;
        this.page = 0;
        PacketDistributor.sendToServer(new ShopEditPayload(this.company.id(), 0, kind, 0, 0));
        rebuildWidgets();
    }

    private void changePage(int delta) {
        this.page = Math.max(0, this.page + delta);
        rebuildWidgets();
    }

    private void edit(int action, int index, int value) {
        PacketDistributor.sendToServer(new ShopEditPayload(this.company.id(), action, this.listKind, index, value));
    }

    @Override
    public void tick() {
        super.tick();
        // 服务器推了新数据：只有"行数或内容真的变了"才重建整套控件。
        // 以前是收到任何一包就 rebuildWidgets()，而每次改价格都会来一包 ->
        // 正在输入的价格框每点一下就被重建、丢掉焦点（看起来就是"卡一下"）。
        if (ClientShopCache.version() != this.lastVersion) {
            this.lastVersion = ClientShopCache.version();
            if (this.lastListSize != entries().size()) {
                rebuildWidgets();
            } else {
                // 行数没变：只把各行输入框刷新成服务端的权威价格
                List<TradeEntry> list = entries();
                int start = this.page * ROWS;
                for (int row = 0; row < this.priceBoxes.size(); row++) {
                    int index = start + row;
                    if (index >= list.size()) {
                        break;
                    }
                    String canonical = String.valueOf(list.get(index).price());
                    if (!this.priceBoxes.get(row).getValue().equals(canonical)) {
                        this.priceBoxes.get(row).setValue(canonical);
                    }
                }
                this.lastListSize = list.size();
            }
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);

        graphics.fill(this.leftPos, this.topPos, this.leftPos + PANEL_W, this.topPos + PANEL_H, BG);
        graphics.fill(this.leftPos + 1, this.topPos + 1, this.leftPos + PANEL_W - 1, this.topPos + PANEL_H - 1, PANEL);
        graphics.drawString(this.font, this.title, this.leftPos + 6, this.topPos + 8, ACCENT, false);
        graphics.drawString(this.font, this.company.displayName(), this.leftPos + 190, this.topPos + 8, GOLD, false);

        List<TradeEntry> list = entries();
        int start = this.page * ROWS;
        for (int row = 0; row < ROWS; row++) {
            int index = start + row;
            int rowY = this.topPos + 68 + row * 20;
            if (index >= list.size()) {
                break;
            }
            TradeEntry entry = list.get(index);
            graphics.fill(this.leftPos + 4, rowY, this.leftPos + PANEL_W - 4, rowY + 18, ROW_BG);

            ItemStack stack = entry.createStack();
            graphics.renderItem(stack, this.leftPos + 6, rowY + 1);
            String name = stack.getHoverName().getString();
            if (name.length() > 14) {
                name = name.substring(0, 13) + "…";
            }
            graphics.drawString(this.font, name, this.leftPos + 26, rowY + 5, TEXT, false);
            if (this.listKind == 0) {
                graphics.drawString(this.font, Component.literal(entry.tier().cnName()),
                        this.leftPos + 258, rowY + 5, DIM, false);
            } else {
                graphics.drawString(this.font, Component.literal("×" + entry.count()),
                        this.leftPos + 262, rowY + 5, DIM, false);
            }
        }

        if (list.isEmpty()) {
            graphics.drawString(this.font, fit(Component.translatable(
                    "gui.deepspacedeployer.editor_empty").getString(), PANEL_W - 16),
                    this.leftPos + 8, this.topPos + 72, DIM, false);
        } else {
            graphics.drawString(this.font,
                    Component.translatable("gui.deepspacedeployer.page", this.page + 1,
                            Math.max(1, (list.size() + ROWS - 1) / ROWS)),
                    this.leftPos + 176, this.topPos + 46, DIM, false);
        }

        // 底部提示也截断到面板宽度（原来 437px 长，会画到面板外面去）
        graphics.drawString(this.font, fit(Component.translatable(
                "gui.deepspacedeployer.editor_hint").getString(), PANEL_W - 12),
                this.leftPos + 6, this.topPos + PANEL_H - 18, DIM, false);

        super.render(graphics, mouseX, mouseY, partialTick);
        // 主题盖在最上面（没主题就什么都不画）
        this.webview.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 主题的按钮优先（它们画在原生控件上面）
        if (this.webview.mouseClicked(mouseX, mouseY, button)) {
            syncTheme();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 按像素宽度截断文字（超长加省略号），避免文字画出面板 */
    private String fit(String text, int maxWidth) {
        if (text == null || this.font.width(text) <= maxWidth) {
            return text == null ? "" : text;
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

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    /** 给外面用：把物品名转成显示用组件（数据包里的 id 不一定有中文名） */
    public static Component itemName(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
        return item == Items.AIR ? Component.literal(itemId) : item.getDescription();
    }
}
