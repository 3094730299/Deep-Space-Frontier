package com.deepspacedeployer.client;

import net.minecraft.world.InteractionHand;

/**
 * 客户端入口的"隔离层"。
 *
 * <p>服务端代码只调用这个类的方法（而且只在 {@code level.isClientSide()} 分支里调用），
 * 这样服务器永远不会加载到 Minecraft 客户端类。</p>
 */
public final class ClientScreenBridge {

    public static void openCardBindScreen(InteractionHand hand) {
        CardWalletClient.clear();
        net.minecraft.client.Minecraft.getInstance().setScreen(new CardBindScreen(hand));
    }

    private ClientScreenBridge() {
    }
}
