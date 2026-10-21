package com.deepspacedeployer.client;

import com.deepspacedeployer.net.BankStatePayload;
import net.minecraft.client.Minecraft;

/** 客户端缓存：货币银行两个卡槽 + 自己卡的账户数据。 */
public final class BankStateClient {

    private static BankStatePayload data;

    public static void accept(BankStatePayload payload) {
        data = payload;
        if (Minecraft.getInstance().screen instanceof CurrencyBankScreen bank) {
            bank.onBankStateUpdate();
        }
    }

    public static BankStatePayload get() {
        return data;
    }

    public static void clear() {
        data = null;
    }

    private BankStateClient() {
    }
}
