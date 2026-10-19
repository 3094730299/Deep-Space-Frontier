package com.deepspacedeployer.client;

import com.deepspacedeployer.economy.CardData;
import com.deepspacedeployer.net.BindCardPayload;
import com.deepspacedeployer.ui.webview.ThemeData;
import com.deepspacedeployer.ui.webview.ThemeSlotLayout;
import com.deepspacedeployer.ui.webview.WebScreenId;
import com.deepspacedeployer.ui.webview.WebViewManager;
import com.deepspacedeployer.ui.webview.WebViewScreen;
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
 * 银行卡界面：绑定账户 / 给卡起名字 / 解除绑定。
 *
 * <p>改名会写进物品的"自定义名称"，所以在背包、工具提示、机器界面里都能看到这个名字 ——
 * 多人服务器上有多张卡时很好用。</p>
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
        int cy = this.height / 2;

        ItemStack card = heldCard();

        this.nameBox = new EditBox(this.font, cx - 100, cy - 10, 200, 20,
                Component.translatable("gui.deepspacedeployer.bind_field"));
        this.nameBox.setMaxLength(64);
        this.nameBox.setValue(CardData.accountName(card));
        this.addRenderableWidget(this.nameBox);

        this.labelBox = new EditBox(this.font, cx - 100, cy + 16, 200, 20,
                Component.translatable("gui.deepspacedeployer.bind_label_field"));
        this.labelBox.setMaxLength(48);
        this.labelBox.setValue(CardData.cardLabel(card));
        this.addRenderableWidget(this.labelBox);

        // 绑定（同时应用卡名）
        this.addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.bind_button"),
                        b -> submit(BindCardPayload.ACTION_BIND))
                .bounds(cx - 100, cy + 44, 64, 20).build());

        // 只改名
        this.addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.rename_button"),
                        b -> submit(BindCardPayload.ACTION_RENAME))
                .bounds(cx - 32, cy + 44, 64, 20).build());

        // 解除绑定
        this.addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.unbind_button"),
                        b -> submit(BindCardPayload.ACTION_UNBIND))
                .bounds(cx + 36, cy + 44, 64, 20).build());

        this.setInitialFocus(this.nameBox);

        // ★ 界面主题：原生输入框建完之后装配（主题只负责画框和文字）
        this.webview.setEnabled(WebViewManager.enabled());
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

    private void submit(int action) {
        PacketDistributor.sendToServer(new BindCardPayload(
                action == BindCardPayload.ACTION_BIND ? this.nameBox.getValue() : "",
                this.labelBox.getValue(),
                action));
        if (action == BindCardPayload.ACTION_UNBIND) {
            this.nameBox.setValue("");
        }
        this.onClose();
    }

    // ---------------------------------------------------------------- 主题：数据与动作

    /** 喂给 card_bind.html 的数据 */
    private ThemeData buildThemeData() {
        ItemStack card = heldCard();
        ThemeData d = new ThemeData();
        boolean bound = CardData.hasAccount(card);
        d.put("有卡主", bound);
        d.put("卡主", CardData.accountName(card));
        d.put("卡名", CardData.cardLabel(card));
        d.put("是副卡", CardData.isSubCard(card));
        d.put("主手", this.hand == InteractionHand.MAIN_HAND);
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
                submit(BindCardPayload.ACTION_UNBIND);
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

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (this.webview.themeChanged()) {
            this.webview.refresh();
        }
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);

        int cx = this.width / 2;
        graphics.drawCenteredString(this.font, this.title, cx, this.height / 2 - 62, 0xFFFFFF);
        graphics.drawCenteredString(this.font,
                Component.translatable("gui.deepspacedeployer.bind_hint"),
                cx, this.height / 2 - 46, 0xA0A0A0);
        // 副卡额外提示一句：余额独立、只能转账进出
        if (CardData.isSubCard(heldCard())) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("gui.deepspacedeployer.bind_sub_hint"),
                    cx, this.height / 2 - 34, 0xD8A0FF);
        }
        graphics.drawCenteredString(this.font,
                Component.translatable(this.hand == InteractionHand.MAIN_HAND
                        ? "gui.deepspacedeployer.bind_hand_main"
                        : "gui.deepspacedeployer.bind_hand_off"),
                cx, this.height / 2 + 74, 0x707070);

        // 主题盖在最上面（没主题就什么都不画）
        this.webview.render(graphics, mouseX, mouseY, partialTick);
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
