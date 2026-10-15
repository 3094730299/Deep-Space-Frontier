package com.deepspacedeployer.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 客户端入口的"隔离层"（和 {@link ClientScreenBridge} 一个套路）。
 *
 * <p>服务端代码只通过这里碰客户端界面，而且只在"这包是发给客户端的"分支里调用，
 * 这样服务器永远不会加载到 Minecraft 客户端类。</p>
 */
public final class ClientTransferBridge {

    /**
     * 服务端把卡的最新内容推过来了（资金转化界面两张卡 / 返回商店时插回去的那张卡）。
     *
     * <p>界面已经关了就直接丢掉（卡在服务端，重新打开界面时会重新推一份）。</p>
     */
    public static void acceptCards(List<ItemStack> cards) {
        if (cards == null || cards.isEmpty()) {
            return;
        }
        if (Minecraft.getInstance().screen instanceof TransferScreen screen) {
            screen.acceptCards(cards);
        } else if (Minecraft.getInstance().screen instanceof MachineScreen screen) {
            screen.acceptCard(cards.get(0));
        }
    }

    private ClientTransferBridge() {
    }
}
