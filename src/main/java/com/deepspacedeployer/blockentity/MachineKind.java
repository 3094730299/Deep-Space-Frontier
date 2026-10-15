package com.deepspacedeployer.blockentity;

import net.minecraft.util.StringRepresentable;

/**
 * 功能方块的区别。用同一个方块类 / 方块实体 / 菜单实现，靠这个枚举分流。
 */
public enum MachineKind implements StringRepresentable {

    /** 商店方块：插卡开机，买卖模块与物理结构、兑换票据、选投放点（属于某家公司） */
    TERMINAL("factory_terminal", "工厂终端", "Factory Terminal"),
    /**
     * 工厂装配核心：选蓝图 → 提交材料 → 一键组装物理结构。
     *
     * <p><b>按公司分成 6 种</b>（老板要求：和商店、收购台一样，放下去不能切换）：
     * 组装费就从<b>这台核心所属公司</b>里、你那张卡的主账户余额扣，
     * 所以界面里不再需要"选公司"。</p>
     */
    CORE("factory_core", "载具打印机", "Vehicle Printer"),
    /** 收购方块：投入物品自动换钱，支持机械手自动化（属于某家公司） */
    BUYER("buyer", "收购投送机", "Delivery Buyer"),
    /**
     * 寄售市场：玩家之间买卖东西的地方（老板要求）。
     *
     * <p>可以挂售<b>载具</b>（Sable-Schematics 里的图纸，兼容多物理结构）或<b>物品</b>，
     * 定价两种：<b>以币</b>（账户余额）或<b>以物易物</b>（买方交指定物品 + 数量）。</p>
     *
     * <p><b>无归属</b>（老板要求）：不属于任何一家公司，界面上不显示公司、外观固定。
     * "以币"交易按<b>每张挂单自己的公司</b>结算（挂单时卖家选，默认他自己的公司），
     * 所以中立的市场照样能撮合跨公司的买卖。</p>
     */
    MARKET("market", "寄售市场", "Consignment Market");

    private final String id;
    private final String cnName;
    private final String enName;

    MachineKind(String id, String cnName, String enName) {
        this.id = id;
        this.cnName = cnName;
        this.enName = enName;
    }

    /** 注册名，也是翻译键后缀：block.deepspacedeployer.&lt;id&gt; */
    public String id() {
        return id;
    }

    public String cnName() {
        return cnName;
    }

    public String enName() {
        return enName;
    }

    /**
     * 这个方块是不是"属于某家公司"。
     *
     * <p>商店 / 收购站 / 工厂装配核心都<b>按公司分成 6 种方块</b>（老板要求，放下去就不能换）；
     * <b>寄售市场</b>是中立设施 —— 不分公司、也没有等级，全世界就一种。</p>
     */
    public boolean isCompanyBound() {
        return this != MARKET;
    }

    /** 这个方块有没有"等级"这一说（寄售市场没有） */
    public boolean hasTier() {
        return this != MARKET;
    }

    @Override
    public String getSerializedName() {
        return id;
    }
}
