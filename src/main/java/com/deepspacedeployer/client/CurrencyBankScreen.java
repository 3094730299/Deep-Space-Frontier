package com.deepspacedeployer.client;

import com.deepspacedeployer.Company;
import com.deepspacedeployer.economy.CompanyFunds;
import com.deepspacedeployer.net.BankStatePayload;
import com.deepspacedeployer.net.CardListPayload.CardEntry;
import com.deepspacedeployer.net.CurrencyBankPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * 深空货币银行界面（原生，无主题）。
 *
 * <p>两个共享卡槽：左边/右边显示别人的卡和你的卡；一个「确认转账」按钮：
 * 填了目标名字 → 按名字转；没填但对面卡槽有别人的卡 → 当面转；都没有 → 按
 * 上方源/目标做自己的货币转换。</p>
 */
public class CurrencyBankScreen extends Screen {

    private int source = 0;
    private int target = 1;
    private EditBox amountBox;
    private EditBox targetBox;
    private Button sourceButton;
    private Button targetButton;
    private Button selectorButton;
    private int selectedCard;
    private String status = "";
    private boolean confirmVoid;

    public CurrencyBankScreen() {
        super(Component.translatable("gui.deepspacedeployer.bank_title"));
    }

    private int contentTop() {
        return Math.max(4, this.height / 2 - 116);
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int top = contentTop();

        this.sourceButton = Button.builder(label(true), b -> {
            this.source = (this.source + 1) % CompanyFunds.SLOT_COUNT;
            this.sourceButton.setMessage(label(true));
            this.confirmVoid = false;
        }).bounds(cx - 140, top + 96, 130, 18).build();
        this.addRenderableWidget(this.sourceButton);

        this.targetButton = Button.builder(label(false), b -> {
            this.target = (this.target + 1) % CompanyFunds.SLOT_COUNT;
            this.targetButton.setMessage(label(false));
            this.confirmVoid = false;
        }).bounds(cx + 10, top + 96, 130, 18).build();
        this.addRenderableWidget(this.targetButton);

        this.amountBox = new EditBox(this.font, cx - 140, top + 118, 100, 18,
                Component.translatable("gui.deepspacedeployer.bank_amount"));
        this.amountBox.setMaxLength(18);
        this.amountBox.setValue("100");
        this.amountBox.setHint(Component.translatable("gui.deepspacedeployer.bank_amount"));
        this.addRenderableWidget(this.amountBox);

        this.targetBox = new EditBox(this.font, cx - 30, top + 118, 170, 18,
                Component.translatable("gui.deepspacedeployer.bank_target_name"));
        this.targetBox.setMaxLength(64);
        this.targetBox.setHint(Component.translatable("gui.deepspacedeployer.bank_target_name"));
        this.addRenderableWidget(this.targetBox);

        this.addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.bank_confirm"),
                        b -> submitConfirm())
                .bounds(cx - 150, top + 142, 150, 20).build());

        this.selectorButton = Button.builder(selectorLabel(), b -> {
            List<CardEntry> active = activeCards();
            if (active.isEmpty()) {
                return;
            }
            this.selectedCard = (this.selectedCard + 1) % active.size();
            this.selectorButton.setMessage(selectorLabel());
            this.confirmVoid = false;
            this.status = "";
        }).bounds(cx - 150, top + 176, 100, 18).build();
        this.addRenderableWidget(this.selectorButton);

        this.addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.bank_free_card"),
                        b -> submit(CurrencyBankPayload.ACTION_FREE_CARD))
                .bounds(cx - 46, top + 176, 70, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.bank_void"),
                        b -> submit(CurrencyBankPayload.ACTION_VOID))
                .bounds(cx + 28, top + 176, 60, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.bank_close"),
                        b -> this.onClose())
                .bounds(cx + 92, top + 176, 55, 18).build());

        this.setInitialFocus(this.amountBox);
    }

    public void onBankStateUpdate() {
        // 原生界面每帧直接读 BankStateClient，不需要重建控件
    }

    public void onCardListUpdate() {
        List<CardEntry> active = activeCards();
        if (this.selectedCard >= active.size()) {
            this.selectedCard = 0;
        }
        this.confirmVoid = false;
        this.status = "";
        if (this.selectorButton != null) {
            this.selectorButton.setMessage(selectorLabel());
        }
    }

    private Component label(boolean isSource) {
        int slot = isSource ? this.source : this.target;
        Component name = slot == CompanyFunds.GENERIC_SLOT
                ? Component.translatable("gui.deepspacedeployer.bank_generic")
                : Company.VALUES[slot].displayName();
        return Component.translatable(isSource
                ? "gui.deepspacedeployer.bank_source" : "gui.deepspacedeployer.bank_target", name);
    }

    private List<CardEntry> activeCards() {
        List<CardEntry> out = new ArrayList<>();
        for (CardEntry entry : CardListClient.cards()) {
            if (!entry.voided()) {
                out.add(entry);
            }
        }
        return out;
    }

    private String selectedCardId() {
        List<CardEntry> active = activeCards();
        if (active.isEmpty()) {
            return "";
        }
        if (this.selectedCard >= active.size()) {
            this.selectedCard = 0;
        }
        return active.get(this.selectedCard).cardId();
    }

    private String selectedVoidName() {
        List<CardEntry> active = activeCards();
        if (active.isEmpty()) {
            return Component.translatable("gui.deepspacedeployer.bank_no_card_select").getString();
        }
        return active.get(Math.min(this.selectedCard, active.size() - 1)).name();
    }

    private Component selectorLabel() {
        if (activeCards().isEmpty()) {
            return Component.translatable("gui.deepspacedeployer.bank_no_card_select");
        }
        String name = selectedVoidName();
        String shown = this.font.width(name) > 54
                ? this.font.plainSubstrByWidth(name, 52) + "…"
                : name;
        return Component.translatable("gui.deepspacedeployer.bank_select_card", shown);
    }

    private long readAmount() {
        try {
            return Long.parseLong(this.amountBox.getValue().trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private void submitConfirm() {
        long amount = readAmount();
        if (amount <= 0L) {
            this.status = Component.translatable("msg.deepspacedeployer.bank_amount_invalid").getString();
            return;
        }
        this.status = "";
        this.confirmVoid = false;
        PacketDistributor.sendToServer(new CurrencyBankPayload(
                CurrencyBankPayload.ACTION_CONFIRM, this.source, this.target, amount,
                this.targetBox.getValue().trim(), "", 0));
    }

    private void submit(int action) {
        if (action == CurrencyBankPayload.ACTION_VOID) {
            String cardId = selectedCardId();
            if (cardId.isEmpty()) {
                this.status = Component.translatable("msg.deepspacedeployer.bank_no_card").getString();
                return;
            }
            if (!this.confirmVoid) {
                this.confirmVoid = true;
                this.status = Component.translatable("gui.deepspacedeployer.bank_void_confirm").getString();
                return;
            }
            this.status = "";
            this.confirmVoid = false;
            PacketDistributor.sendToServer(new CurrencyBankPayload(
                    action, this.source, this.target, 0L, "", cardId, 0));
            return;
        }
        this.status = "";
        this.confirmVoid = false;
        PacketDistributor.sendToServer(new CurrencyBankPayload(
                action, this.source, this.target, 0L, "", "", 0));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);

        int cx = this.width / 2;
        int top = contentTop();
        BankStatePayload state = BankStateClient.get();
        long[] balances = state == null ? new long[CompanyFunds.SLOT_COUNT] : state.balances();

        graphics.drawCenteredString(this.font, this.title, cx, top, 0xFFFFFF);

        // 两个共享卡槽（带卡物品图标）
        List<BankStatePayload.Slot> slots = state == null ? List.of() : state.slots();
        drawSlotBox(graphics, cx - 140, top + 12, 1, slots.size() > 0 ? slots.get(0) : null);
        drawSlotBox(graphics, cx + 4, top + 12, 2, slots.size() > 1 ? slots.get(1) : null);

        // 自己卡里的 7 个账户
        int gridTop = top + 40;
        for (int i = 0; i < 8; i++) {
            int col = i % 2;
            int row = i / 2;
            int x = col == 0 ? cx - 140 : cx + 4;
            int y = gridTop + row * 10;
            String label;
            long value;
            int color = 0xFFFFFF;
            if (i < Company.COUNT) {
                label = Company.VALUES[i].displayName().getString();
                value = balances[i];
            } else if (i == Company.COUNT) {
                label = Component.translatable("gui.deepspacedeployer.bank_generic").getString();
                value = balances[CompanyFunds.GENERIC_SLOT];
            } else {
                label = Component.translatable("gui.deepspacedeployer.card_total").getString();
                value = state == null ? 0L : state.totalAssets();
                color = 0xFFD75E;
            }
            graphics.drawString(this.font, label, x, y, 0x9A9A9A, false);
            String text = Long.toString(value);
            graphics.drawString(this.font, text, x + 88 - this.font.width(text), y, color, false);
        }

        graphics.drawCenteredString(this.font,
                Component.translatable("gui.deepspacedeployer.bank_total",
                        state == null ? 0L : state.totalAssets()), cx, top + 82, 0xFFD75E);

        // 税率写在确认按钮右侧
        int taxX = cx + 20;
        graphics.drawString(this.font,
                Component.translatable("gui.deepspacedeployer.bank_tax_title"),
                taxX, top + 144, 0xFFD75E, false);
        drawTaxLine(graphics, taxX, top + 154,
                Component.translatable("gui.deepspacedeployer.bank_company_to_company").getString() + " 20%  "
                        + Component.translatable("gui.deepspacedeployer.bank_company_to_generic").getString() + " 30%");
        drawTaxLine(graphics, taxX, top + 164,
                Component.translatable("gui.deepspacedeployer.bank_generic_to_company").getString() + " 0%  "
                        + Component.translatable("gui.deepspacedeployer.bank_transfer").getString() + " 5%");

        String selected = selectedVoidName();
        String statusLine = this.status.isEmpty()
                ? Component.translatable("gui.deepspacedeployer.bank_selected_void", selected).getString()
                : this.status;
        graphics.drawCenteredString(this.font, Component.literal(statusLine), cx, top + 198,
                this.status.isEmpty() ? 0x9A9A9A : 0xFFD166);

        graphics.drawCenteredString(this.font,
                Component.translatable("gui.deepspacedeployer.bank_transfer_hint"), cx, top + 212, 0x707070);
        graphics.drawCenteredString(this.font,
                Component.translatable("gui.deepspacedeployer.bank_use_hint"), cx, top + 224, 0x707070);
    }

    private void drawSlotBox(GuiGraphics graphics, int x, int y, int index, BankStatePayload.Slot slot) {
        graphics.fill(x, y, x + 20, y + 20, 0xAA0B1020);
        graphics.fill(x, y, x + 20, y + 1, 0xFF4A5568);
        graphics.fill(x, y + 19, x + 20, y + 20, 0xFF4A5568);
        graphics.fill(x, y, x + 1, y + 20, 0xFF4A5568);
        graphics.fill(x + 19, y, x + 20, y + 20, 0xFF4A5568);

        String name;
        String owner = "";
        int color;
        if (slot == null || slot.empty()) {
            name = Component.translatable("gui.deepspacedeployer.bank_slot_empty").getString();
            color = 0x6E7891;
        } else {
            graphics.renderItem(slot.stack(), x + 2, y + 2);
            graphics.renderItemDecorations(this.font, slot.stack(), x + 2, y + 2);
            name = slot.name();
            owner = slot.mine()
                    ? Component.translatable("gui.deepspacedeployer.bank_slot_mine").getString()
                    : slot.ownerName();
            color = slot.voided() ? 0x7A7A7A : (slot.mine() ? 0x7FD3E8 : 0xFFD479);
        }

        String line = Component.translatable("gui.deepspacedeployer.bank_slot_line", index, name).getString();
        if (this.font.width(line) > 90) {
            line = this.font.plainSubstrByWidth(line, 86) + "…";
        }
        graphics.drawString(this.font, line, x + 24, y + 2, color, false);
        if (!owner.isEmpty()) {
            String shown = this.font.width(owner) > 90
                    ? this.font.plainSubstrByWidth(owner, 86) + "…"
                    : owner;
            graphics.drawString(this.font, shown, x + 24, y + 12, 0x9A9A9A, false);
        }
    }

    private void drawTaxLine(GuiGraphics graphics, int x, int y, String text) {
        if (this.font.width(text) > 140) {
            text = this.font.plainSubstrByWidth(text, 136) + "…";
        }
        graphics.drawString(this.font, text, x, y, 0xC8D2E0, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
