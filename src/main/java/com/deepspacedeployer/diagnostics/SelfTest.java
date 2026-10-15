package com.deepspacedeployer.diagnostics;

import com.deepspacedeployer.Company;
import com.deepspacedeployer.Config;
import com.deepspacedeployer.Tier;
import com.deepspacedeployer.blockentity.MachineKind;
import com.deepspacedeployer.economy.ReputationData;
import com.deepspacedeployer.factory.BlueprintInfo;
import com.deepspacedeployer.factory.BlueprintLibrary;
import com.deepspacedeployer.integration.IntegrationProbe;
import com.deepspacedeployer.registry.ModBlocks;
import com.deepspacedeployer.registry.ModItems;
import com.deepspacedeployer.shop.ShopData;
import com.deepspacedeployer.shop.TradeEntry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.List;

/**
 * 游戏内自检：{@code /dsd selftest}
 *
 * <p>目的很实际 —— 你只需要启动一次游戏、跑一条指令，就能把大部分"能静态查出来的问题"一次性揪出来：
 * 注册是否齐全、价目表里的物品 id 有没有写错、蓝图文件夹读没读到、联动桥通不通、配置值是多少。</p>
 */
public final class SelfTest {

    public static List<String> run(MinecraftServer server) {
        List<String> lines = new ArrayList<>();
        lines.add("===== 深空铸造体 自检 =====");

        ModList.get().getModContainerById("deepspacedeployer").ifPresentOrElse(
                container -> lines.add("模组版本：" + container.getModInfo().getVersion()),
                () -> lines.add("模组版本：未知（奇怪，但游戏能跑就行）"));

        // 1) 注册
        lines.add("--- 注册 ---");
        int blocks = 0;
        for (MachineKind kind : MachineKind.values()) {
            var key = BuiltInRegistries.BLOCK.getKey(ModBlocks.get(kind));
            lines.add((key == null ? "[X] 方块没注册：" : "[OK] 方块 ") + kind.id() + " -> " + key);
            blocks++;
        }
        lines.add("[OK] 银行卡 -> " + BuiltInRegistries.ITEM.getKey(ModItems.BANK_CARD.get()));
        lines.add("[OK] 蓝图凭证 -> " + BuiltInRegistries.ITEM.getKey(ModItems.BLUEPRINT_VOUCHER.get()));
        lines.add("[OK] 等级升级套件 -> " + BuiltInRegistries.ITEM.getKey(ModItems.TIER_UPGRADE_KIT.get()));
        lines.add("（六种票据已按老板要求删除，钱只存在「公司账户 + 卡上余额」两处）");
        lines.add("共检查 " + blocks + " 个方块、4 类物品");

        // 2) 价目表
        lines.add("--- 价目表 ---");
        ShopData shop = ShopData.get();
        int badEntries = 0;
        for (Company company : Company.VALUES) {
            List<TradeEntry> buy = shop.buyOffers(company);
            List<TradeEntry> sell = shop.sellOffers(company);
            int badBuy = 0;
            int badSell = 0;
            for (TradeEntry entry : buy) {
                if (!entry.isValid()) {
                    badBuy++;
                    if (badEntries++ < 8) {
                        lines.add("[X] " + company.id() + " 出售表里有不存在的物品：" + entry.itemId());
                    }
                } else if (entry.hasBlueprint() && BlueprintLibrary.get(entry.blueprint()) == null) {
                    // 凭证指向的蓝图在这台服务器上不存在
                    badBuy++;
                    if (badEntries++ < 8) {
                        lines.add("[X] " + company.id() + " 卖的蓝图凭证指向不存在的蓝图："
                                + entry.blueprint() + "（凭证买了也用不了）");
                    }
                }
            }
            for (TradeEntry entry : sell) {
                if (!entry.isValid()) {
                    badSell++;
                    if (badEntries++ < 8) {
                        lines.add("[X] " + company.id() + " 收购表里有不存在的物品：" + entry.itemId());
                    }
                }
            }
            lines.add(String.format("[%s] %s：出售 %d 条（坏的 %d）｜收购 %d 条（坏的 %d）",
                    badBuy + badSell == 0 ? "OK" : "!!", company.cnName(), buy.size(), badBuy, sell.size(), badSell));
        }

        // 3) 蓝图
        lines.add("--- 蓝图 ---");
        lines.add("文件夹：" + BlueprintLibrary.folder());
        List<BlueprintInfo> blueprints = BlueprintLibrary.all();
        lines.add("读到 " + blueprints.size() + " 份");
        for (BlueprintInfo info : blueprints) {
            lines.add("  · " + info.name() + "  " + info.sizeText() + "  " + info.totalBlocks() + " 方块  "
                    + info.materials().size() + " 种材料");
        }
        if (blueprints.isEmpty()) {
            lines.add("  （空的：把 .nbt 蓝图放进上面的文件夹，然后 /dsd reloadblueprints）");
        }

        // 4) 配置
        lines.add("--- 配置（config/deepspacedeployer-common.toml）---");
        lines.add("组装费 每方块 " + Config.ASSEMBLY_FEE_PER_BLOCK.get()
                + " ｜ 组装秒数 低/中/高 " + Config.ASSEMBLY_SECONDS_LOW.get() + "/"
                + Config.ASSEMBLY_SECONDS_MID.get() + "/" + Config.ASSEMBLY_SECONDS_HIGH.get());
        lines.add("等级方块上限 低 " + Tier.LOW.maxBlueprintBlocks() + " / 中 " + Tier.MID.maxBlueprintBlocks()
                + " / 高 " + Tier.HIGH.maxBlueprintBlocks()
                + " ｜ 硬上限 " + Config.MAX_BLUEPRINT_BLOCKS.get());
        lines.add("跨公司兑换手续费 " + Config.CROSS_COMPANY_EXCHANGE_FEE.get() + "% ｜ 声望 " +
                Config.REPUTATION_PER_1000_SPENT.get() + " 票据/点");
        lines.add("焊接 最多 " + Config.WELDER_MAX_BLOCKS.get() + " 方块 / 半径 " + Config.WELDER_RADIUS.get()
                + " 格 ｜ 组装即物理化 " + Config.ASSEMBLE_AS_PHYSICS.get());
        lines.add("必须插卡开机 " + Config.REQUIRE_CARD_FOR_MACHINES.get());
        lines.add("创造板条箱无限材料 " + Config.CREATIVE_CRATE_INFINITE.get()
                + "（半径 " + Config.CREATIVE_CRATE_RANGE.get() + " 格）｜ 桥："
                + com.deepspacedeployer.integration.CreativeCrateBridge.describe());
        lines.add("收购站每 tick 结算 " + Config.BUYER_BATCHES_PER_TICK.get() + " 次");

        // 5) 存档数据
        lines.add("--- 存档 ---");
        try {
            ReputationData data = ReputationData.get(server);
            lines.add("[OK] 声望数据可读写（文件 " + ReputationData.FILE_NAME + "）");
            var players = server.getPlayerList().getPlayers();
            if (!players.isEmpty()) {
                var first = players.get(0);
                long total = 0L;
                for (Company company : Company.VALUES) {
                    total += data.spent(first.getUUID(), company);
                }
                lines.add("（" + first.getGameProfile().getName() + " 的累计消费合计：" + total + "）");
            } else {
                lines.add("（暂无在线玩家，跳过声望值抽查）");
            }
        } catch (Throwable t) {
            lines.add("[X] 声望数据异常：" + t);
        }

        // 6) 联动
        lines.add("--- 联动 ---");
        lines.addAll(IntegrationProbe.describe());

        // 7) 世界生成（结构/结构集有没有注册成功）
        lines.add("--- 世界生成 ---");
        try {
            int structures = countStructures(server);
            int sets = countStructureSets(server);
            lines.add(String.format("[%s] 结构已注册 %d 个（期望 18）｜结构集 %d 个（期望 3）",
                    structures == 18 && sets == 3 ? "OK" : "!!", structures, sets));
            if (structures < 18) {
                lines.add("[提示] 结构没注册全：检查 data/deepspacedeployer/worldgen/ 下的 json 有没有被数据包读到");
            }
            lines.add("（结构是否真的刷得出来要看新探索的区块；可以用 /locate structure 试）");
        } catch (Throwable t) {
            lines.add("[X] 世界生成检查异常：" + t);
        }

        lines.add("===== 自检结束，把上面这段整段发出来即可 =====");
        return lines;
    }

    /** 本模组命名空间下注册了多少个结构 */
    private static int countStructures(MinecraftServer server) {
        int count = 0;
        for (var key : server.registryAccess()
                .registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE).keySet()) {
            if (key.getNamespace().equals("deepspacedeployer")) {
                count++;
            }
        }
        return count;
    }

    /** 本模组命名空间下注册了多少个结构集 */
    private static int countStructureSets(MinecraftServer server) {
        int count = 0;
        for (var key : server.registryAccess()
                .registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE_SET).keySet()) {
            if (key.getNamespace().equals("deepspacedeployer")) {
                count++;
            }
        }
        return count;
    }

    private SelfTest() {
    }
}
