package com.deepspacedeployer.client;

import com.deepspacedeployer.net.CardWalletPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;

/** 客户端缓存：卡界面打开时服务端发来的 7 个账户数据。 */
public final class CardWalletClient {

    private static CardWalletPayload data;

    public static void accept(CardWalletPayload payload) {
        data = payload;
        if (Minecraft.getInstance().screen instanceof CardBindScreen screen) {
            screen.onWalletUpdate();
        }
    }

    public static void clear() {
        data = null;
    }

    public static CardWalletPayload get(InteractionHand hand) {
        return data != null && data.hand() == hand.ordinal() ? data : null;
    }

    private CardWalletClient() {
    }
}
