package com.deepspacedeployer.client;

import com.deepspacedeployer.economy.CardData;
import com.deepspacedeployer.net.BindCardPayload;
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
 */
public class CardBindScreen extends Screen {

    /** 打开界面时用的那只手（服务端会两只手都找一遍，这里只用于显示） */
    private final InteractionHand hand;
    private EditBox nameBox;
    private EditBox labelBox;

    public CardBindScreen(InteractionHand hand) {
        super(Component.translatable("gui.deepspacedeployer.bind_title"));
        this.hand = hand;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;

        ItemStack card = Minecraft.getInstance().player == null
                ? ItemStack.EMPTY
                : Minecraft.getInstance().player.getItemInHand(this.hand);

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

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);

        int cx = this.width / 2;
        graphics.drawCenteredString(this.font, this.title, cx, this.height / 2 - 62, 0xFFFFFF);
        graphics.drawCenteredString(this.font,
                Component.translatable("gui.deepspacedeployer.bind_hint"),
                cx, this.height / 2 - 46, 0xA0A0A0);
        // 副卡额外提示一句：余额独立、只能转账进出
        ItemStack held = Minecraft.getInstance().player == null
                ? ItemStack.EMPTY
                : Minecraft.getInstance().player.getItemInHand(this.hand);
        if (CardData.isSubCard(held)) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("gui.deepspacedeployer.bind_sub_hint"),
                    cx, this.height / 2 - 34, 0xD8A0FF);
        }
        graphics.drawCenteredString(this.font,
                Component.translatable(this.hand == InteractionHand.MAIN_HAND
                        ? "gui.deepspacedeployer.bind_hand_main"
                        : "gui.deepspacedeployer.bind_hand_off"),
                cx, this.height / 2 + 74, 0x707070);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
