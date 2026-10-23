package com.deepspacedeployer.economy;

/**
 * 实体货币面额：1 / 5 / 10 / 20 / 50 / 100。
 *
 * <p>六家公司各有一套，共 36 种实体货币。物品注册名形如
 * {@code deepspacedeployer:threshold_scrip_20}。</p>
 */
public enum CurrencyDenomination {

    ONE(1),
    FIVE(5),
    TEN(10),
    TWENTY(20),
    FIFTY(50),
    HUNDRED(100);

    public static final CurrencyDenomination[] VALUES = values();
    public static final int COUNT = VALUES.length;

    private final int value;

    CurrencyDenomination(int value) {
        this.value = value;
    }

    /** 面额（元） */
    public int value() {
        return value;
    }

    /** 物品注册名后缀，例如 20 */
    public String suffix() {
        return Integer.toString(value);
    }

    public static CurrencyDenomination byIndex(int index) {
        if (index < 0 || index >= COUNT) {
            return ONE;
        }
        return VALUES[index];
    }

    public static CurrencyDenomination byValue(int value) {
        for (CurrencyDenomination denomination : VALUES) {
            if (denomination.value == value) {
                return denomination;
            }
        }
        return ONE;
    }
}
