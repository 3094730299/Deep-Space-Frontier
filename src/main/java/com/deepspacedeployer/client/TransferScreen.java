package com.deepspacedeployer.client;

import com.deepspacedeployer.Company;
import com.deepspacedeployer.economy.CardData;
import com.deepspacedeployer.menu.MachineMenu;
import com.deepspacedeployer.menu.TransferMenu;
import com.deepspacedeployer.net.TransferPayload;
import com.deepspacedeployer.ui.webview.ThemeData;
import com.deepspacedeployer.ui.webview.ThemeSlotLayout;
import com.deepspacedeployer.ui.webview.WebScreenId;
import com.deepspacedeployer.ui.webview.WebViewManager;
import com.deepspacedeployer.ui.webview.WebViewScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * 资金转化界面：两张银行卡 + 金额输入 + 所有和钱有关的操作。
 *
 * <p>老板的要求原话：<i>"在商店方块内增加一个资金转化按钮，打开后是两个银行卡的格子，
 * 下面是输入框和确定，专门用来从一个银行卡中转出资金到另一个银行卡，
 * 把第一个界面中的所有关于银行卡的转入转出存入取出的按钮全部挪到里面，左上角再加一个返回按钮"</i>。</p>
 *
 * <p>所以现在钱的操作全在这里，商店界面只留一个入口按钮（{@code MachineScreen} 的「资金转化」）。</p>
 */
public class TransferScreen extends AbstractContainerScreen<TransferMenu> {

    private static final int BG = 0xFF11111A;
    private static final int PANEL = 0xFF262633;
    private static final int SLOT_BG = 0xFF0C0C12;
    private static final int TEXT = 0xFFE8F4FF;
    private static final int TEXT_DIM = 0xFF98A2B3;
    private static final int ACCENT = 0xFF54C8FF;
    private static final int GOLD = 0xFFFFD166;

    /** 金额输入框（默认 100，只允许数字）—— 给下面那一排转账按钮用 */
    private EditBox amountBox;
    /** 从公司账户转钱到卡上的金额输入框（跟上面那个是分开的两个框，老板要求加的） */
    private EditBox toCardBox;
    /** 转账目标（玩家名 / UUID） */
    private EditBox targetBox;

    /** 界面主题宿主 */
    private final WebViewScreen webview = new WebViewScreen(WebScreenId.TRANSFER);
    private String themeSnapshot = "";

    public TransferScreen(TransferMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 288;
        this.imageHeight = 270;
        // 面板坐标 = 玩家背包坐标：两者一致，渲染时不用再减 leftPos
        this.inventoryLabelY = -1000;
    }

    @Override
    protected void init() {
        super.init();
        int x = this.leftPos;
        int y = this.topPos;

        // 每个按钮都按"文字实际像素宽 + 左右各 6px 边距"给宽度：
        // 原版按钮文字居中，宽度不够就会被裁 / 压边（老板报的"按键文字重叠"就是这个）。
        // 左上角：返回商店（72 = 4 个中文字 48 + 边距）
        addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.transfer_back"),
                        b -> send(TransferPayload.ACT_BACK, 0L))
                .bounds(x + 8, y + 18, 72, 18).build());

        // 金额：输入框 + 三个快捷加钱按钮（"+100"+1k"+1万"，各自文字宽 + 边距）
        // 这个框给"卡→卡""卡转余额""转账给玩家"用；"存入卡内"有自己单独的框（本行右边）
        this.amountBox = new EditBox(this.font, x + 8, y + 106, 66, 18,
                Component.translatable("gui.deepspacedeployer.amount_hint"));
        this.amountBox.setMaxLength(10);
        this.amountBox.setFilter(text -> text.isEmpty() || text.matches("[0-9]{1,10}"));
        this.amountBox.setValue("100");
        addRenderableWidget(this.amountBox);

        addRenderableWidget(Button.builder(Component.literal("+100"), b -> bumpAmount(100L))
                .bounds(x + 78, y + 106, 34, 18).build());
        addRenderableWidget(Button.builder(Component.literal("+1k"), b -> bumpAmount(1000L))
                .bounds(x + 114, y + 106, 28, 18).build());
        addRenderableWidget(Button.builder(Component.literal("+1万"), b -> bumpAmount(10_000L))
                .bounds(x + 144, y + 106, 34, 18).build());

        // 公司账户 → 卡（老板要求新加的文本框）：输入框 + 「存入卡内」按钮
        this.toCardBox = new EditBox(this.font, x + 8, y + 128, 104, 18,
                Component.translatable("gui.deepspacedeployer.to_card_hint"));
        this.toCardBox.setMaxLength(10);
        this.toCardBox.setFilter(text -> text.isEmpty() || text.matches("[0-9]{1,10}"));
        this.toCardBox.setValue("100");
        addRenderableWidget(this.toCardBox);
        addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.to_card"),
                        b -> send(TransferPayload.ACT_TO_CARD, 0, toCardAmount()))
                .bounds(x + 116, y + 128, 80, 18).build());
        // 卡 → 卡（给副卡充值）放在这一行剩下的位置
        addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.transfer_card_to_card"),
                        b -> send(TransferPayload.ACT_CARD_TO_CARD, amount()))
                .bounds(x + 200, y + 128, 80, 18).build());

        // 卡 → 公司账户（用上面那个金额框）
        addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.from_card"),
                        b -> send(TransferPayload.ACT_FROM_CARD, amount()))
                .bounds(x + 8, y + 150, 72, 18).build());

        // 转账给玩家（票据删掉之后，"给钱"就靠这个）：玩家名 + 上面那个金额
        this.targetBox = new EditBox(this.font, x + 84, y + 150, 124, 18,
                Component.translatable("gui.deepspacedeployer.transfer_target_hint"));
        this.targetBox.setMaxLength(32);
        addRenderableWidget(this.targetBox);
        addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.transfer_send"),
                        b -> send(TransferPayload.ACT_TRANSFER, 0, amount(), targetName()))
                .bounds(x + 212, y + 150, 68, 18).build());

        // 兑换 / 余额
        addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.exchange"),
                        b -> send(TransferPayload.ACT_EXCHANGE, 0L))
                .bounds(x + 8, y + 172, 88, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.balance_button"),
                        b -> send(TransferPayload.ACT_REPORT, 0L))
                .bounds(x + 100, y + 172, 88, 18).build());

        // ★ 界面主题：原生输入框/按钮建完之后装配
        this.webview.setEnabled(WebViewManager.enabled());
        this.webview.size(WebScreenId.TRANSFER.width(), WebScreenId.TRANSFER.height());
        this.webview.at(this.leftPos, this.topPos);
        this.webview.setActionSink(this::onThemeAction);
        this.themeSnapshot = "";
        syncTheme();
        applyThemeSlotLayout();
    }

    /**
     * 把原版物品格 / 输入框摆到主题标记的位置。
     *
     * <p>这个菜单的槽位顺序和别的界面不一样：**玩家背包在前 36 格，两张银行卡在后面**
     * （见 {@code TransferMenu}），所以卡的索引是 {@code size-2 / size-1}。</p>
     */
    private void applyThemeSlotLayout() {
        if (!this.webview.visible()) {
            return;
        }
        var slots = this.menu.slots;
        int cardsStart = slots.size() - 2;
        if (cardsStart < 36) {
            return;
        }
        ThemeSlotLayout.place(this.webview, slots.get(cardsStart), "card_a");
        ThemeSlotLayout.place(this.webview, slots.get(cardsStart + 1), "card_b");
        ThemeSlotLayout.player(this.webview, slots.subList(0, 27), slots.subList(27, 36));

        // 原生输入框：按 data-input="金额" / "目标" 摆
        var amount = ThemeSlotLayout.input(this.webview, "金额");
        if (amount != null && this.amountBox != null) {
            this.amountBox.setX(this.leftPos + Math.round(amount.x()));
            this.amountBox.setY(this.topPos + Math.round(amount.y()));
        }
        var target = ThemeSlotLayout.input(this.webview, "目标");
        if (target != null && this.targetBox != null) {
            this.targetBox.setX(this.leftPos + Math.round(target.x()));
            this.targetBox.setY(this.topPos + Math.round(target.y()));
        }
    }

    // ---------------------------------------------------------------- 界面主题

    /** 数据变了才重新绑定主题（快照比对） */
    private void syncTheme() {
        if (!this.webview.enabled()) {
            return;
        }
        if (this.webview.themeChanged()) {
            this.themeSnapshot = "";
        }
        String snapshot = themeSignature();
        if (snapshot.equals(this.themeSnapshot) && this.webview.visible()) {
            return;
        }
        this.themeSnapshot = snapshot;
        this.webview.reload(this::buildThemeData);
        applyThemeSlotLayout();
    }

    private String themeSignature() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < TransferMenu.CARD_SLOTS; i++) {
            ItemStack card = this.menu.card(i);
            sb.append(CardData.accountName(card)).append(':')
                    .append(CardData.getBalance(card, Company.VALUES[i % Company.VALUES.length]))
                    .append('|');
        }
        // 输入框内容也算进快照（金额变了主题上的数字要跟着变）
        sb.append('|').append(this.amountBox == null ? "" : this.amountBox.getValue());
        sb.append('|').append(this.toCardBox == null ? "" : this.toCardBox.getValue());
        return sb.toString();
    }

    /** 喂给 transfer.html 的数据 */
    private ThemeData buildThemeData() {
        ThemeData d = new ThemeData();
        ItemStack cardA = this.menu.card(0);
        ItemStack cardB = this.menu.card(1);
        d.put("卡A主", CardData.hasAccount(cardA) ? CardData.accountName(cardA) : "未绑定");
        d.put("卡B主", CardData.hasAccount(cardB) ? CardData.accountName(cardB) : "未绑定");
        d.put("卡A余额", CardData.getBalance(cardA, Company.VALUES[0]));
        d.put("卡B余额", CardData.getBalance(cardB, Company.VALUES[1 % Company.VALUES.length]));
        d.put("金额", this.amountBox == null ? "100" : this.amountBox.getValue());
        d.put("有卡A", !cardA.isEmpty());
        d.put("有卡B", !cardB.isEmpty());
        return d;
    }

    /** transfer.html 的动作：amount_up / amount_down / card_to_card / to_card / from_card / send / exchange / balance / back */
    private boolean onThemeAction(String action, com.deepspacedeployer.ui.theme.HtmlNode source) {
        if (action == null) {
            return false;
        }
        switch (action) {
            case "amount_up" -> {
                bumpAmount(100L);
                syncTheme();
                return true;
            }
            case "amount_down" -> {
                bumpAmount(-100L);
                syncTheme();
                return true;
            }
            case "card_to_card" -> {
                send(TransferPayload.ACT_CARD_TO_CARD, amount());
                return true;
            }
            case "to_card" -> {
                send(TransferPayload.ACT_TO_CARD, 0, toCardAmount());
                return true;
            }
            case "from_card" -> {
                send(TransferPayload.ACT_FROM_CARD, amount());
                return true;
            }
            case "send" -> {
                send(TransferPayload.ACT_TRANSFER, 0, amount(), targetName());
                return true;
            }
            case "exchange" -> {
                send(TransferPayload.ACT_EXCHANGE, 0L);
                return true;
            }
            case "balance" -> {
                send(TransferPayload.ACT_REPORT, 0L);
                return true;
            }
            case "back" -> {
                send(TransferPayload.ACT_BACK, 0L);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** 「存入卡内」用的金额（那个专用输入框） */
    private long toCardAmount() {
        try {
            if (this.toCardBox == null || this.toCardBox.getValue().trim().isEmpty()) {
                return 1L;
            }
            return Math.max(1L, Long.parseLong(this.toCardBox.getValue().trim()));
        } catch (NumberFormatException e) {
            return 1L;
        }
    }

    /** 转账目标（玩家名 / UUID）；空着就发空串，服务端会提示 */
    private String targetName() {
        return this.targetBox == null ? "" : this.targetBox.getValue().trim();
    }

    /** 输入框里加一笔（上限 10 亿，和服务端的裁剪范围一致） */
    private void bumpAmount(long delta) {
        long now = amount() + delta;
        this.amountBox.setValue(Long.toString(Math.max(1L, Math.min(1_000_000_000L, now))));
    }

    private long amount() {
        if (this.amountBox == null) {
            return 100L;
        }
        try {
            return Math.max(1L, Long.parseLong(this.amountBox.getValue().trim()));
        } catch (NumberFormatException e) {
            return 1L;
        }
    }

    private void send(int action, long amount) {
        send(action, 0, amount, "");
    }

    private void send(int action, int slot, long amount) {
        send(action, slot, amount, "");
    }

    private void send(int action, int slot, long amount, String name) {
        if (this.minecraft == null || this.menu.dataValue(MachineMenu.DATA_SYNCED) != 1) {
            return;
        }
        PacketDistributor.sendToServer(new TransferPayload(
                this.menu.dataValue(MachineMenu.DATA_POS_X),
                this.menu.dataValue(MachineMenu.DATA_POS_Y),
                this.menu.dataValue(MachineMenu.DATA_POS_Z),
                action, slot, amount, name == null ? "" : name));
    }

    /**
     * 右键卡槽 = 把背包里的银行卡"拎"到鼠标上，再左键点卡槽放下去。
     *
     * <p>老板要求不要右键直接放置，改成"用鼠标挪上去"：所以这里只负责把卡拎起来，
     * 放置完全走原版左键（服务端校验）。</p>
     *
     * <p>只在<b>空卡槽</b>上拦右键：卡槽里有卡时右键是原版的"拿一半"，那个行为要留给玩家。</p>
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 主题先处理（data-action）；没命中才轮到原生控件 —— 否则主题画的按钮点不动
        if (this.webview.mouseClicked(mouseX, mouseY, button)) {
            syncTheme();
            return true;
        }
        if (button == 1) {
            int hovered = hoveredCardSlot(mouseX, mouseY);
            if (hovered >= 0
                    && this.menu.getSlot(hovered).getItem().isEmpty()
                    && this.minecraft != null && this.minecraft.player != null
                    && this.minecraft.player.containerMenu.getCarried().isEmpty()) {
                // 把"右键的是哪个卡槽"一起发过去：卡1 优先拎主卡、卡2 优先拎副卡
                send(TransferPayload.ACT_SLOT_SWAP, hovered, 0L);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 鼠标底下的卡槽编号；不在卡槽上返回 -1 */
    private int hoveredCardSlot(double mouseX, double mouseY) {
        for (int i = 0; i < TransferMenu.CARD_SLOTS; i++) {
            var slot = this.menu.getSlot(i);
            int sx = this.leftPos + slot.x;
            int sy = this.topPos + slot.y;
            if (mouseX >= sx - 2 && mouseX < sx + 18 && mouseY >= sy - 2 && mouseY < sy + 18) {
                return i;
            }
        }
        return -1;
    }

    private Company company() {
        return Company.byIndex(this.menu.dataValue(MachineMenu.DATA_COMPANY));
    }

    private ItemStack card(int index) {
        return this.menu.card(index);
    }

    // ---------------- 服务端把两张卡的真实内容推过来 ----------------

    /**
     * 刷新两个卡槽的显示（卡槽拿不走，内容以服务端推来的为准）。
     *
     * <p>{@code Slot#set} 只在本地容器里写一份（生存模式不会因此发包给服务端），
     * 服务端的 {@code TransferMenu.cards} 才是权威 —— 每次服务端动过卡都会推这一包过来。</p>
     */
    public void acceptCards(List<ItemStack> cards) {
        if (cards == null) {
            return;
        }
        for (int i = 0; i < TransferMenu.CARD_SLOTS && i < cards.size(); i++) {
            ItemStack stack = cards.get(i);
            ItemStack copy = stack == null ? ItemStack.EMPTY : stack.copyWithCount(1);
            this.menu.getSlot(i).set(copy);
        }
    }

    /**
     * 面板背景画在 {@code renderBg} 里 —— 这是原版唯一正确的时机。
     *
     * <p>原版 {@code AbstractContainerScreen#render} 的顺序是
     * renderBg → 画控件 → 画卡槽物品 → renderLabels，所以面板<b>必须</b>在这里画完；
     * 早先版本在 {@code render()} 里 {@code super.render()} 之后再铺面板，
     * 结果把按钮、输入框和玩家背包里的物品全盖住了（看不见但还能点）。</p>
     */
    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        // 屏幕变暗由 AbstractContainerScreen#renderBackground（内部调 renderTransparentBackground）负责；
        // 这个类里不必也不能调 super.renderBg（javac 只看到父类那份抽象声明，会报"无法直接访问"）。
        int x = this.leftPos;
        int y = this.topPos;
        graphics.fill(x, y, x + this.imageWidth, y + this.imageHeight, BG);
        graphics.fill(x + 1, y + 1, x + this.imageWidth - 1, y + this.imageHeight - 1, PANEL);
        graphics.fill(x + 1, y + 15, x + this.imageWidth - 1, y + 16, ACCENT);
        // 两个卡槽的底（画在卡之前）
        slotBg(graphics, x + TransferMenu.CARD1_X, y + TransferMenu.CARD1_Y);
        slotBg(graphics, x + TransferMenu.CARD2_X, y + TransferMenu.CARD2_Y);

        // 卡槽标题、箭头、两张卡的信息都画在这里（控件之前）：
        // 这样万一文字压到输入框/按钮，是被控件盖住，而不是把控件盖掉。
        graphics.drawString(this.font, Component.translatable("gui.deepspacedeployer.transfer_card_from"),
                x + TransferMenu.CARD1_X, y + TransferMenu.CARD1_Y - 12, TEXT, false);
        graphics.drawString(this.font, Component.translatable("gui.deepspacedeployer.transfer_card_to"),
                x + TransferMenu.CARD2_X, y + TransferMenu.CARD2_Y - 12, TEXT, false);
        // 箭头放在两个卡槽标题之间的空隙（y 和标题同一行，免得压到卡里的物品）
        graphics.drawString(this.font, "→", x + 140, y + TransferMenu.CARD1_Y - 12, ACCENT, false);
        // 卡的信息放在卡槽下面（y+84 那行）：金额输入框从 y+106 才开始，不会撞上。
        // 每张卡只占一行（卡主 + 余额），给下面新加的那个文本框腾出空间。
        drawCardInfo(graphics, 0, x + 8, y + 84);
        drawCardInfo(graphics, 1, x + 152, y + 84);
    }

    /** 画界面（只补标题与提示；面板在 renderBg，控件与卡槽物品由 super.render 画） */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        syncTheme();
        applyThemeSlotLayout();   // 每帧对齐（便宜；保证主题加载时机不影响）
        super.render(graphics, mouseX, mouseY, partialTick);

        int x = this.leftPos;
        int y = this.topPos;
        // 标题往右让开"← 返回商店"那个按钮（它到 x+80）
        graphics.drawString(this.font, this.title, x + 90, y + 5, ACCENT, false);
        // 提示放在最后一排按钮下面那一行（按钮到 y+190，玩家背包从 y+186 起但只在中间，
        // 左边这条是空的）
        graphics.drawString(this.font, fit(Component.translatable(
                "gui.deepspacedeployer.transfer_hint").getString(), 262, "· "),
                x + 8, y + 194, TEXT_DIM, false);

        // 主题盖在最上面（没主题就什么都不画）
        this.webview.render(graphics, mouseX, mouseY, partialTick);
    }

    /** 按像素宽度截断文字（超长加省略号），避免文字超出界面 */
    private String fit(String text, int maxWidth, String prefix) {
        if (text == null) {
            return prefix;
        }
        if (this.font.width(prefix + text) <= maxWidth) {
            return prefix + text;
        }
        String ellipsis = "…";
        int limit = maxWidth - this.font.width(ellipsis) - this.font.width(prefix);
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (this.font.width(sb.toString() + c) > limit) {
                break;
            }
            sb.append(c);
        }
        return prefix + sb + ellipsis;
    }

    private void slotBg(GuiGraphics graphics, int sx, int sy) {
        graphics.fill(sx - 1, sy - 1, sx + 17, sy + 17, SLOT_BG);
    }

    /** 画一张卡的信息：卡主（副卡标明"副卡"）+ 这张卡自己的余额，压成一行画 */
    private void drawCardInfo(GuiGraphics graphics, int index, int tx, int ty) {
        ItemStack stack = card(index);
        String who = CardData.accountName(stack);
        boolean sub = false;    // 副卡已退役；旧界面统一按普通卡显示
        long balance = CardData.getBalance(stack, company());
        String owner = who.isEmpty() ? "-" : who + (sub ? "(副)" : "");
        Component line = Component.literal(owner + "  ")
                .append(Component.translatable("gui.deepspacedeployer.transfer_card_balance", balance));
        graphics.drawString(this.font, line, tx, ty, balance > 0 ? GOLD : (sub ? ACCENT : TEXT_DIM), false);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // 全用绝对坐标画（坐标和面板一致），这里不画任何东西
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
