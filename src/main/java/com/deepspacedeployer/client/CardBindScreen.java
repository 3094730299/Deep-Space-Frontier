package com.deepspacedeployer.client;

import com.deepspacedeployer.Company;
import com.deepspacedeployer.economy.CardData;
import com.deepspacedeployer.economy.CompanyFunds;
import com.deepspacedeployer.net.BindCardPayload;
import com.deepspacedeployer.net.CardWalletPayload;
import com.deepspacedeployer.ui.webview.ThemeData;
import com.deepspacedeployer.ui.webview.ThemeSlotLayout;
import com.deepspacedeployer.ui.webview.WebScreenId;
import com.deepspacedeployer.ui.webview.WebViewScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 深空银行卡界面：<b>7 个账户余额</b> + 总资产 + 绑定账户 / 改名 / 解绑。
 *
 * <p>这里显示的是 {@link CompanyFunds} 里的账户余额（6 家公司 + 通用货币），
 * 不是卡物品上的旧便携余额。</p>
 *
 * <p><b>界面主题</b>：这一份由 {@code theme/…/card_bind.html} 描述
 * （见 {@link #buildThemeData()} 和 {@link #onThemeAction}）。
 * 主题读不到 / 写坏了 → 安静用下面的原生界面，不影响功能。</p>
 */
public class CardBindScreen extends Screen {

    /** 打开界面时用的那只手（服务端会两只手都找一遍，这里只用于显示） */
    private final InteractionHand hand;
    private EditBox nameBox;
    private EditBox labelBox;

    // ---------------- 界面主题 ----------------
    private final WebViewScreen webview = new WebViewScreen(WebScreenId.CARD_BIND);

    public CardBindScreen(InteractionHand hand) {
        super(Component.translatable("gui.deepspacedeployer.bind_title"));
        this.hand = hand;
    }

    /** 手里那张卡（可能为 EMPTY） */
    private ItemStack heldCard() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? ItemStack.EMPTY : mc.player.getItemInHand(this.hand);
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int top = contentTop();

        ItemStack card = heldCard();

        this.nameBox = new EditBox(this.font, cx - 100, top + 104, 200, 20,
                Component.translatable("gui.deepspacedeployer.bind_field"));
        this.nameBox.setMaxLength(64);
        String account = CardData.accountName(card);
        if (account.isEmpty() && Minecraft.getInstance().player != null) {
            // 未绑定：默认填自己，点「绑定」就是绑到自己；要给别人再改
            account = Minecraft.getInstance().player.getGameProfile().getName();
        }
        this.nameBox.setValue(account);
        this.addRenderableWidget(this.nameBox);

        this.labelBox = new EditBox(this.font, cx - 100, top + 130, 200, 20,
                Component.translatable("gui.deepspacedeployer.bind_label_field"));
        this.labelBox.setMaxLength(48);
        this.labelBox.setValue(CardData.cardLabel(card));
        this.addRenderableWidget(this.labelBox);

        // 绑定（同时应用卡名）
        this.addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.bind_button"),
                        b -> submit(BindCardPayload.ACTION_BIND))
                .bounds(cx - 100, top + 158, 64, 20).build());

        // 只改名
        this.addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.rename_button"),
                        b -> submit(BindCardPayload.ACTION_RENAME))
                .bounds(cx - 32, top + 158, 64, 20).build());

        // 关闭（银行卡不能“解绑”：不想用了只能挂失，名额只有挂失能减）
        this.addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.card_close"),
                        b -> this.onClose())
                .bounds(cx + 36, top + 158, 64, 20).build());

        this.setInitialFocus(this.nameBox);

        // ★ 界面主题：原生输入框建完之后装配（主题只负责画框和文字）
        // 卡界面主题还是旧的“绑定”布局，没有 7 账户表；先停用主题，
        // 保证钱包原生界面完整可用。主题 HTML 更新后再打开（见待办 UI-A）。
        this.webview.setEnabled(false);
        this.webview.size(WebScreenId.CARD_BIND.width(), WebScreenId.CARD_BIND.height());
        this.webview.at((this.width - WebScreenId.CARD_BIND.width()) / 2,
                (this.height - WebScreenId.CARD_BIND.height()) / 2);
        this.webview.setActionSink(this::onThemeAction);
        this.webview.reload(this::buildThemeData);
        applyThemeInputs();
    }

    /**
     * 把两个原生输入框摆到主题标记的位置（{@code data-input="玩家名"} / {@code "卡名"}）。
     *
     * <p>这个界面不是容器界面（没有物品格），画布是**居中**的，
     * 所以屏幕坐标 = 画布左上角 + 标记坐标。</p>
     */
    private void applyThemeInputs() {
        if (!this.webview.visible()) {
            return;
        }
        int originX = (this.width - WebScreenId.CARD_BIND.width()) / 2;
        int originY = (this.height - WebScreenId.CARD_BIND.height()) / 2;
        var name = ThemeSlotLayout.input(this.webview, "玩家名");
        if (name != null && this.nameBox != null) {
            this.nameBox.setX(originX + Math.round(name.x()));
            this.nameBox.setY(originY + Math.round(name.y()));
            this.nameBox.setWidth(Math.max(60, Math.round(name.w())));
        }
        var label = ThemeSlotLayout.input(this.webview, "卡名");
        if (label != null && this.labelBox != null) {
            this.labelBox.setX(originX + Math.round(label.x()));
            this.labelBox.setY(originY + Math.round(label.y()));
            this.labelBox.setWidth(Math.max(60, Math.round(label.w())));
        }
    }

    /** 服务端把 7 个账户数据发过来了：重绑一次主题数据（原生界面每帧自己读缓存） */
    public void onWalletUpdate() {
        this.webview.refresh();
    }

    private void submit(int action) {
        PacketDistributor.sendToServer(new BindCardPayload(
                action == BindCardPayload.ACTION_BIND ? this.nameBox.getValue() : "",
                this.labelBox.getValue(),
                action,
                this.hand.ordinal()));
        if (action == BindCardPayload.ACTION_UNBIND) {
            this.nameBox.setValue("");
        }
        this.onClose();
    }

    // ---------------------------------------------------------------- 主题：数据与动作

    /** 喂给 card_bind.html 的数据 */
    private ThemeData buildThemeData() {
        ItemStack card = heldCard();
        CardWalletPayload wallet = CardWalletClient.get(this.hand);
        long[] balances = wallet == null ? new long[CompanyFunds.SLOT_COUNT] : wallet.balances();
        ThemeData d = new ThemeData();
        boolean bound = CardData.hasAccount(card);
        d.put("有卡主", bound);
        d.put("卡主", wallet != null ? wallet.accountName() : CardData.accountName(card));
        d.put("卡名", wallet != null ? wallet.cardName() : CardData.cardLabel(card));
        d.put("主手", this.hand == InteractionHand.MAIN_HAND);
        d.put("废卡", wallet != null && wallet.voided());
        d.put("总资产", wallet == null ? 0L : wallet.totalAssets());
        d.put("通用余额", balances[CompanyFunds.GENERIC_SLOT]);
        for (Company company : Company.VALUES) {
            d.put("余额_" + company.id(), balances[company.ordinal()]);
        }
        return d;
    }

    /** card_bind.html 里的按钮：bind / rename / unbind / close */
    private boolean onThemeAction(String action, com.deepspacedeployer.ui.theme.HtmlNode source) {
        if (action == null) {
            return false;
        }
        switch (action) {
            case "bind" -> {
                submit(BindCardPayload.ACTION_BIND);
                return true;
            }
            case "rename" -> {
                submit(BindCardPayload.ACTION_RENAME);
                return true;
            }
            case "unbind" -> {
                // 旧主题的按钮：银行卡已经不能解绑
                return false;
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

    /** 内容区顶端：整体高约 188，尽量在屏幕里居中；小屏也能完整放下 */
    private int contentTop() {
        return Math.max(4, this.height / 2 - 94);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (this.webview.themeChanged()) {
            this.webview.refresh();
        }
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);

        int cx = this.width / 2;
        int top = contentTop();
        CardWalletPayload wallet = CardWalletClient.get(this.hand);
        boolean voided = wallet != null && wallet.voided();

        if (voided) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("gui.deepspacedeployer.card_voided")
                            .withStyle(ChatFormatting.GRAY, ChatFormatting.BOLD),
                    cx, top, 0xFFFFFF);
        } else {
            graphics.drawCenteredString(this.font, this.title, cx, top, 0xFFFFFF);
        }

        String account = wallet != null ? wallet.accountName() : CardData.accountName(heldCard());
        String cardName = wallet != null ? wallet.cardName() : CardData.cardLabel(heldCard());
        Component header = (account.isEmpty()
                ? Component.translatable("gui.deepspacedeployer.card_unbound")
                : Component.literal(account))
                .append(Component.literal("  ·  "))
                .append(Component.literal(cardName.isEmpty() ? "-" : cardName));
        graphics.drawCenteredString(this.font, header, cx, top + 16,
                account.isEmpty() ? 0xA0A0A0 : 0x7FD3E8);

        drawWalletGrid(graphics, cx, top + 34, wallet);

        if (voided) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("gui.deepspacedeployer.card_void_note"), cx, top + 90, 0x9A9A9A);
        } else {
            graphics.drawCenteredString(this.font,
                    Component.translatable("gui.deepspacedeployer.card_empty_warning"), cx, top + 90, 0x5B9BFF);
        }

        graphics.drawCenteredString(this.font,
                Component.translatable(this.hand == InteractionHand.MAIN_HAND
                        ? "gui.deepspacedeployer.bind_hand_main"
                        : "gui.deepspacedeployer.bind_hand_off"),
                cx, top + 188, 0x707070);

        // 主题盖在最上面（卡界面暂时用原生布局，主题未启用）
        this.webview.render(graphics, mouseX, mouseY, partialTick);
    }

    /** 2 列 × 4 行：6 家公司 + 通用 + 总资产 */
    private void drawWalletGrid(GuiGraphics graphics, int cx, int top, CardWalletPayload wallet) {
        long[] balances = wallet == null ? new long[CompanyFunds.SLOT_COUNT] : wallet.balances();
        int left = cx - 140;
        int right = cx + 4;
        for (int i = 0; i < 8; i++) {
            int col = i % 2;
            int row = i / 2;
            int x = col == 0 ? left : right;
            int y = top + row * 13;
            String label;
            long value;
            int color = 0xFFFFFF;
            if (i < Company.COUNT) {
                Company company = Company.VALUES[i];
                label = company.displayName().getString();
                value = balances[company.ordinal()];
            } else if (i == Company.COUNT) {
                label = Component.translatable("gui.deepspacedeployer.card_generic").getString();
                value = balances[CompanyFunds.GENERIC_SLOT];
            } else {
                label = Component.translatable("gui.deepspacedeployer.card_total").getString();
                value = wallet == null ? 0L : wallet.totalAssets();
                color = 0xFFD75E;
            }
            graphics.drawString(this.font, label, x, y, 0x9A9A9A, false);
            String text = Long.toString(value);
            graphics.drawString(this.font, text, x + 90 - this.font.width(text), y, color, false);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.webview.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
