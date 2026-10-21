package com.deepspacedeployer.economy;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
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
 * 把银行卡绑定到某个玩家账户 / 改名 / 解绑（服务端逻辑）。
 *
 * <p>老板 2026-10-19 的规则：</p>
 * <ul>
 *   <li><b>一张卡 = 一个账户</b>，没有副卡；余额在 {@link CompanyFunds}；</li>
 *   <li>每人最多 {@link CardRegistry#MAX_CARDS_PER_PLAYER} 张，只有挂失能减；</li>
 *   <li>同一玩家不能有两张同名卡；</li>
 *   <li>卡可以给别人用，但改名 / 改绑 / 解绑只有卡主本人能做。</li>
 * </ul>
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

    public static void handle(Player player, com.deepspacedeployer.net.BindCardPayload payload) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        ItemStack card = payload.hand() == 1
                ? serverPlayer.getItemInHand(InteractionHand.OFF_HAND)
                : serverPlayer.getItemInHand(InteractionHand.MAIN_HAND);
        if (!CardData.isCard(card)) {
            card = findHeldCard(serverPlayer);       // 兼容旧客户端 / 卡被挪走
        }
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

        CardRegistry registry = CardRegistry.get(serverPlayer.server);
        UUID cardId = CardData.ensureCardId(card);
        CardRegistry.CardRecord record = cardId == null ? null : registry.find(cardId);
        if (record != null && record.voided()) {
            serverPlayer.displayClientMessage(Component.translatable("msg.deepspacedeployer.card_voided"), false);
            return;
        }

        String label = payload.label() == null ? "" : payload.label().trim();

        // 1) 纯改名
        if (payload.action() == com.deepspacedeployer.net.BindCardPayload.ACTION_RENAME) {
            rename(serverPlayer, card, registry, cardId, record, label);
            return;
        }

        // 2) 解绑已停用：老板规则“只有挂失能减名额”，不能靠解绑绕过 2 张上限
        if (payload.action() == com.deepspacedeployer.net.BindCardPayload.ACTION_UNBIND) {
            serverPlayer.displayClientMessage(Component.translatable("msg.deepspacedeployer.card_no_unbind"), false);
            return;
        }

        // 3) 绑定（顺便改名）
        String name = payload.targetName() == null ? "" : payload.targetName().trim();
        if (name.isEmpty()) {
            serverPlayer.displayClientMessage(Component.translatable("msg.deepspacedeployer.bind_need_name"), true);
            return;
        }
        Target target = resolveTarget(serverPlayer.server, name);
        if (target == null) {
            serverPlayer.displayClientMessage(Component.translatable("msg.deepspacedeployer.bind_failed", name), false);
            return;
        }

        // 换主：先检查目标名额，再摘旧记录（顺序不能反，否则目标满了旧记录已经没了）
        UUID registryOwner = cardId == null ? null : registry.ownerOf(cardId);
        boolean sameOwner = registryOwner != null && registryOwner.equals(target.uuid());
        if (!sameOwner && !registry.canAdd(target.uuid())) {
            limitMessage(serverPlayer);
            return;
        }
        if (record != null && !sameOwner) {
            registry.remove(cardId);
            record = null;
        }

        String cardName = label.isEmpty()
                ? (record != null ? record.name() : registry.uniqueName(target.uuid(), "银行卡"))
                : label;
        boolean nameUsedByThisCard = record != null && record.name().equals(cardName);
        if (!nameUsedByThisCard && registry.nameTaken(target.uuid(), cardName)) {
            serverPlayer.displayClientMessage(Component.translatable("msg.deepspacedeployer.card_name_taken")
                    .withStyle(ChatFormatting.GOLD), true);
            return;
        }

        CardData.setAccount(card, target.uuid(), target.display());
        CardData.migrateToAccount(card, CompanyFunds.get(serverPlayer.server), target.uuid());
        if (label.isEmpty()) {
            CardData.setCardLabel(card, cardName);
        }

        if (record == null) {
            if (!registry.add(target.uuid(), cardId, target.uuid(), cardName)) {
                limitMessage(serverPlayer);
                return;
            }
        } else if (!record.name().equals(cardName)) {
            registry.rename(cardId, cardName);
        }

        syncHeld(serverPlayer);
        LOGGER.info("[深空铸造体] 银行卡 {} 绑定到 {} ({})", cardId, target.display(), target.uuid());
        serverPlayer.displayClientMessage(Component.translatable("msg.deepspacedeployer.bound",
                target.display()), false);
    }

    private static void rename(ServerPlayer player, ItemStack card, CardRegistry registry,
                               UUID cardId, CardRegistry.CardRecord record, String label) {
        if (record != null) {
            String newName = label.isEmpty() ? registry.uniqueName(player.getUUID(), "银行卡") : label;
            if (!registry.rename(cardId, newName)) {
                player.displayClientMessage(Component.translatable("msg.deepspacedeployer.card_name_taken")
                        .withStyle(ChatFormatting.GOLD), true);
                return;
            }
        }
        CardData.setCardLabel(card, label);
        syncHeld(player);
        player.displayClientMessage(Component.translatable(label.isEmpty()
                ? "msg.deepspacedeployer.card_rename_cleared"
                : "msg.deepspacedeployer.card_renamed", label), false);
    }

    private record Target(UUID uuid, String display) {
    }

    /** 在线玩家名 / UUID / 玩家档案缓存，三选一 */
    private static Target resolveTarget(MinecraftServer server, String name) {
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            return new Target(online.getUUID(), online.getGameProfile().getName());
        }
        try {
            UUID parsed = UUID.fromString(name);
            ServerPlayer byId = server.getPlayerList().getPlayer(parsed);
            return new Target(parsed, byId != null ? byId.getGameProfile().getName() : name);
        } catch (IllegalArgumentException ignored) {
            // 不是 UUID，继续按名字查缓存
        }
        GameProfileCache cache = server.getProfileCache();
        if (cache != null) {
            Optional<GameProfile> profile = cache.get(name);
            if (profile.isPresent()) {
                return new Target(profile.get().getId(), profile.get().getName());
            }
        }
        return null;
    }

    private static void limitMessage(ServerPlayer player) {
        player.displayClientMessage(Component.translatable("msg.deepspacedeployer.card_limit")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.ITALIC), true);
    }

    /** 让客户端看到他手里那张卡的新数据 */
    private static void syncHeld(ServerPlayer player) {
        player.inventoryMenu.broadcastChanges();
        player.containerMenu.broadcastChanges();
    }

    private CardBinding() {
    }
}
