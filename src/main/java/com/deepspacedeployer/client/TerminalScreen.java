package com.deepspacedeployer.client;

import com.deepspacedeployer.Company;
import com.deepspacedeployer.blockentity.UniversalTerminalBlockEntity;
import com.deepspacedeployer.menu.TerminalMenu;
import com.deepspacedeployer.shop.TradeEntry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 通用终端的界面：左边 6 个卡槽（= 6 家公司），右边是该公司的商品列表。
 *
 * <p>插卡 → 公司自动切换（终端会扫描卡上的公司标记）→ 右边列表跟着换。
 * 按钮走 {@code clickMenuButton}：购买 = 100 + 列表下标，翻页 = 200/201。</p>
 */
public class TerminalScreen extends AbstractContainerScreen<TerminalMenu> {

    private static final int W = 268;
    private static final int H = 166;
    /** 商品列表起点 */
    private static final int LIST_X = 100;
    private static final int LIST_Y = 20;
    private static final int ROW_H = 18;

    private Button prevButton;
    private Button nextButton;

    public TerminalScreen(TerminalMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = W;
        this.imageHeight = H;
        this.inventoryLabelY = H - 94;
    }

    @Override
    protected void init() {
        super.init();
        clearWidgets();
        prevButton = addRenderableWidget(Button.builder(Component.literal("<"), b -> {
            if (minecraft != null && minecraft.gameMode != null) {
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, TerminalMenu.ACTION_PAGE_PREV);
            }
        }).bounds(leftPos + LIST_X, topPos + LIST_Y + TerminalMenu.SLOT_COUNT * 0 - 16, 16, 14).build());
        nextButton = addRenderableWidget(Button.builder(Component.literal(">"), b -> {
            if (minecraft != null && minecraft.gameMode != null) {
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, TerminalMenu.ACTION_PAGE_NEXT);
            }
        }).bounds(leftPos + LIST_X + 20, topPos + LIST_Y - 16, 16, 14).build());
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xE0202020);
        graphics.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + imageHeight - 1, 0xFF3A3A44);
        // 卡槽区
        graphics.fill(leftPos + 20, topPos + 14, leftPos + 88, topPos + 62, 0xFF2A2A32);
        // 商品区
        graphics.fill(leftPos + LIST_X - 4, topPos + 14, leftPos + imageWidth - 6, topPos + H - 30, 0xFF2A2A32);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);

        UniversalTerminalBlockEntity terminal = menu.terminal();
        Company company = terminal.company();

        // 标题
        Component title = terminal.hasCompany()
                ? Component.translatable("gui.deepspacedeployer.terminal_titled", company.displayName())
                : Component.translatable("gui.deepspacedeployer.terminal_hint_insert_card");
        graphics.drawString(font, title, leftPos + 8, topPos + 6,
                terminal.hasCompany() ? company.themeColor() : 0xFFAAAAAA, false);

        // 卡槽标签
        for (int i = 0; i < TerminalMenu.SLOT_COUNT; i++) {
            ItemStack card = menu.terminal().getItem(i);
            Company slotCompany = UniversalTerminalBlockEntity.companyOf(card);
            if (slotCompany != null) {
                int x = leftPos + 26 + (i % 3) * 22;
                int y = topPos + 20 + (i / 3) * 22;
                graphics.fill(x - 1, y - 1, x + 17, y + 17, 0x40000000 | (slotCompany.themeColor() & 0xFFFFFF));
            }
        }

        // 商品列表
        if (terminal.hasCompany()) {
            List<TradeEntry> offers = terminal.currentPageOffers();
            for (int i = 0; i < offers.size(); i++) {
                TradeEntry entry = offers.get(i);
                int y = topPos + LIST_Y + i * ROW_H;
                ItemStack stack = entry.createStack();
                graphics.renderItem(stack, leftPos + LIST_X, y);
                graphics.drawString(font, stack.getHoverName().getString().substring(0,
                                Math.min(14, stack.getHoverName().getString().length())),
                        leftPos + LIST_X + 20, y + 4, 0xFFFFFFFF, false);
                graphics.drawString(font, entry.price() + " 币", leftPos + LIST_X + 120, y + 4, 0xFFFFD166, false);
            }
            if (offers.isEmpty()) {
                graphics.drawString(font, Component.translatable("gui.deepspacedeployer.terminal_no_offers"),
                        leftPos + LIST_X, topPos + LIST_Y + 4, 0xFF888888, false);
            }
            // 翻页
            if (prevButton != null) {
                prevButton.active = terminal.page() > 0;
            }
            if (nextButton != null) {
                nextButton.active = (terminal.page() + 1) * UniversalTerminalBlockEntity.PAGE_SIZE < terminal.offers().size();
            }
        }

        renderTooltip(graphics, mouseX, mouseY);
    }

    /** 点商品行 = 买 */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && menu.terminal().hasCompany()) {
            int relX = (int) mouseX - leftPos - LIST_X;
            int relY = (int) mouseY - topPos - LIST_Y;
            if (relX >= 0 && relX < 150 && relY >= 0) {
                int row = relY / ROW_H;
                if (row >= 0 && row < menu.terminal().currentPageOffers().size()
                        && minecraft != null && minecraft.gameMode != null) {
                    minecraft.gameMode.handleInventoryButtonClick(menu.containerId,
                            TerminalMenu.ACTION_BUY_BASE + row);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
