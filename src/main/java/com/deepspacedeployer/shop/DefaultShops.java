package com.deepspacedeployer.shop;

import com.deepspacedeployer.Company;
import com.deepspacedeployer.Tier;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 内置默认价目表（没有数据包时用这套；数据包里写了哪家公司就用数据包的）。
 *
 * <p>物品 id 都是从整合包里的航空学 / Simulated / 机械动力 jar 里查出来的真 ID。</p>
 */
public final class DefaultShops {

    // ---------------- 通用收购表（六家公司都收，价格一样）----------------
    private static final Object[][] COMMON_SELL = {
            // {物品 id, 数量, 价格}
            {"minecraft:iron_ingot", 1, 8},
            {"minecraft:copper_ingot", 1, 5},
            {"minecraft:gold_ingot", 1, 12},
            {"minecraft:redstone", 1, 3},
            {"minecraft:quartz", 1, 6},
            {"minecraft:diamond", 1, 45},
            {"minecraft:netherite_scrap", 1, 200},
            {"create:andesite_alloy", 1, 10},
            {"create:zinc_ingot", 1, 9},
            {"create:brass_ingot", 1, 20},
            {"create:iron_sheet", 1, 7},
            {"create:copper_sheet", 1, 6},
            {"create:brass_sheet", 1, 16},
            {"create:electron_tube", 1, 28},
            {"create:precision_mechanism", 1, 95},
            {"create:sturdy_sheet", 1, 65},
            {"create:powdered_obsidian", 1, 32},
            {"create:rose_quartz", 1, 22},
            {"create:propeller", 1, 48},
            {"create:whisk", 1, 48},
            {"aeronautics:end_stone_powder", 1, 14},
            {"aeronautics:levitite", 1, 160},
            {"simulated:altitude_sensor", 1, 45},
            {"simulated:laser_sensor", 1, 45},
    };

    // ---------------- 各公司的商品（公司卖给玩家）----------------
    private static final Map<Company, Object[][]> BUY = new EnumMap<>(Company.class);

    static {
        BUY.put(Company.THRESHOLD, new Object[][]{
                {"aeronautics:aviators_goggles", 1, 260, Tier.LOW, 0},
                {"simulated:altitude_sensor", 1, 120, Tier.LOW, 0},
                {"simulated:gimbal_sensor", 1, 180, Tier.MID, 0},
                {"simulated:laser_sensor", 1, 200, Tier.MID, 5},
                {"simulated:laser_pointer", 1, 200, Tier.MID, 5},
                {"simulated:analog_transmission", 1, 320, Tier.MID, 10},
                {"create:precision_mechanism", 4, 420, Tier.MID, 0},
                // 蓝图凭证：买下来对工厂核心右键即可选中该结构
                {"deepspacedeployer:blueprint_voucher", 1, 900, Tier.LOW, 0, "test_platform"},
                {"deepspacedeployer:blueprint_voucher", 1, 3200, Tier.MID, 10, "small_airship"},
                {"deepspacedeployer:threshold_license", 1, 2500, Tier.MID, 10},
        });
        BUY.put(Company.WHISPERBELT, new Object[][]{
                {"aeronautics:white_envelope", 2, 140, Tier.LOW, 0},
                {"aeronautics:black_envelope", 2, 140, Tier.LOW, 0},
                {"aeronautics:smart_propeller", 1, 380, Tier.MID, 5},
                {"simulated:white_symmetric_sail", 2, 300, Tier.MID, 5},
                {"simulated:black_portable_engine", 1, 400, Tier.MID, 5},
                {"simulated:directional_linked_receiver", 1, 520, Tier.HIGH, 20},
                {"deepspacedeployer:whisperbelt_license", 1, 2500, Tier.MID, 10},
        });
        BUY.put(Company.BROKENEDGE, new Object[][]{
                {"create:sturdy_sheet", 4, 320, Tier.LOW, 0},
                {"aeronautics:mounted_potato_cannon", 1, 300, Tier.LOW, 0},
                {"create:brass_sheet", 8, 200, Tier.LOW, 0},
                {"simulated:iron_handle", 4, 160, Tier.MID, 0},
                {"simulated:docking_connector", 1, 450, Tier.MID, 10},
                {"aeronautics:adjustable_burner", 1, 700, Tier.HIGH, 25},
                {"deepspacedeployer:brokenedge_license", 1, 2500, Tier.MID, 10},
        });
        BUY.put(Company.SKIMMER, new Object[][]{
                {"aeronautics:wooden_propeller", 1, 90, Tier.LOW, 0},
                {"aeronautics:andesite_propeller", 1, 190, Tier.LOW, 0},
                {"aeronautics:propeller_bearing", 1, 260, Tier.LOW, 0},
                {"simulated:orange_portable_engine", 1, 380, Tier.MID, 5},
                {"simulated:yellow_portable_engine", 1, 380, Tier.MID, 5},
                {"aeronautics:gyroscopic_propeller_bearing", 1, 620, Tier.MID, 15},
                {"aeronautics:steam_vent", 1, 550, Tier.HIGH, 20},
                {"deepspacedeployer:blueprint_voucher", 1, 1100, Tier.LOW, 0, "test_platform"},
                {"deepspacedeployer:blueprint_voucher", 1, 3600, Tier.MID, 15, "small_airship"},
                {"deepspacedeployer:skimmer_license", 1, 2500, Tier.MID, 10},
        });
        BUY.put(Company.BEDROCK, new Object[][]{
                {"create:iron_sheet", 8, 90, Tier.LOW, 0},
                {"create:copper_sheet", 8, 80, Tier.LOW, 0},
                {"simulated:gray_handle", 4, 140, Tier.LOW, 0},
                {"simulated:gray_nameplate", 4, 120, Tier.LOW, 0},
                {"simulated:analog_transmission", 1, 340, Tier.MID, 5},
                {"simulated:directional_gearshift", 1, 380, Tier.MID, 10},
                {"simulated:auger_shaft", 1, 420, Tier.MID, 10},
                {"simulated:auger_cog", 1, 420, Tier.HIGH, 20},
                {"deepspacedeployer:bedrock_license", 1, 2500, Tier.MID, 10},
                {"deepspacedeployer:tier_upgrade_kit", 1, 3000, Tier.LOW, 5},
        });
        BUY.put(Company.ASTRALINK, new Object[][]{
                {"create:electron_tube", 4, 150, Tier.LOW, 0},
                {"aeronautics:levitite", 1, 420, Tier.MID, 0},
                {"aeronautics:levitite_blend_bucket", 1, 300, Tier.MID, 5},
                {"simulated:altitude_sensor", 1, 130, Tier.LOW, 0},
                {"simulated:directional_linked_receiver", 1, 560, Tier.HIGH, 20},
                {"simulated:creative_physics_staff", 1, 5000, Tier.HIGH, 60},
                {"deepspacedeployer:astralink_license", 1, 2500, Tier.MID, 10},
                {"deepspacedeployer:tier_upgrade_kit", 1, 3200, Tier.MID, 10},
        });
    }

    public static ShopData build() {
        Map<Company, List<TradeEntry>> buy = new EnumMap<>(Company.class);
        Map<Company, List<TradeEntry>> sell = new EnumMap<>(Company.class);

        List<TradeEntry> commonSell = new ArrayList<>();
        for (Object[] row : COMMON_SELL) {
            commonSell.add(new TradeEntry((String) row[0], (Integer) row[1], (Integer) row[2], Tier.LOW, 0));
        }

        for (Company company : Company.VALUES) {
            List<TradeEntry> companySell = new ArrayList<>(commonSell);
            // 各公司在通用表基础上各有偏好（暂时用同一套，方便你之后在数据包里分化）
            sell.put(company, companySell);

            List<TradeEntry> companyBuy = new ArrayList<>();
            for (Object[] row : BUY.getOrDefault(company, new Object[0][0])) {
                companyBuy.add(new TradeEntry((String) row[0], (Integer) row[1], (Integer) row[2],
                        (Tier) row[3], (Integer) row[4], row.length > 5 ? (String) row[5] : ""));
            }
            buy.put(company, companyBuy);
        }
        return new ShopData(buy, sell);
    }

    private DefaultShops() {
    }
}
