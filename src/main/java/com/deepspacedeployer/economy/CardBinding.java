package com.deepspacedeployer.economy;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.UUID;

/**
 * 把银行卡绑定到某个玩家账户（服务端逻辑）。
 *
 * <p>接受两种输入：玩家名（在线玩家，或以前进过服务器的玩家），或者 UUID 字符串。</p>
 */
public final class CardBinding {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 从玩家两只手里找银行卡 */
    public static ItemStack findHeldCard(Player player) {
        ItemStack main = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (CardData.isCard(main)) {
            return main;
        }
        ItemStack off = player.getItemInHand(InteractionHand.OFF_HAND);
        if (CardData.isCard(off)) {
            return off;
        }
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (CardData.isCard(stack)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * 处理银行卡操作：绑定 / 改名 / 解绑。
     *
     * <p><b>卡可以给别人用，但改不了归属</b>（老板确认的规则）：
     * 插着别人的卡照样能买东西、收款也照旧进卡主账户（扣款对象永远是卡主，见
     * {@code MachineBlockEntity#activeAccount}）；但<b>改名 / 改绑 / 解绑只有卡主本人能做</b>。
     * 否则拿到卡的人只要一次右键就能把账户接管走 —— 那比"花一次钱"严重得多。</p>
     *
     * @param player 发起操作的玩家（不必是卡主；不是卡主时只能做"用卡"的事）
     */
    public static void handle(Player player, com.deepspacedeployer.net.BindCardPayload payload) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        ItemStack card = findHeldCard(serverPlayer);
        if (card.isEmpty()) {
            serverPlayer.displayClientMessage(Component.translatable("msg.deepspacedeployer.bind_no_card"), false);
            return;
        }

        // 归属校验：已绑定的卡，只有卡主本人能改名/改绑/解绑
        if (CardData.hasAccount(card) && !CardData.isBoundTo(card, serverPlayer)) {
            serverPlayer.displayClientMessage(Component.translatable("msg.deepspacedeployer.card_not_yours",
                    CardData.accountName(card)), true);
            return;
        }

        String label = payload.label() == null ? "" : payload.label().trim();

        // 1) 纯改名
        if (payload.action() == com.deepspacedeployer.net.BindCardPayload.ACTION_RENAME) {
            CardData.setCardLabel(card, label);
            syncHeld(serverPlayer);
            serverPlayer.displayClientMessage(Component.translatable(label.isEmpty()
                    ? "msg.deepspacedeployer.card_rename_cleared"
                    : "msg.deepspacedeployer.card_renamed", label), false);
            return;
        }

        // 2) 解绑
        if (payload.action() == com.deepspacedeployer.net.BindCardPayload.ACTION_UNBIND) {
            CardData.clearAccount(card);
            syncHeld(serverPlayer);
            serverPlayer.displayClientMessage(Component.translatable("msg.deepspacedeployer.unbound"), false);
            return;
        }

        // 3) 绑定（顺便改名）
        String name = payload.targetName() == null ? "" : payload.targetName().trim();
        if (name.isEmpty()) {
            serverPlayer.displayClientMessage(Component.translatable("msg.deepspacedeployer.bind_need_name"), true);
            return;
        }
        if (!label.isEmpty()) {
            CardData.setCardLabel(card, label);
        }

        MinecraftServer server = serverPlayer.server;
        UUID uuid = null;
        String display = name;

        // 在线玩家（按名字）
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online == null) {
            // 名字其实是 UUID 的情况
            try {
                UUID parsed = UUID.fromString(name);
                online = server.getPlayerList().getPlayer(parsed);
                if (online == null) {
                    uuid = parsed;
                    display = name;
                }
            } catch (IllegalArgumentException ignored) {
                // 不是 UUID，继续按名字查缓存
            }
        }
        if (online != null) {
            uuid = online.getUUID();
            display = online.getGameProfile().getName();
        }

        // 不在线：查玩家档案缓存（以前进过服务器就能查到）
        if (uuid == null) {
            GameProfileCache cache = server.getProfileCache();
            if (cache != null) {
                Optional<GameProfile> profile = cache.get(name);
                if (profile.isPresent()) {
                    uuid = profile.get().getId();
                    display = profile.get().getName();
                }
            }
        }

        if (uuid == null) {
            serverPlayer.displayClientMessage(Component.translatable("msg.deepspacedeployer.bind_failed", name), false);
            return;
        }

        CardData.setAccount(card, uuid, display);
        syncHeld(serverPlayer);
        LOGGER.info("[深空铸造体] 银行卡绑定到 {} ({})", display, uuid);
        serverPlayer.displayClientMessage(Component.translatable("msg.deepspacedeployer.bound", display), false);
    }

    /** 让客户端看到他手里那张卡的新数据 */
    private static void syncHeld(ServerPlayer player) {
        player.inventoryMenu.broadcastChanges();
        player.containerMenu.broadcastChanges();
    }

    private CardBinding() {
    }
}
