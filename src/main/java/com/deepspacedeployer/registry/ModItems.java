package com.deepspacedeployer.registry;

import com.deepspacedeployer.Company;
import com.deepspacedeployer.DeepSpaceDeployer;
import com.deepspacedeployer.Tier;
import com.deepspacedeployer.block.MachineBlock;
import com.deepspacedeployer.blockentity.MachineKind;
import com.deepspacedeployer.economy.CurrencyDenomination;
import com.deepspacedeployer.item.BankCardItem;
import com.deepspacedeployer.item.BlueprintVoucherItem;
import com.deepspacedeployer.item.BuyerBlockItem;
import com.deepspacedeployer.item.CompanyLicenseItem;
import com.deepspacedeployer.item.CurrencyItem;
import com.deepspacedeployer.item.TierUpgradeKitItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.core.component.DataComponents;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 物品注册表：银行卡 + 六家公司授权铭牌 + 蓝图凭证 + 升级套件 + 36 种实体货币 + 功能方块的方块物品。
 *
 * <p>旧版"六种票据"已经删掉；2026-10-22 按 P0-3 新增 <b>6 家公司 × 6 种面额 = 36 种实体货币</b>，
 * 堆叠上限 100，可在货币银行存取。钱仍然存在世界存档的 {@code CompanyFunds} 里，
 * 实体券只是账户余额的外壳。</p>
 */
public final class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, DeepSpaceDeployer.MOD_ID);

    /** 唯一一种银行卡：走账户、兑换、绑定账户都靠它；余额在世界存档的账户里 */
    public static final DeferredHolder<Item, BankCardItem> BANK_CARD =
            ITEMS.register("bank_card", () -> new BankCardItem(new Item.Properties().stacksTo(1)));

    /**
     * 旧副卡（0.1.10 之前存档里可能还有）。
     *
     * <p><b>只在注册表里留一个 id 防止老存档掉物品</b>：不在创造物品栏发放，
     * 右键时自动转成 {@link #BANK_CARD}。新系统只有一种卡，没有副卡账户。</p>
     */
    public static final DeferredHolder<Item, BankCardItem> BANK_CARD_SUB =
            ITEMS.register("bank_card_sub", () -> new BankCardItem(new Item.Properties().stacksTo(1), true));

    /** 蓝图凭证：商店买来的"某份物理结构的使用权"，对工厂核心右键即选中 */
    public static final DeferredHolder<Item, BlueprintVoucherItem> BLUEPRINT_VOUCHER =
            ITEMS.register("blueprint_voucher", () -> new BlueprintVoucherItem(new Item.Properties().stacksTo(16)));

    /** 等级升级套件：把机器升一级 */
    public static final DeferredHolder<Item, TierUpgradeKitItem> TIER_UPGRADE_KIT =
            ITEMS.register("tier_upgrade_kit", () -> new TierUpgradeKitItem(new Item.Properties().stacksTo(16)));

    /** 六家公司的授权铭牌：把自建机器改成该公司 */
    public static final List<DeferredHolder<Item, CompanyLicenseItem>> LICENSES;

    static {
        List<DeferredHolder<Item, CompanyLicenseItem>> licenses = new ArrayList<>(Company.COUNT);
        for (Company company : Company.VALUES) {
            licenses.add(ITEMS.register(company.id() + "_license",
                    () -> new CompanyLicenseItem(company, new Item.Properties().stacksTo(16))));
        }
        LICENSES = Collections.unmodifiableList(licenses);
    }

    public static Item licenseItem(Company company) {
        return LICENSES.get(company.ordinal()).get();
    }

    /**
     * 36 种实体货币：6 家公司 × 6 种面额（1/5/10/20/50/100）。
     *
     * <p>注册名 {@code <公司>_scrip_<面额>}，例如 {@code threshold_scrip_20}；
     * 堆叠上限 100。钱仍然存在账户里，实体券只是可以在银行存取的外壳。</p>
     */
    public static final List<DeferredHolder<Item, CurrencyItem>> CURRENCIES;

    static {
        List<DeferredHolder<Item, CurrencyItem>> currencies = new ArrayList<>(
                Company.COUNT * CurrencyDenomination.COUNT);
        for (Company company : Company.VALUES) {
            for (CurrencyDenomination denomination : CurrencyDenomination.VALUES) {
                String name = company.id() + "_scrip_" + denomination.suffix();
                currencies.add(ITEMS.register(name,
                        () -> new CurrencyItem(company, denomination,
                                new Item.Properties().stacksTo(100))));
            }
        }
        CURRENCIES = Collections.unmodifiableList(currencies);
    }

    public static List<DeferredHolder<Item, CurrencyItem>> currencies() {
        return CURRENCIES;
    }

    public static Item currencyItem(Company company, CurrencyDenomination denomination) {
        int index = company.ordinal() * CurrencyDenomination.COUNT + denomination.ordinal();
        return CURRENCIES.get(index).get();
    }

    /** 创造栏/发钱用：指定公司、面额、张数（单叠最多 100）。 */
    public static ItemStack currencyStack(Company company, CurrencyDenomination denomination, int count) {
        int clamped = Math.max(1, Math.min(100, count));
        return new ItemStack(currencyItem(company, denomination), clamped);
    }

    /** 这张物品栈是不是实体货币；不是返回 null。 */
    public static CurrencyItem currencyOf(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof CurrencyItem currency)) {
            return null;
        }
        return currency;
    }

    /** 一张实体券的面额；不是货币返回 0。 */
    public static int currencyValue(ItemStack stack) {
        CurrencyItem currency = currencyOf(stack);
        return currency == null ? 0 : currency.value();
    }

    /**
     * 功能方块的方块物品。
     *
     * <p>规则（老板定的）：<b>每个公司各一件，不能再切换</b> ——</p>
     * <ul>
     *   <li>{@code factory_core / factory_terminal / buyer}：6 家公司各一件，
     *       注册名 {@code <机器>_<公司>}（如 {@code factory_core_threshold}）；</li>
     *   <li>{@code market}：<b>无归属、也没有等级</b>，全世界就一件 {@code market}。</li>
     * </ul>
     *
     * <p>它们共用同一个方块（{@code deepspacedeployer:factory_core} 等），
     * 公司数据写在物品的 {@code BLOCK_STATE} 组件里 —— 放置时由
     * {@code MachineBlock#getStateForPlacement} 读出来，掉落表的 {@code copy_state} 用的是同一套，
     * 所以"挖起来再放下"照样保住公司。</p>
     *
     * <p><b>等级</b>不再作为物品维度（创造栏里一种只有一个），但方块上仍然保留
     * {@code tier} 属性：世界生成的 18 座工厂按 6 公司 × 3 等级放置，
     * 玩家手里的机器则一律从低级起步、用「机械级别升级插件」升上去。</p>
     *
     * <p>⚠️ 注册表项的路径必须是 {@code [a-z0-9_.-]}：公司显示名里可能有空格，
     * 所以一律用 {@link Company#id()}。</p>
     *
     * <p>⚠️ 踩过的坑（详见 docs/设计决策.md §2.38/§2.39）：漏注册方块物品会让启动崩、
     * 漏登记方块实体的合法方块清单会让放置崩 —— 现在 {@code tools/validate.py} 会逐项检查。</p>
     */
    private static final Map<String, DeferredHolder<Item, BlockItem>> MACHINE_VARIANTS = new LinkedHashMap<>();
    /** 创造栏的顺序：市场一件，然后按 机器 × 公司 排 */
    private static final List<DeferredHolder<Item, BlockItem>> MACHINE_VARIANT_ORDER = new ArrayList<>();

    static {
        // 无归属、无等级的方块（寄售市场）：整个模组就一件
        MACHINE_VARIANTS.put(MachineKind.MARKET.id() + "|", ITEMS.register(MachineKind.MARKET.id(),
                () -> new BlockItem(ModBlocks.get(MachineKind.MARKET), new Item.Properties())));
        MACHINE_VARIANT_ORDER.add(MACHINE_VARIANTS.get(MachineKind.MARKET.id() + "|"));

        // 有归属的方块（工厂核心 / 商店）：6 家公司各一件。
        // 收购台不归公司，旧 6 个物品仍注册（隐藏）用于老存档/老背包迁移；
        // 新的 3 个等级物品在下面单独注册。
        for (MachineKind kind : MachineKind.values()) {
            if (kind == MachineKind.MARKET) {
                continue;
            }
            for (Company company : Company.VALUES) {
                String name = kind.id() + "_" + company.id();
                MACHINE_VARIANTS.put(key(kind, company),
                        ITEMS.register(name, () -> new BlockItem(ModBlocks.get(kind), new Item.Properties())));
                if (kind != MachineKind.BUYER) {
                    MACHINE_VARIANT_ORDER.add(MACHINE_VARIANTS.get(key(kind, company)));
                }
            }
        }
    }

    /**
     * 收购投送机的 3 个等级物品：{@code buyer_low / buyer_mid / buyer_high}。
     *
     * <p>老存档里的 6 个 {@code buyer_<公司>} 物品仍注册但不在创造栏发放；
     * 放下去后统一变成中立收购机，等级按老物品的 BLOCK_STATE 保留。</p>
     */
    public static final List<DeferredHolder<Item, BlockItem>> BUYER_ITEMS;

    static {
        List<DeferredHolder<Item, BlockItem>> buyers = new ArrayList<>(Tier.COUNT);
        for (Tier tier : Tier.VALUES) {
            String name = MachineKind.BUYER.id() + "_" + tier.id();
            buyers.add(ITEMS.register(name, () -> new BuyerBlockItem(
                    ModBlocks.get(MachineKind.BUYER), tier, new Item.Properties())));
        }
        BUYER_ITEMS = Collections.unmodifiableList(buyers);
    }

    /** 收购投送机某个等级的物品。 */
    public static Item buyerItem(Tier tier) {
        return BUYER_ITEMS.get(Math.max(0, Math.min(Tier.COUNT - 1, tier.rank()))).get();
    }

    private static String key(MachineKind kind, Company company) {
        return kind.id() + "|" + company.id();
    }

    /**
     * 某个机器 + 公司的方块物品。
     *
     * <p>无归属的机器（{@link MachineKind#isCompanyBound()} 为 false）忽略公司参数，
     * 一律返回那一件中立物品。</p>
     */
    public static Item machineItem(MachineKind kind, Company company) {
        if (kind == MachineKind.BUYER) {
            return buyerItem(Tier.LOW);
        }
        DeferredHolder<Item, BlockItem> holder = kind.isCompanyBound()
                ? MACHINE_VARIANTS.get(key(kind, company))
                : MACHINE_VARIANTS.get(kind.id() + "|");
        return holder == null ? Items.AIR : holder.get();
    }

    /**
     * 某个机器 + 公司的方块物品栈（带好公司数据，创造栏/指令给的就是它）。
     *
     * <p>等级固定用低级：玩家拿到手就是低级厂，靠升级插件往上走；
     * 无归属的方块不写公司属性。</p>
     */
    public static ItemStack machineStack(MachineKind kind, Company company) {
        if (kind == MachineKind.BUYER) {
            return machineStack(kind, Tier.LOW);
        }
        Item item = machineItem(kind, company);
        if (item == Items.AIR) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(item);
        BlockItemStateProperties state = BlockItemStateProperties.EMPTY.with(MachineBlock.TIER, Tier.LOW);
        if (kind.isCompanyBound()) {
            state = state.with(MachineBlock.COMPANY, company);
        }
        stack.set(DataComponents.BLOCK_STATE, state);
        return stack;
    }

    /** 收购投送机的等级物品栈（创造栏/指令用）。 */
    public static ItemStack machineStack(MachineKind kind, Tier tier) {
        if (kind != MachineKind.BUYER) {
            return machineStack(kind, Company.VALUES[0]);
        }
        ItemStack stack = new ItemStack(buyerItem(tier));
        stack.set(DataComponents.BLOCK_STATE,
                BlockItemStateProperties.EMPTY.with(MachineBlock.TIER, tier));
        return stack;
    }

    /** 创造栏用：所有功能方块物品（市场一件 + 每个有归属机器的 6 家公司） */
    public static List<DeferredHolder<Item, BlockItem>> machineVariants() {
        return Collections.unmodifiableList(MACHINE_VARIANT_ORDER);
    }

    /** 通用终端：纯装饰方块（原版模型，无 GeckoLib 依赖） */
    public static final DeferredHolder<Item, BlockItem> UNIVERSAL_TERMINAL =
            ITEMS.register("universal_terminal",
                    () -> new BlockItem(ModBlocks.UNIVERSAL_TERMINAL.get(), new Item.Properties()));

    /** 精构仪：装饰方块（1×1×5 多方块的主体） */
    public static final DeferredHolder<Item, BlockItem> PRECISION_FABRICATOR =
            ITEMS.register("precision_fabricator",
                    () -> new BlockItem(ModBlocks.PRECISION_FABRICATOR.get(), new Item.Properties()));

    /** 深空货币银行：全局银行方块（无公司、无等级） */
    public static final DeferredHolder<Item, BlockItem> CURRENCY_BANK =
            ITEMS.register("currency_bank",
                    () -> new BlockItem(ModBlocks.CURRENCY_BANK.get(), new Item.Properties()));

    /**
     * 精构仪多方块的部件格物品。
     *
     * <p>和 {@link #PRINTER_PART} 一样：<b>不能单独放置</b>
     * （{@code PrecisionFabricatorPartBlock.getStateForPlacement} 返回 null）、
     * 也不在创造模式物品栏里；注册它只是为了让掉落表和注册表完整，
     * 万一被指令取出也不会出现"方块没有对应物品"的怪状态。</p>
     */
    public static final DeferredHolder<Item, BlockItem> PRECISION_FABRICATOR_PART =
            ITEMS.register("precision_fabricator_part",
                    () -> new BlockItem(ModBlocks.PRECISION_FABRICATOR_PART.get(), new Item.Properties()));

    /**
     * 载具打印机多方块结构的部件格物品。
     *
     * <p>它<b>不能单独放置</b>（没有合成表、也不在创造模式物品栏里），
     * 只是为了让"拆掉整台机器时部件格不掉出无法使用的物品"这件事有个对应注册。</p>
     */
    public static final DeferredHolder<Item, BlockItem> PRINTER_PART =
            ITEMS.register("printer_part",
                    () -> new BlockItem(ModBlocks.PRINTER_PART.get(), new Item.Properties()));

    /**
     * 高腐蚀溶液桶。
     *
     * <p>用原版 {@link net.minecraft.world.item.BucketItem}：右键流体方块装、右键地面倒，
     * 空桶留在手里。真正的流体逻辑在 {@code fluid/ModFluids}。</p>
     */
    public static final DeferredHolder<Item, net.minecraft.world.item.BucketItem> CORROSIVE_BUCKET =
            ITEMS.register("corrosive_solution_bucket",
                    () -> new net.minecraft.world.item.BucketItem(
                            com.deepspacedeployer.fluid.ModFluids.CORROSIVE_STILL.get(),
                            new Item.Properties()
                                    .stacksTo(1)
                                    .craftRemainder(net.minecraft.world.item.Items.BUCKET)));

    /** 是不是银行卡（唯一卡 / 老存档里的旧副卡都算） */
    public static boolean isBankCard(ItemStack stack) {
        return !stack.isEmpty() && (stack.is(BANK_CARD.get()) || stack.is(BANK_CARD_SUB.get()));
    }

    /** @deprecated 旧副卡只剩迁移用途；新代码不要再区分主卡/副卡 */
    @Deprecated
    public static boolean isSubCard(ItemStack stack) {
        return !stack.isEmpty() && stack.is(BANK_CARD_SUB.get());
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }

    private ModItems() {
    }
}
