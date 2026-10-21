package com.deepspacedeployer.client;

import com.deepspacedeployer.net.CardListPayload;
import net.minecraft.client.Minecraft;

import java.util.List;

/** 客户端缓存：银行界面挂失选卡用的玩家卡列表。 */
public final class CardListClient {

    private static List<CardListPayload.CardEntry> cards = List.of();

    public static void accept(CardListPayload payload) {
        cards = payload.cards();
        if (Minecraft.getInstance().screen instanceof CurrencyBankScreen bank) {
            bank.onCardListUpdate();
        }
    }

    public static List<CardListPayload.CardEntry> cards() {
        return cards;
    }

    public static void clear() {
        cards = List.of();
    }

    private CardListClient() {
    }
}
