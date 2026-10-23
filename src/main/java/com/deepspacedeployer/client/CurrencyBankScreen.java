package com.deepspacedeployer.client;

import com.deepspacedeployer.Company;
import com.deepspacedeployer.economy.CardData;
import com.deepspacedeployer.economy.CompanyFunds;
import com.deepspacedeployer.economy.CurrencyDenomination;
import com.deepspacedeployer.menu.CurrencyBankMenu;
import com.deepspacedeployer.net.BankStatePayload;
import com.deepspacedeployer.net.CardListPayload;
import com.deepspacedeployer.net.CurrencyBankPayload;
import com.deepspacedeployer.registry.ModItems;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 深空货币银行：功能主页 + 取钱/存钱、转账、挂失、免费取卡四个子界面。
 *
 * <p>每个子界面左上角都有「返回」。转账/挂失页显示两个真实卡槽，卡槽下方分别列出
 * 该卡账户的 7 个币种 + 总资产。挂失只认卡槽里的卡：另一个卡槽有有效卡就把钱全部
 * 转过去；没有接收卡时第一次只提示，第二次才强行挂失并清空余额。</p>
 */
public class CurrencyBankScreen extends AbstractContainerScreen<CurrencyBankMenu> {

    private enum Page {
        HUB, WITHDRAW, TRANSFER, VOID, FREE_CARD
    }

    private static final int PANEL = 0xFF202636;
    private static final int SLOT_BG = 0xFF0B1020;
    private static final int SLOT_LINE = 0xFF4A5568;
    private static final int GOLD = 0xFFFFD166;
    private static final int DIM = 0xFF9AA4B2;

    private Page page = Page.HUB;
    private EditBox amountBox;
    private EditBox targetBox;
    private Button sourceButton;
    private Button targetButton;
    private Button lostSelectorButton;
    private Button cashCompanyButton;
    private Button cashDenomButton;
    private EditBox cashAmountBox;
    private int lostIndex;
    private int cashCompany;
    private int cashDenom;
    private int source = 0;
    private int target = 1;

    private String status = "";

    public CurrencyBankScreen(CurrencyBankMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 320;
        this.imageHeight = 240;
        this.titleLabelY = -1000;      // 自己画页面标题
        this.inventoryLabelY = -1000;  // 自己控制布局
    }

    @Override
    protected void init() {
        super.init();
        updateCardSlots();
        this.rebuildPageWidgets();
    }

    private boolean isCardPage() {
        return this.page == Page.TRANSFER || this.page == Page.VOID || this.page == Page.WITHDRAW;
    }

    private boolean isSlotVisible(Slot slot) {
        if (slot.index >= CurrencyBankMenu.BANK_SLOTS) {
            return true;
        }
        if (this.page == Page.TRANSFER) {
            return true;
        }
        return (this.page == Page.VOID || this.page == Page.WITHDRAW) && slot.index == 0;
    }

    private void updateCardSlots() {
        // 转账页两个卡槽；挂失/存取页只保留第 1 个卡槽；其它页面不显示
        this.menu.setCardSlotActive(0, this.page == Page.TRANSFER
                || this.page == Page.VOID || this.page == Page.WITHDRAW);
        this.menu.setCardSlotActive(1, this.page == Page.TRANSFER);
    }

    private void setPage(Page page) {
        this.page = page;
        this.status = "";
        updateCardSlots();
        this.rebuildPageWidgets();
    }

    private void rebuildPageWidgets() {
        this.clearWidgets();
        int x = this.leftPos;
        int y = this.topPos;

        if (this.page != Page.HUB) {
            this.addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.bank_back"),
                            b -> setPage(Page.HUB))
                    .bounds(x + 8, y + 6, 50, 18).build());
        }

        switch (this.page) {
            case HUB -> {
                this.addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.bank_hub_withdraw"),
                                b -> setPage(Page.WITHDRAW))
                        .bounds(x + 20, y + 50, 140, 24).build());
                this.addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.bank_hub_transfer"),
                                b -> setPage(Page.TRANSFER))
                        .bounds(x + 170, y + 50, 140, 24).build());
                this.addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.bank_hub_void"),
                                b -> setPage(Page.VOID))
                        .bounds(x + 20, y + 84, 140, 24).build());
                this.addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.bank_hub_free"),
                                b -> setPage(Page.FREE_CARD))
                        .bounds(x + 170, y + 84, 140, 24).build());
                this.addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.bank_close"),
                                b -> this.onClose())
                        .bounds(x + 262, y + 6, 50, 18).build());
            }
            case TRANSFER -> {
                this.sourceButton = Button.builder(label(true), b -> {
                    this.source = (this.source + 1) % CompanyFunds.SLOT_COUNT;
                    this.sourceButton.setMessage(label(true));
                }).bounds(x + 20, y + 94, 130, 18).build();
                this.addRenderableWidget(this.sourceButton);

                this.targetButton = Button.builder(label(false), b -> {
                    this.target = (this.target + 1) % CompanyFunds.SLOT_COUNT;
                    this.targetButton.setMessage(label(false));
                }).bounds(x + 170, y + 94, 130, 18).build();
                this.addRenderableWidget(this.targetButton);

                this.amountBox = new EditBox(this.font, x + 20, y + 116, 100, 18,
                        Component.translatable("gui.deepspacedeployer.bank_amount"));
                this.amountBox.setMaxLength(18);
                this.amountBox.setValue("100");
                this.amountBox.setHint(Component.translatable("gui.deepspacedeployer.bank_amount"));
                this.addRenderableWidget(this.amountBox);

                this.targetBox = new EditBox(this.font, x + 130, y + 116, 170, 18,
                        Component.translatable("gui.deepspacedeployer.bank_target_name"));
                this.targetBox.setMaxLength(64);
                this.targetBox.setHint(Component.translatable("gui.deepspacedeployer.bank_target_name"));
                this.addRenderableWidget(this.targetBox);

                this.addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.bank_confirm"),
                                b -> submitConfirm())
                        .bounds(x + 20, y + 138, 160, 20).build());
                this.setInitialFocus(this.amountBox);
            }
            case VOID -> {
                this.lostSelectorButton = Button.builder(lostLabel(), b -> {
                    List<CardListPayload.CardEntry> lost = lostCards();
                    if (lost.isEmpty()) {
                        this.status = Component.translatable(
                                "gui.deepspacedeployer.bank_no_other_card").getString();
                        return;
                    }
                    this.lostIndex = (this.lostIndex + 1) % lost.size();
                    this.lostSelectorButton.setMessage(lostLabel());
                    this.status = "";
                }).bounds(x + 170, y + 24, 140, 18).build();
                this.addRenderableWidget(this.lostSelectorButton);

                this.addRenderableWidget(Button.builder(
                                Component.translatable("gui.deepspacedeployer.bank_void"),
                                b -> clickVoid())
                        .bounds(x + 20, y + 94, 140, 18).build());
            }
            case FREE_CARD -> this.addRenderableWidget(Button.builder(
                            Component.translatable("gui.deepspacedeployer.bank_free_claim"),
                            b -> submitFree())
                    .bounds(x + 80, y + 70, 160, 20).build());
            case WITHDRAW -> {
                this.cashCompanyButton = Button.builder(cashCompanyLabel(), b -> {
                    this.cashCompany = (this.cashCompany + 1) % Company.COUNT;
                    this.cashCompanyButton.setMessage(cashCompanyLabel());
                }).bounds(x + 42, y + 40, 120, 18).build();
                this.addRenderableWidget(this.cashCompanyButton);

                this.cashDenomButton = Button.builder(cashDenomLabel(), b -> {
                    this.cashDenom = (this.cashDenom + 1) % CurrencyDenomination.COUNT;
                    this.cashDenomButton.setMessage(cashDenomLabel());
                }).bounds(x + 170, y + 40, 140, 18).build();
                this.addRenderableWidget(this.cashDenomButton);

                this.cashAmountBox = new EditBox(this.font, x + 20, y + 86, 60, 18,
                        Component.translatable("gui.deepspacedeployer.bank_cash_count"));
                this.cashAmountBox.setMaxLength(3);
                this.cashAmountBox.setValue("1");
                this.cashAmountBox.setHint(Component.translatable(
                        "gui.deepspacedeployer.bank_cash_count"));
                this.addRenderableWidget(this.cashAmountBox);

                this.addRenderableWidget(Button.builder(
                                Component.translatable("gui.deepspacedeployer.bank_cash_deposit_selected"),
                                b -> submitCashDeposit(false))
                        .bounds(x + 90, y + 86, 90, 18).build());
                this.addRenderableWidget(Button.builder(
                                Component.translatable("gui.deepspacedeployer.bank_cash_deposit_all"),
                                b -> submitCashDeposit(true))
                        .bounds(x + 190, y + 86, 100, 18).build());
                this.addRenderableWidget(Button.builder(
                                Component.translatable("gui.deepspacedeployer.bank_cash_withdraw"),
                                b -> submitCashWithdraw())
                        .bounds(x + 20, y + 110, 100, 18).build());
                this.setInitialFocus(this.cashAmountBox);
            }
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;
        graphics.fill(x, y, x + this.imageWidth, y + this.imageHeight, 0xF0101018);
        graphics.fill(x + 1, y + 1, x + this.imageWidth - 1, y + this.imageHeight - 1, PANEL);
        graphics.fill(x + 1, y + 15, x + this.imageWidth - 1, y + 16, 0xFF54C8FF);

        if (this.page == Page.TRANSFER) {
            drawSlotFrame(graphics, x + CurrencyBankMenu.BANK_SLOT_1_X, y + CurrencyBankMenu.BANK_SLOT_1_Y);
            drawSlotFrame(graphics, x + CurrencyBankMenu.BANK_SLOT_2_X, y + CurrencyBankMenu.BANK_SLOT_2_Y);
        } else if (this.page == Page.VOID || this.page == Page.WITHDRAW) {
            drawSlotFrame(graphics, x + CurrencyBankMenu.BANK_SLOT_1_X, y + CurrencyBankMenu.BANK_SLOT_1_Y);
        }

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                drawSlotFrame(graphics, x + CurrencyBankMenu.PLAYER_INV_X + col * 18,
                        y + CurrencyBankMenu.PLAYER_MAIN_Y + row * 18);
            }
        }
        for (int col = 0; col < 9; col++) {
            drawSlotFrame(graphics, x + CurrencyBankMenu.PLAYER_INV_X + col * 18,
                    y + CurrencyBankMenu.PLAYER_HOTBAR_Y);
        }
    }

    private void drawSlotFrame(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + 18, y + 18, SLOT_BG);
        graphics.fill(x, y, x + 18, y + 1, SLOT_LINE);
        graphics.fill(x, y + 17, x + 18, y + 18, SLOT_LINE);
        graphics.fill(x, y, x + 1, y + 18, SLOT_LINE);
        graphics.fill(x + 17, y, x + 18, y + 18, SLOT_LINE);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);

        int x = this.leftPos;
        int y = this.topPos;
        graphics.drawCenteredString(this.font, pageTitle(), x + this.imageWidth / 2, y + 6, 0xFFFFFF);

        switch (this.page) {
            case HUB -> {
                // 按钮自己会画
            }
            case WITHDRAW -> {
                BankStatePayload state = BankStateClient.get();
                BankStatePayload.Slot slot0 = state != null && !state.slots().isEmpty()
                        ? state.slots().get(0) : null;
                String cardName = slot0 == null || slot0.empty()
                        ? Component.translatable("gui.deepspacedeployer.bank_slot_empty").getString()
                        : slot0.name();
                String cardLine = Component.translatable(
                        "gui.deepspacedeployer.bank_cash_card", cardName).getString();
                if (this.font.width(cardLine) > 250) {
                    cardLine = this.font.plainSubstrByWidth(cardLine, 246) + "…";
                }
                graphics.drawString(this.font, cardLine, x + 62, y + 24, 0x7FD3E8, false);

                String accountLine = Component.translatable(
                        "gui.deepspacedeployer.bank_cash_account", cashBalance()).getString();
                if (this.font.width(accountLine) > 145) {
                    accountLine = this.font.plainSubstrByWidth(accountLine, 141) + "…";
                }
                graphics.drawString(this.font, accountLine, x + 20, y + 62, 0xE8F4FF, false);

                String holdingLine = Component.translatable(
                        "gui.deepspacedeployer.bank_cash_holding",
                        cashHeld(), cashHeldAll()).getString();
                if (this.font.width(holdingLine) > 145) {
                    holdingLine = this.font.plainSubstrByWidth(holdingLine, 141) + "…";
                }
                graphics.drawString(this.font, holdingLine, x + 170, y + 62, 0xC8D2E0, false);

                graphics.drawString(this.font,
                        Component.translatable("gui.deepspacedeployer.bank_cash_value",
                                cashDenomValue()).getString(),
                        x + 20, y + 74, DIM, false);
                String note = Component.translatable("gui.deepspacedeployer.bank_cash_note").getString();
                if (this.font.width(note) > 290) {
                    note = this.font.plainSubstrByWidth(note, 286) + "…";
                }
                graphics.drawString(this.font, note, x + 20, y + 146, DIM, false);
                if (!this.status.isEmpty()) {
                    drawCenteredFit(graphics, this.status, x + this.imageWidth / 2, y + 132, GOLD);
                }
            }
            case TRANSFER -> {
                drawCardTable(graphics, 0, x + 20, y + 46);
                drawCardTable(graphics, 1, x + 170, y + 46);
                drawTaxLine(graphics, x + 190, y + 140,
                        Component.translatable("gui.deepspacedeployer.bank_company_to_company").getString() + " 20%  "
                                + Component.translatable("gui.deepspacedeployer.bank_company_to_generic").getString() + " 30%");
                drawTaxLine(graphics, x + 190, y + 150,
                        Component.translatable("gui.deepspacedeployer.bank_generic_to_company").getString() + " 0%  "
                                + Component.translatable("gui.deepspacedeployer.bank_transfer").getString() + " 5%");
            }
            case VOID -> {
                // 左边卡槽 = 接收余额的卡；右边选择并显示“其他卡”（要挂失的卡）
                drawCardTable(graphics, 0, x + 20, y + 46);
                CardListPayload.CardEntry lost = selectedLost();
                String lostTitle = lost == null
                        ? Component.translatable("gui.deepspacedeployer.bank_no_other_card").getString()
                        : Component.translatable("gui.deepspacedeployer.bank_select_card", lost.name()).getString();
                long[] lostBalances = lost == null ? new long[CompanyFunds.SLOT_COUNT] : lost.balances();
                long lostTotal = lost == null ? 0L : lost.totalAssets();
                drawTable(graphics, lostTitle, lostBalances, lostTotal, x + 170, y + 46, GOLD);
                graphics.drawString(this.font,
                        Component.translatable("gui.deepspacedeployer.bank_void_receiver_note"),
                        x + 170, y + 94, DIM, false);
                graphics.drawString(this.font,
                        Component.translatable("gui.deepspacedeployer.bank_void_lost_note"),
                        x + 170, y + 106, DIM, false);
                if (!this.status.isEmpty()) {
                    drawCenteredFit(graphics, this.status, x + this.imageWidth / 2, y + 118, GOLD);
                }
            }
            case FREE_CARD -> {
                String hint = this.status.isEmpty()
                        ? Component.translatable("gui.deepspacedeployer.bank_free_hint").getString()
                        : this.status;
                drawCenteredFit(graphics, hint, x + this.imageWidth / 2, y + 100,
                        this.status.isEmpty() ? DIM : GOLD);
            }
        }
    }

    private Component pageTitle() {
        return switch (this.page) {
            case HUB -> Component.translatable("gui.deepspacedeployer.bank_title");
            case WITHDRAW -> Component.translatable("gui.deepspacedeployer.bank_page_withdraw");
            case TRANSFER -> Component.translatable("gui.deepspacedeployer.bank_page_transfer");
            case VOID -> Component.translatable("gui.deepspacedeployer.bank_page_void");
            case FREE_CARD -> Component.translatable("gui.deepspacedeployer.bank_page_free");
        };
    }

    private void drawCardTable(GuiGraphics graphics, int index, int x, int y) {
        BankStatePayload state = BankStateClient.get();
        BankStatePayload.Slot slot = state != null && state.slots().size() > index
                ? state.slots().get(index) : null;
        String name = slot == null || slot.empty()
                ? Component.translatable("gui.deepspacedeployer.bank_slot_empty").getString()
                : slot.name();
        String title = Component.translatable("gui.deepspacedeployer.bank_slot_line", index + 1, name).getString();
        int titleColor = slot == null || slot.empty() ? 0x6E7891
                : (slot.voided() ? 0x7A7A7A : (slot.mine() ? 0x7FD3E8 : 0xFFD479));
        long[] balances = slot == null || slot.balances() == null
                ? new long[CompanyFunds.SLOT_COUNT] : slot.balances();
        long total = slot == null ? 0L : slot.totalAssets();
        drawTable(graphics, title, balances, total, x, y, titleColor);
    }

    private void drawTable(GuiGraphics graphics, String title, long[] balances,
                           long total, int x, int y, int titleColor) {
        if (this.font.width(title) > 140) {
            title = this.font.plainSubstrByWidth(title, 136) + "…";
        }
        graphics.drawString(this.font, title, x, y, titleColor, false);

        for (int i = 0; i < 8; i++) {
            int col = i % 2;
            int row = i / 2;
            int cx = x + col * 70;
            int cy = y + 9 + row * 9;
            String label;
            long value;
            int color = 0xE8F4FF;
            if (i < Company.COUNT) {
                label = Company.VALUES[i].displayName().getString();
                value = balances[i];
            } else if (i == Company.COUNT) {
                label = Component.translatable("gui.deepspacedeployer.bank_generic").getString();
                value = balances[CompanyFunds.GENERIC_SLOT];
            } else {
                label = Component.translatable("gui.deepspacedeployer.card_total").getString();
                value = total;
                color = GOLD;
            }
            if (this.font.width(label) > 34) {
                label = this.font.plainSubstrByWidth(label, 32) + "…";
            }
            graphics.drawString(this.font, label, cx, cy, 0x9A9A9A, false);
            String text = Long.toString(value);
            graphics.drawString(this.font, text, cx + 70 - this.font.width(text), cy, color, false);
        }
    }

    // ---------------- 挂失页：卡槽内的卡是接收卡，挂失的是“其他卡” ----------------

    private List<CardListPayload.CardEntry> lostCards() {
        List<CardListPayload.CardEntry> out = new ArrayList<>();
        String slot0 = cardIdOfSlot(0);
        String slot1 = cardIdOfSlot(1);
        for (CardListPayload.CardEntry entry : CardListClient.cards()) {
            if (entry.voided()) {
                continue;
            }
            String id = entry.cardId();
            if (id == null || id.equals(slot0) || id.equals(slot1)) {
                continue;
            }
            out.add(entry);
        }
        return out;
    }

    private String cardIdOfSlot(int slot) {
        ItemStack stack = this.menu.slots.get(slot).getItem();
        UUID id = CardData.cardId(stack);
        return id == null ? "" : id.toString();
    }

    private CardListPayload.CardEntry selectedLost() {
        List<CardListPayload.CardEntry> lost = lostCards();
        if (lost.isEmpty()) {
            return null;
        }
        if (this.lostIndex >= lost.size()) {
            this.lostIndex = 0;
        }
        return lost.get(this.lostIndex);
    }

    private Component lostLabel() {
        List<CardListPayload.CardEntry> lost = lostCards();
        if (lost.isEmpty()) {
            return Component.translatable("gui.deepspacedeployer.bank_no_other_card");
        }
        int index = Math.min(this.lostIndex, lost.size() - 1);
        String name = lost.get(index).name();
        String shown = this.font.width(name) > 44
                ? this.font.plainSubstrByWidth(name, 42) + "…"
                : name;
        return Component.translatable("gui.deepspacedeployer.bank_select_card_count",
                index + 1, lost.size(), shown);
    }

    /** 转账页两个源/目标按钮：显示当前选中的币种和查看者卡内余额。 */
    private Component label(boolean isSource) {
        int slot = isSource ? this.source : this.target;
        Component name = slot == CompanyFunds.GENERIC_SLOT
                ? Component.translatable("gui.deepspacedeployer.bank_generic")
                : Company.VALUES[slot].displayName();
        BankStatePayload state = BankStateClient.get();
        long balance = state == null ? 0L : state.balances()[slot];
        return Component.translatable(isSource
                ? "gui.deepspacedeployer.bank_source_balance"
                : "gui.deepspacedeployer.bank_target_balance", name, balance);
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
        PacketDistributor.sendToServer(new CurrencyBankPayload(
                CurrencyBankPayload.ACTION_CONFIRM, this.source, this.target, amount,
                this.targetBox.getValue().trim(), "", 0, false));
    }

    private void submitFree() {
        this.status = Component.translatable("gui.deepspacedeployer.bank_free_requested").getString();
        PacketDistributor.sendToServer(new CurrencyBankPayload(
                CurrencyBankPayload.ACTION_FREE_CARD, 0, 0, 0L, "", "", 0, false));
    }

    // ---------------- 取钱 / 存钱（实体货币） ----------------

    private Component cashCompanyLabel() {
        String text = Component.translatable("gui.deepspacedeployer.bank_cash_company",
                Company.byIndex(this.cashCompany).displayName()).getString();
        if (this.font.width(text) > 116) {
            text = this.font.plainSubstrByWidth(text, 112) + "…";
        }
        return Component.literal(text);
    }

    private Component cashDenomLabel() {
        return Component.translatable("gui.deepspacedeployer.bank_cash_denom", cashDenomValue());
    }

    private int cashDenomValue() {
        return CurrencyDenomination.byIndex(this.cashDenom).value();
    }

    private long cashBalance() {
        BankStatePayload state = BankStateClient.get();
        if (state == null || state.balances() == null || this.cashCompany >= state.balances().length) {
            return 0L;
        }
        return state.balances()[this.cashCompany];
    }

    private int cashHeld() {
        if (this.minecraft == null || this.minecraft.player == null) {
            return 0;
        }
        var item = ModItems.currencyItem(Company.byIndex(this.cashCompany),
                CurrencyDenomination.byIndex(this.cashDenom));
        int count = 0;
        var inventory = this.minecraft.player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int cashHeldAll() {
        if (this.minecraft == null || this.minecraft.player == null) {
            return 0;
        }
        int count = 0;
        var inventory = this.minecraft.player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (ModItems.currencyOf(inventory.getItem(i)) != null) {
                count += inventory.getItem(i).getCount();
            }
        }
        return count;
    }

    private int readCashCount() {
        try {
            return Integer.parseInt(this.cashAmountBox.getValue().trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private boolean cashCardReady() {
        if (this.menu.slots.get(0).getItem().isEmpty()) {
            this.status = Component.translatable("msg.deepspacedeployer.bank_cash_need_card").getString();
            return false;
        }
        BankStatePayload state = BankStateClient.get();
        BankStatePayload.Slot slot0 = state != null && !state.slots().isEmpty()
                ? state.slots().get(0) : null;
        if (slot0 != null && slot0.voided()) {
            this.status = Component.translatable("msg.deepspacedeployer.card_voided").getString();
            return false;
        }
        if (slot0 != null && !slot0.mine()) {
            this.status = Component.translatable("msg.deepspacedeployer.card_not_yours").getString();
            return false;
        }
        return true;
    }

    private void submitCashDeposit(boolean all) {
        if (!cashCardReady()) {
            return;
        }
        if (all) {
            if (cashHeldAll() <= 0) {
                this.status = Component.translatable("msg.deepspacedeployer.bank_cash_no_items").getString();
                return;
            }
            this.status = "";
            PacketDistributor.sendToServer(new CurrencyBankPayload(
                    CurrencyBankPayload.ACTION_DEPOSIT_CASH, 0, 0, 0L, "", "", 0, false));
            return;
        }
        int count = readCashCount();
        if (count < 1 || count > 100) {
            this.status = Component.translatable("msg.deepspacedeployer.bank_cash_invalid").getString();
            return;
        }
        if (cashHeld() <= 0) {
            this.status = Component.translatable("msg.deepspacedeployer.bank_cash_no_items").getString();
            return;
        }
        this.status = "";
        PacketDistributor.sendToServer(new CurrencyBankPayload(
                CurrencyBankPayload.ACTION_DEPOSIT_CASH, this.cashCompany, this.cashDenom,
                count, "", "", 0, false));
    }

    private void submitCashWithdraw() {
        if (!cashCardReady()) {
            return;
        }
        int count = readCashCount();
        if (count < 1 || count > 100) {
            this.status = Component.translatable("msg.deepspacedeployer.bank_cash_invalid").getString();
            return;
        }
        long cost = (long) count * cashDenomValue();
        if (cashBalance() < cost) {
            this.status = Component.translatable("msg.deepspacedeployer.bank_cash_insufficient").getString();
            return;
        }
        this.status = "";
        PacketDistributor.sendToServer(new CurrencyBankPayload(
                CurrencyBankPayload.ACTION_WITHDRAW_CASH, this.cashCompany, this.cashDenom,
                count, "", "", 0, false));
    }

    private void clickVoid() {
        ItemStack receiver = this.menu.slots.get(0).getItem();
        if (receiver.isEmpty()) {
            this.status = Component.translatable("gui.deepspacedeployer.bank_void_need_receiver").getString();
            return;
        }
        BankStatePayload state = BankStateClient.get();
        BankStatePayload.Slot receiverSlot = state != null && !state.slots().isEmpty()
                ? state.slots().get(0) : null;
        if (receiverSlot != null && receiverSlot.voided()) {
            this.status = Component.translatable("msg.deepspacedeployer.card_voided").getString();
            return;
        }
        if (receiverSlot != null && !receiverSlot.mine()) {
            this.status = Component.translatable("msg.deepspacedeployer.card_not_yours").getString();
            return;
        }
        CardListPayload.CardEntry lost = selectedLost();
        if (lost == null) {
            this.status = Component.translatable("gui.deepspacedeployer.bank_no_other_card").getString();
            return;
        }
        this.status = "";
        PacketDistributor.sendToServer(new CurrencyBankPayload(
                CurrencyBankPayload.ACTION_VOID_SLOT, 0, 0, 0L, "", lost.cardId(), 0, false));
    }

    private void drawCenteredFit(GuiGraphics graphics, String text, int centerX, int y, int color) {
        int max = this.imageWidth - 20;
        if (this.font.width(text) > max) {
            text = this.font.plainSubstrByWidth(text, max - 4) + "…";
        }
        graphics.drawCenteredString(this.font, Component.literal(text), centerX, y, color);
    }

    private void drawTaxLine(GuiGraphics graphics, int x, int y, String text) {
        if (this.font.width(text) > 128) {
            text = this.font.plainSubstrByWidth(text, 124) + "…";
        }
        graphics.drawString(this.font, text, x, y, 0xC8D2E0, false);
    }

    public void onBankStateUpdate() {
        if (this.sourceButton != null) {
            this.sourceButton.setMessage(label(true));
        }
        if (this.targetButton != null) {
            this.targetButton.setMessage(label(false));
        }
        if (this.lostSelectorButton != null) {
            this.lostSelectorButton.setMessage(lostLabel());
        }
    }

    public void onCardListUpdate() {
        if (this.lostIndex >= lostCards().size()) {
            this.lostIndex = 0;
        }
        if (this.lostSelectorButton != null) {
            this.lostSelectorButton.setMessage(lostLabel());
        }
    }

    @Override
    protected void renderSlot(GuiGraphics graphics, Slot slot) {
        if (!isSlotVisible(slot)) {
            return;
        }
        super.renderSlot(graphics, slot);
    }

    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType type) {
        if (slot != null && !isSlotVisible(slot)) {
            return;
        }
        super.slotClicked(slot, slotId, mouseButton, type);
    }
}
