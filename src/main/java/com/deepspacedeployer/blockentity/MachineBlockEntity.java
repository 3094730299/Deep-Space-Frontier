package com.deepspacedeployer.blockentity;

import com.deepspacedeployer.Company;
import com.deepspacedeployer.Config;
import com.deepspacedeployer.DeepSpaceDeployer;
import com.deepspacedeployer.Tier;
import com.deepspacedeployer.block.MachineBlock;
import com.deepspacedeployer.economy.CardData;
import com.deepspacedeployer.economy.CompanyFunds;
import com.deepspacedeployer.economy.PaymentService;
import com.deepspacedeployer.economy.ReputationData;
import com.deepspacedeployer.economy.WalletService;
import com.deepspacedeployer.menu.MachineMenu;
import com.deepspacedeployer.menu.TransferMenu;
import com.deepspacedeployer.net.BlueprintThumbnailsPayload;
import com.deepspacedeployer.net.MarketListPayload;
import com.deepspacedeployer.net.ShopDataPayload;
import com.deepspacedeployer.net.TransferSyncPayload;
import com.deepspacedeployer.registry.ModBlockEntities;
import com.deepspacedeployer.registry.ModItems;
import com.deepspacedeployer.registry.ModMenus;
import com.deepspacedeployer.shop.ShopData;
import com.deepspacedeployer.shop.TradeEntry;
import com.deepspacedeployer.factory.BlueprintInfo;
import com.deepspacedeployer.factory.ModulePresetLibrary;
import com.deepspacedeployer.factory.PhysicsBlueprintLibrary;
import com.deepspacedeployer.net.ModuleListPayload;
import net.minecraft.world.phys.Vec3;
import com.deepspacedeployer.factory.BlueprintLibrary;
import com.deepspacedeployer.factory.ParsedBlueprint;
import com.deepspacedeployer.integration.CreateClipboardBridge;
import com.deepspacedeployer.integration.CreativeCrateBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 四个功能方块共用的方块实体。
 *
 * <p><b>钱存在方块里、按玩家分账</b>；银行卡决定"当前账户"；购买先扣方块内余额再扣卡上余额。</p>
 *
 * <p><b>收购站</b>额外有 9 个输入格：物品放进去（或由漏斗 / 机械手塞进去）会按本公司收购价
 * 自动换成票据记到当前账户上。</p>
 */
public class MachineBlockEntity extends BlockEntity implements WorldlyContainer,
        software.bernie.geckolib.animatable.GeoBlockEntity {

    public static final int SLOT_CARD = 0;
    public static final int SLOT_INPUT_START = 1;
    public static final int INPUT_SLOTS = 9;
    /**
     * 工厂核心的"剪贴板格"：放一块机械动力剪贴板进去，缺的材料清单会被写进去（导出给别人看）。
     *
     * <p><b>放在最后</b>（不是插在滑槽中间），这样 {@code SLOT_INPUT_START..+9} 这一段
     * 在所有机器上含义不变，老的存档 / 老的格子编号都不会错位。</p>
     */
    public static final int SLOT_CLIPBOARD = SLOT_INPUT_START + INPUT_SLOTS;
    /** 寄售市场的三个格子：待售 / 以物易物的需求 / 取货 */
    public static final int SLOT_MARKET_SELL = SLOT_CLIPBOARD + 1;
    public static final int SLOT_MARKET_BARTER = SLOT_CLIPBOARD + 2;
    public static final int SLOT_MARKET_COLLECT = SLOT_CLIPBOARD + 3;
    public static final int SLOT_COUNT = SLOT_MARKET_COLLECT + 1;

    private static final String NBT_ITEMS = "Items";
    private static final String NBT_COMPANY = "Company";
    private static final String NBT_BUYER_COMPANY = "BuyerCompany";
    private static final String NBT_AUTO_SELL = "AutoSell";
    private static final String NBT_TIER = "Tier";
    private static final String NBT_BLUEPRINT = "Blueprint";
    private static final String NBT_MATERIALS = "Materials";
    private static final String NBT_RESERVED = "Reserved";
    private static final String NBT_ASSEMBLY_TICKS = "AssemblyTicks";
    private static final String NBT_ASSEMBLY_TOTAL = "AssemblyTotal";
    private static final String NBT_TARGET = "Target";
    private static final String NBT_BLUEPRINT_LIST = "BlueprintList";
    private static final String NBT_LAST_ASSEMBLY = "LastAssembly";
    private static final String NBT_TARGET_OVERRIDE = "TargetOverride";

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);

    /** 余额不存在方块里：改为世界级存档 CompanyFunds（每个玩家 × 每家公司一个余额，全图通用） */
    /** 收购站：true = 自动收购（东西放进去就卖），false = 点击收购（按按钮才卖） */
    private boolean autoSell = true;

    /** 工厂终端商店：每件商品最多 3 个库存；按条目签名存，价目表改了自动补满。 */
    public static final int SHOP_STOCK_MAX = 3;
    private final Map<String, Integer> shopStock = new HashMap<>();
    private int shopRestockTicks;

    /** 收购限流：全服每 tick 已处理的物品数（静态，所有收购机共享） */
    private static int buyerGlobalTick = -1;
    private static int buyerGlobalUsed;
    /** 收购限流：本机当前 tick 已处理的物品数 */
    private int buyerMachineTick = -1;
    private int buyerMachineUsed;
    /** 收购节奏：按等级累计的“组”额度；每处理一组扣 1。 */
    private double buyerBatchCredit;
    private int buyerCreditTick = -1;

    /** 属于哪家公司（商店/核心用方块状态；收购机用运行时选择） */
    private int companyIndex;

    /** 收购投送机：界面里选择按哪家公司结算（不改变方块归属）。null = 还没从老存档迁移。 */
    private Company buyerCompany;

    /** 机器种类缓存（见 {@link #kind()}） */
    private MachineKind cachedKind = MachineKind.TERMINAL;

    /** 工厂等级：0 低级 / 1 中级 / 2 高级（暂时默认高级，方便测试；之后由世界生成的建筑决定） */
    private int tierIndex = Tier.HIGH.rank();

    private final IItemHandler itemHandler = new MachineItemHandler();

    /** 工厂最近一次组装出来的结构范围（"这块地刚造过"的判断依据） */
    private int lastOriginX;
    private int lastOriginY;
    private int lastOriginZ;
    private int lastSizeX;
    private int lastSizeY;
    private int lastSizeZ;
    private boolean hasLastAssembly;

    /** 玩家在地图上选的投放点（选了就用它，否则默认落在机器正面） */
    private boolean hasTargetOverride;
    private int overrideX;
    private int overrideZ;

    /** 创造板条箱探测结果缓存（扫 11³ 个方块太贵，不能每 tick 都扫） */
    private boolean creativeCached;
    private long creativeCheckedAt = -100L;

    /** 已经发给附近玩家的"图纸缩略图代"（换图纸后会自动重发） */
    private int sentThumbnailGeneration = -1;

    /** 上一次写进剪贴板的缺料清单指纹（没变就不重复写） */
    private String clipboardSignature = "";

    /** 市场：本地挂单版本号（自己改了就 +1，仅供界面判断"要不要重建"） */
    private int marketVersion;

    // ---------------- 工厂（蓝图组装）状态 ----------------
    private String selectedBlueprint = "";
    private final Map<String, Integer> materials = new LinkedHashMap<>();
    private final Map<String, Integer> reserved = new LinkedHashMap<>();
    private int assemblyTicks;
    private int assemblyTotal;
    private int targetX;
    private int targetY;
    private int targetZ;
    private boolean hasTarget;
    /** 客户端显示用的蓝图列表（服务端每次同步时带上） */
    private List<BlueprintInfo> blueprintSnapshot = List.of();

    public MachineBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MACHINE.get(), pos, state);
    }

    // ---------------- 基本信息 ----------------

    /**
     * 机器种类：在方块实体进入世界/被加载时算一次并缓存。
     *
     * <p>为什么不每次都读方块状态：{@code getBlockState()} 需要 {@code level}，
     * 而方块实体在极早期（还没挂到世界上）被调用时会空指针。缓存下来最稳。</p>
     */
    public MachineKind kind() {
        return cachedKind;
    }

    /**
     * 打开这台机器的界面（供方块与多方块的<b>部件格</b>共用）。
     *
     * <p>放在这里而不是方块类里，是因为载具打印机是 3×2×2 的多方块结构 ——
     * 点 12 格里任何一格都应该打开同一个界面，共用一份实现才不会走偏。</p>
     */
    public void openMenuFor(net.minecraft.server.level.ServerPlayer serverPlayer) {
        if (level == null || level.isClientSide) {
            return;
        }
        MachineKind k = kind();
        serverPlayer.openMenu(new net.minecraft.world.SimpleMenuProvider(
                (windowId, inventory, p) -> new com.deepspacedeployer.menu.MachineMenu(
                        com.deepspacedeployer.registry.ModMenus.get(k), windowId, inventory, this),
                net.minecraft.network.chat.Component.translatable(
                        "block.deepspacedeployer." + k.id())));
        // 工厂核心：先把最新蓝图列表推给客户端
        if (k == MachineKind.CORE) {
            syncToClient();
        }
        // 寄售市场：挂单列表也直接推一份
        if (k == MachineKind.MARKET) {
            pushMarketNow(serverPlayer);
        }
        // 价目表
        sendShopData(serverPlayer);
        if (k == MachineKind.MODULE_PRINTER) {
            sendModuleList(serverPlayer);
        }
    }

    /** 模块打印机：把当前预设列表发给客户端（蓝图本体留在服务端）。 */
    private void sendModuleList(ServerPlayer player) {
        List<ModuleListPayload.ModuleEntry> entries = new ArrayList<>();
        for (ModulePresetLibrary.Preset preset : ModulePresetLibrary.all()) {
            entries.add(new ModuleListPayload.ModuleEntry(
                    preset.name(), preset.display(), preset.price(), preset.tier().rank(),
                    preset.company(), preset.entry().sizeX(), preset.entry().sizeY(),
                    preset.entry().sizeZ(), preset.blocks()));
        }
        PacketDistributor.sendToPlayer(player, new ModuleListPayload(List.copyOf(entries)));
    }

    /** 把当前结算公司的收购/出售价目表推给客户端（收购机切换公司也要重推）。 */
    private void sendShopData(ServerPlayer player) {
        com.deepspacedeployer.shop.ShopData shop = com.deepspacedeployer.shop.ShopData.get();
        List<TradeEntry> offers = shop.buyOffers(company());
        ensureShopStock(offers);
        PacketDistributor.sendToPlayer(player, new ShopDataPayload(
                company().id(), tier().rank(),
                offers, shop.sellOffers(company()), shopStockFor(offers)));
    }

    /** 把当前库存推给正在追踪这个方块的客户端（买完 / 补货后刷新界面）。 */
    private void broadcastShopData() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        com.deepspacedeployer.shop.ShopData shop = com.deepspacedeployer.shop.ShopData.get();
        List<TradeEntry> offers = shop.buyOffers(company());
        ensureShopStock(offers);
        PacketDistributor.sendToPlayersTrackingChunk(serverLevel,
                new net.minecraft.world.level.ChunkPos(worldPosition),
                new ShopDataPayload(company().id(), tier().rank(),
                        offers, shop.sellOffers(company()), shopStockFor(offers)));
    }

    /** 老存档/新价目表：缺失的条目默认补满 3 个。 */
    private void ensureShopStock(List<TradeEntry> offers) {
        for (TradeEntry entry : offers) {
            shopStock.putIfAbsent(shopStockKey(entry), SHOP_STOCK_MAX);
        }
    }

    private List<Integer> shopStockFor(List<TradeEntry> offers) {
        List<Integer> stocks = new ArrayList<>(offers.size());
        for (TradeEntry entry : offers) {
            stocks.add(Math.max(0, shopStock.getOrDefault(shopStockKey(entry), SHOP_STOCK_MAX)));
        }
        return List.copyOf(stocks);
    }

    private static String shopStockKey(TradeEntry entry) {
        return entry.itemId() + "|" + entry.count() + "|" + entry.price() + "|"
                + entry.tier().rank() + "|" + entry.reputation() + "|" + entry.blueprint();
    }

    private int shopStockOf(TradeEntry entry) {
        ensureShopStock(List.of(entry));
        return Math.max(0, shopStock.getOrDefault(shopStockKey(entry), SHOP_STOCK_MAX));
    }

    private void takeShopStock(TradeEntry entry) {
        String key = shopStockKey(entry);
        int stock = Math.max(0, shopStock.getOrDefault(key, SHOP_STOCK_MAX));
        shopStock.put(key, Math.max(0, stock - 1));
        setChanged();
    }

    /** 工厂终端每 tick 走一次；到间隔就把当前价目表补满。 */
    public void tickShopRestock() {
        if (kind() != MachineKind.TERMINAL) {
            return;
        }
        if (shopRestockTicks > 0) {
            shopRestockTicks--;
            return;
        }
        List<TradeEntry> offers = ShopData.get().buyOffers(company());
        ensureShopStock(offers);
        boolean changed = false;
        for (TradeEntry entry : offers) {
            String key = shopStockKey(entry);
            int stock = shopStock.getOrDefault(key, SHOP_STOCK_MAX);
            if (stock < SHOP_STOCK_MAX) {
                shopStock.put(key, SHOP_STOCK_MAX);
                changed = true;
            }
        }
        shopRestockTicks = tier().shopRestockTicks();
        if (changed) {
            setChanged();
            broadcastShopData();
        }
    }

    private CompoundTag writeShopStock() {
        CompoundTag tag = new CompoundTag();
        for (Map.Entry<String, Integer> entry : shopStock.entrySet()) {
            tag.putInt(entry.getKey(), Math.max(0, entry.getValue()));
        }
        return tag;
    }

    private void readShopStock(CompoundTag tag) {
        shopStock.clear();
        for (String key : tag.getAllKeys()) {
            shopStock.put(key, Math.max(0, tag.getInt(key)));
        }
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        this.cachedKind = computeKind();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        this.cachedKind = computeKind();
    }

    private MachineKind computeKind() {
        if (level == null) {
            return MachineKind.TERMINAL;
        }
        Block block = getBlockState().getBlock();
        return block instanceof MachineBlock machine ? machine.kind() : MachineKind.TERMINAL;
    }

    /**
     * 这台机器属于哪家公司。
     *
     * <p>工厂装配核心是<b>无归属</b>的：它永远返回六家里的第一家，但界面上不显示公司、
     * 也不参与"同公司资金通用"那套（组装费走你插的那张卡所属的公司账户，
     * 见 {@code tryPay} → {@code CardData}）。</p>
     */
    public Company company() {
        // 收购投送机不归公司：界面里选的结算公司存在方块实体里。
        // 老存档没有 BuyerCompany 时，从旧方块状态的 company 属性迁移（新方块默认第一家）。
        if (kind() == MachineKind.BUYER) {
            if (buyerCompany == null) {
                BlockState state = getBlockState();
                buyerCompany = state.hasProperty(MachineBlock.COMPANY)
                        ? state.getValue(MachineBlock.COMPANY)
                        : Company.VALUES[0];
                companyIndex = buyerCompany.ordinal();
            }
            return buyerCompany;
        }
        if (!kind().isCompanyBound()) {
            return Company.VALUES[0];
        }
        BlockState state = getBlockState();
        if (state.hasProperty(MachineBlock.COMPANY)) {
            return state.getValue(MachineBlock.COMPANY);
        }
        return Company.byIndex(companyIndex);
    }

    /** 收购投送机：切换按哪家公司结算；不动方块状态，不算“公司归属”。 */
    public void setBuyerCompany(Company company) {
        this.buyerCompany = company;
        this.companyIndex = company.ordinal();
        afterChange();
    }

    public void setCompany(Company company) {
        if (kind() == MachineKind.BUYER) {
            setBuyerCompany(company);
            return;
        }
        this.companyIndex = company.ordinal();
        updateStateProperty(MachineBlock.COMPANY, company);
        afterChange();
    }

    public Tier tier() {
        BlockState state = getBlockState();
        if (state.hasProperty(MachineBlock.TIER)) {
            return state.getValue(MachineBlock.TIER);
        }
        return Tier.byIndex(tierIndex);
    }

    public void setTier(Tier tier) {
        this.tierIndex = tier.rank();
        updateStateProperty(MachineBlock.TIER, tier);
        afterChange();
    }

    /** 把公司/等级写回方块状态（世界生成的建筑本来就是靠方块状态决定的） */
    private <T extends Enum<T> & net.minecraft.util.StringRepresentable> void updateStateProperty(
            net.minecraft.world.level.block.state.properties.EnumProperty<T> property, T value) {
        if (level == null || level.isClientSide) {
            return;
        }
        BlockState state = getBlockState();
        if (state.hasProperty(property) && !state.getValue(property).equals(value)) {
            level.setBlock(worldPosition, state.setValue(property, value), Block.UPDATE_ALL);
        }
    }

    public boolean isOnline() {
        return !Config.REQUIRE_CARD_FOR_MACHINES.get() || !items.get(SLOT_CARD).isEmpty();
    }

    /** blockedAbove() 的结果缓存（一路扫到世界顶部太贵，不能每 tick 都扫） */
    private boolean blockedAboveCache;
    /** 上次扫描的时刻（游戏 tick）—— 初值给 -1000，保证第一次一定重算 */
    private long blockedAboveCheckedAt = -1000L;
    /** 扫描结果的缓存时长（tick）：10 tick = 0.5 秒 */
    private static final int BLOCKED_ABOVE_CACHE_TICKS = 10;

    /**
     * 正上方有没有方块挡住 —— <b>收购投送机专用</b>。
     *
     * <p>老板要求（2026-10-15，三次修改后定稿）：</p>
     * <ol>
     *   <li>顶上被挡住 → <b>不工作</b>（不自动收购、也不响应「立即收购」），界面提示
     *       "上方被阻挡无法工作"；</li>
     *   <li>火把、告示牌、雪片之类的<b>不算挡</b>（只看"占满整格"的方块）；</li>
     *   <li>★ <b>不管距离多远</b>：从机器正上方<b>一直扫到世界顶部</b>，
     *       中间任何高度只要有方块就算被挡（不再只看紧挨着的那一格）。</li>
     * </ol>
     *
     * <p><b>为什么加缓存</b>：这一扫最坏要过几百格（到世界顶部），
     * 而收购循环与界面数据槽都是<b>每 tick</b> 取一次，机器一多会很浪费。
     * 所以结果缓存 {@value #BLOCKED_ABOVE_CACHE_TICKS} tick（0.5 秒）——
     * 玩家盖顶/拆顶后最多半秒就反应过来，肉眼无感。</p>
     */
    public boolean blockedAbove() {
        if (level == null || kind() != MachineKind.BUYER) {
            return false;
        }
        long now = level.getGameTime();
        if (now - blockedAboveCheckedAt < BLOCKED_ABOVE_CACHE_TICKS) {
            return blockedAboveCache;               // 0.5 秒内直接复用上次结果
        }
        blockedAboveCheckedAt = now;
        blockedAboveCache = scanBlockedAbove();
        return blockedAboveCache;
    }

    /** 从机器正上方一路扫到世界顶部，中途遇到"占满整格"的方块就返回 true */
    private boolean scanBlockedAbove() {
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        int top = level.getMaxBuildHeight();
        for (int y = worldPosition.getY() + 1; y < top; y++) {
            probe.set(worldPosition.getX(), y, worldPosition.getZ());
            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(probe);
            if (state.isAir()) {
                continue;                           // 绝大多数格子是空气，先短路掉
            }
            if (isFullBlockAt(probe)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 这一格是不是被"<b>占满整格</b>"的方块占着。
     *
     * <p>⚠️ 不能只看"碰撞箱非空" —— <b>火把本身也有碰撞箱</b>（很小的一个盒子），
     * 那样写等于没改。真正的判据是碰撞箱<b>六个面都贴到格子边界</b>：
     * 火把/告示牌/雪片/半砖/楼梯/栅栏 都只占一部分 → 不算挡；
     * 石头/木板/玻璃/树叶 占满整格 → 算挡。</p>
     */
    private boolean isFullBlockAt(BlockPos pos) {
        net.minecraft.world.phys.shapes.VoxelShape shape =
                level.getBlockState(pos).getCollisionShape(
                        level, pos, net.minecraft.world.phys.shapes.CollisionContext.empty());
        if (shape.isEmpty()) {
            return false;                           // 空气、水、纯装饰（无碰撞）
        }
        return shape.min(Direction.Axis.X) <= 0.0D && shape.max(Direction.Axis.X) >= 1.0D
                && shape.min(Direction.Axis.Y) <= 0.0D && shape.max(Direction.Axis.Y) >= 1.0D
                && shape.min(Direction.Axis.Z) <= 0.0D && shape.max(Direction.Axis.Z) >= 1.0D;
    }

    // ================= 收购投送机「为什么不动」诊断 =================

    /**
     * 收购循环有 5 道关卡，<b>每一道失败原先都是静默 {@code return false}</b> ——
     * 界面和日志都不吭声，玩家只能干瞪眼。
     *
     * <p>2026-10-17 老板反馈"新存档里无论如何都不工作"，就是这个原因：
     * 分不清是没插卡、卡没绑定、开关关了、头顶被挡，还是投入的东西不在这家公司的收购清单里。</p>
     *
     * <p>这些码会同步到界面（按原因显示一行红字）并写进日志（变化时才写，不刷屏）。</p>
     */
    public static final int IDLE_OK = 0;
    public static final int IDLE_NO_CARD = 1;
    public static final int IDLE_CARD_UNBOUND = 2;
    public static final int IDLE_AUTOSELL_OFF = 3;
    public static final int IDLE_BLOCKED_ABOVE = 4;
    public static final int IDLE_NOTHING_TO_SELL = 5;

    /** 给日志用的人话（下标 = 上面的码） */
    private static final String[] IDLE_REASON_NAMES = {
            "正常（有东西可收）",
            "没插银行卡",
            "银行卡还没绑定账户",
            "自动收购已关闭",
            "头顶被整格方块挡住",
            "输入格里没有这家公司收的东西",
    };

    /** 上一次记日志的原因（-1 = 还没记过） */
    private int lastLoggedIdleReason = -1;

    /** 原因码 → 日志/界面用的人话 */
    public static String idleReasonName(int reason) {
        return reason >= 0 && reason < IDLE_REASON_NAMES.length
                ? IDLE_REASON_NAMES[reason] : "未知(" + reason + ")";
    }

    /**
     * 收购投送机当前"为什么不工作"。{@link #IDLE_OK} = 没问题（有东西可收）。
     *
     * <p>顺序与 {@link #processBuyerCycle()} 里的判断<b>完全一致</b> ——
     * 这样界面报的原因就是它真正卡住的那一道，不会指错。</p>
     */
    public int buyerIdleReason() {
        if (kind() != MachineKind.BUYER) {
            return IDLE_OK;
        }
        if (!isOnline()) {
            return IDLE_NO_CARD;
        }
        if (activeAccount() == null) {
            return IDLE_CARD_UNBOUND;
        }
        if (!autoSell) {
            return IDLE_AUTOSELL_OFF;
        }
        if (blockedAbove()) {
            return IDLE_BLOCKED_ABOVE;
        }
        ShopData shop = ShopData.get();
        Company company = company();
        for (int slot = SLOT_INPUT_START; slot < SLOT_COUNT; slot++) {
            ItemStack stack = items.get(slot);
            if (!stack.isEmpty() && shop.findSellEntry(company, stack) != null) {
                return IDLE_OK;                     // 有东西可收 → 正常运行
            }
        }
        return IDLE_NOTHING_TO_SELL;
    }

    /**
     * 原因变化时写一条日志（服务端，节流为"只在变化时写"）。
     *
     * <p>老板说"不动"的时候，让他把日志里 {@code [收购诊断]} 那一行发过来就能立刻定位。</p>
     */
    public void logIdleReason() {
        if (level == null || level.isClientSide || kind() != MachineKind.BUYER) {
            return;
        }
        int reason = buyerIdleReason();
        if (reason == lastLoggedIdleReason) {
            return;
        }
        lastLoggedIdleReason = reason;
        DeepSpaceDeployer.LOGGER.info("[深空铸造体][收购诊断] 位置={} 状态={}",
                worldPosition, idleReasonName(reason));
    }

    /**
     * <b>只推进 GeckoLib 的动画时间轴</b>（不管相位机）。
     *
     * <p>⚠️ 这个方法是给<b>商店那套展开/闭合动画</b>用的。
     * GeckoLib 的动画进度来自 {@link #getTick}，而它返回的是 {@code animAgeTicks} ——
     * 这个字段原先只有 {@link #clientAnimTick()}（收购投送机专用）在更新。
     * 商店如果不推它，时间轴恒为 0 ⇒ <b>动画永远停在第 0 帧</b>：
     * 状态机日志一切正常（那套用墙上时钟计时），但画面上一点不动 ——
     * 而且「展开」的第 0 帧正好就是闭合姿势，看起来就像"根本没播"。
     * （2026-10-16 实测踩到过。）</p>
     *
     * <p>时间原点在第一次调用时定下来，之后按真实经过的毫秒换算成 tick（每 50ms 一 tick）。</p>
     */
    public void advanceAnimClockOnly() {
        if (level == null || !level.isClientSide) {
            return;
        }
        long nowNs = System.nanoTime();
        if (animOriginNs == 0L) {
            animOriginNs = nowNs;
        }
        animAgeTicks = (nowNs - animOriginNs) / 50_000_000.0D;
    }

    public ItemStack card() {
        return items.get(SLOT_CARD);
    }

    /**
     * 这台机器当前用的账户 = <b>卡指向的账户</b>（一张卡 = 一个账户）。
     *
     * <p>挂失后的废卡只作装饰：这里返回 null，机器会显示未插卡/无账户，
     * 不能再消费，也不能再靠收购台给废卡打钱。</p>
     */
    public UUID activeAccount() {
        ItemStack card = items.get(SLOT_CARD);
        UUID account = CardData.ownerAccount(card);
        if (account == null) {
            return null;
        }
        MinecraftServer server = server();
        if (server != null && WalletService.isVoided(server, card)) {
            return null;
        }
        return account;
    }

    /** 当前插着的卡"指向的池子"（副卡=独立池，主卡=主池）；查卡内余额时用 */
    public UUID activeCardPool() {
        return CardData.accountId(items.get(SLOT_CARD));
    }

    public String activeAccountName() {
        return CardData.accountName(items.get(SLOT_CARD));
    }

    private MinecraftServer server() {
        return level == null ? null : level.getServer();
    }

    /** 当前账户在本公司的声望 */
    public int activeReputation() {
        UUID account = activeAccount();
        MinecraftServer server = server();
        if (account == null || server == null) {
            return 0;
        }
        return ReputationData.get(server).reputation(account, company());
    }

    // ---------------- 公司账户（世界级存档，同一家公司全图通用） ----------------

    /** 公司账户存档；拿不到服务器（比如客户端）就返回 null */
    private CompanyFunds funds() {
        MinecraftServer server = server();
        return server == null ? null : CompanyFunds.get(server);
    }

    /** 某个账户在这台方块所属公司的余额（同一家公司的所有方块是同一笔钱） */
    public long localBalance(UUID account) {
        CompanyFunds funds = funds();
        return funds == null ? 0L : funds.balance(account, company());
    }

    public long activeLocalBalance() {
        return localBalance(activeAccount());
    }

    private long addLocal(UUID account, long delta) {
        CompanyFunds funds = funds();
        if (funds == null || account == null) {
            return 0L;
        }
        return funds.add(account, company(), delta);
    }

    // ---------------- 插卡开机 ----------------

    public void insertCard(ItemStack held, Player player) {
        ItemStack previous = items.get(SLOT_CARD);
        ItemStack stored = held.copyWithCount(1);
        // 未绑定的新卡：先查 2 张上限，别让“插机器”绕过卡界面
        if (player instanceof ServerPlayer sp && !CardData.hasAccount(stored) && !WalletService.canAdd(sp)) {
            WalletService.limitMessage(sp);
            return;
        }
        CardData.bindIfMissing(stored, player);
        UUID account = CardData.accountId(stored);
        if (account != null) {
            if (player instanceof ServerPlayer serverPlayer) {
                // 先登记/迁移：ensureRegistered 会挡住废卡，并把老卡便携余额迁进账户
                WalletService.ensureRegistered(serverPlayer, stored);
            } else {
                CardData.migrateToAccount(stored, funds(), account);
            }
        }
        items.set(SLOT_CARD, stored);
        held.shrink(1);
        if (!previous.isEmpty()) {
            if (!player.getInventory().add(previous)) {
                player.drop(previous, false);
            }
        }
        afterChange();
    }

    private void afterChange() {
        setChanged();
        updateLitState();
        syncToClient();
    }

    /**
     * 「资金转化界面」动过钱之后调一次：让方块实体与界面数据保持一致。
     *
     * <p>那个界面里两张卡都不在机器卡槽里，所以公司账户的存/取动作完成后，
     * 服务端只需要通知方块实体刷新一次（钱包在世界存档里，本来就不会丢）。</p>
     */
    public void afterTransfer() {
        afterChange();
    }

    private void updateLitState() {
        if (level == null || level.isClientSide) {
            return;
        }
        BlockState state = getBlockState();
        if (!state.hasProperty(MachineBlock.LIT)) {
            return;
        }
        boolean lit = !items.get(SLOT_CARD).isEmpty();
        if (state.getValue(MachineBlock.LIT) != lit) {
            level.setBlock(worldPosition, state.setValue(MachineBlock.LIT, lit), Block.UPDATE_ALL);
        }
    }

    /** 把最新状态推给客户端（方块更新；内核的缩略图由 tick 按"刷新代"节流发送） */
    public void syncToClient() {
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
    }

    /** 把图纸缩略图发给附近正开着核心界面的玩家（只在"图纸刷新过"时发一次） */
    private void sendThumbnailsToNearby() {
        if (!PhysicsBlueprintLibrary.available() || !PhysicsBlueprintLibrary.thumbnailsReady()
                || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        java.util.Map<String, byte[]> thumbs = PhysicsBlueprintLibrary.thumbnails();
        if (thumbs.isEmpty()) {
            return;
        }
        java.util.List<String> names = new java.util.ArrayList<>(thumbs.size());
        java.util.List<byte[]> pngs = new java.util.ArrayList<>(thumbs.size());
        for (java.util.Map.Entry<String, byte[]> entry : thumbs.entrySet()) {
            names.add(entry.getKey());
            pngs.add(entry.getValue());
        }
        BlueprintThumbnailsPayload payload = new BlueprintThumbnailsPayload(names, pngs);
        for (ServerPlayer player : serverLevel.players()) {
            if (player.blockPosition().distSqr(worldPosition) <= 64.0D * 64.0D) {
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, payload);
            }
        }
        sentThumbnailGeneration = PhysicsBlueprintLibrary.thumbnailGeneration();
    }

    // ---------------- 收购站：自动收物品换钱 ----------------

    public static void serverTick(Level level, BlockPos pos, BlockState state, MachineBlockEntity blockEntity) {
        if (level.isClientSide()) {
            return;
        }
        // 工厂核心沿用"每 10 tick 走一次"的慢节奏（组装进度按 10 tick 步进）
        // 收购站改成老板要求的"每 tick 结算 N 次"（N = buyer.batches_per_tick，默认 10）
        switch (blockEntity.kind()) {
            case BUYER -> {
                // 老板定稿：**收购和动画绑成一个循环** ——
                //   收一轮（所有格子一次收光）→ 播一次「投送」→ 等动画播完 → 再收下一轮
                // 所以这里不再按固定 tick 间隔，而是看"上一轮动画是否播完"。
                blockEntity.logIdleReason();        // 原因变化时写一条日志（不动时好定位）
                blockEntity.processBuyerCycle();
            }
            // 寄售市场：每 40 tick 主动推一次（挂单存在世界存档里，别的市场方块改了这边也要跟上）
            case MARKET -> {
                if (level.getGameTime() % 40L == 0L) {
                    blockEntity.sendMarketToNearby();
                }
            }
            case CORE -> {
                if (level.getGameTime() % 10L == 0L) {
                    blockEntity.processCore();
                    // 缺料清单写进剪贴板格里那块剪贴板（没变化就不写）
                    blockEntity.exportShortageToClipboard();
                }
                // 图纸缩略图生成好了 / 刷新过 → 发一趟（每个核心各自记一次"发到第几代"）
                if (level.getGameTime() % 20L == 0L
                        && blockEntity.sentThumbnailGeneration != PhysicsBlueprintLibrary.thumbnailGeneration()) {
                    blockEntity.sendThumbnailsToNearby();
                }
            }
            // 商店（星联那套）：检查有没有玩家在 2 格内 → 驱动展开/闭合动画。
            // 每 5 tick 算一次就够（0.25 秒），不必每 tick 遍历玩家列表。
            case TERMINAL -> {
                // 商店库存：每 tick 走倒计时，到间隔补满当前价目表
                blockEntity.tickShopRestock();
                if (level.getGameTime() % 5L == 0L) {
                    blockEntity.updateAnyoneNear();
                }
            }
            default -> {
            }
        }
    }

    // ---------------- 商店：玩家靠近检测（展开/闭合动画的触发源） ----------------

    /** 判定半径（格）：老板定稿 —— 水平 2 格内 */
    public static final double NEAR_RADIUS = 2.0D;

    /** 有没有玩家在水平 2 格内（服务端算，同步给客户端做展开/闭合） */
    private boolean anyoneNear;

    /** 给客户端动画看的：现在有没有人在附近 */
    public boolean anyoneNear() {
        return anyoneNear;
    }

    /**
     * 服务端：重算"有没有玩家在附近"，有变化才同步。
     *
     * <p><b>为什么算服务端</b>：谁在附近是服务端的事实（客户端不一定知道所有玩家），
     * 而且这和收购站一个思路 —— 客户端只负责"照着服务端的状态播动画"。</p>
     *
     * <p>距离用<b>水平距离</b>（忽略高度差，老板选的）：站在商店上方一层的平台上也算靠近。
     * 旁观模式不算。</p>
     */
    public void updateAnyoneNear() {
        if (level == null || level.isClientSide) {
            return;
        }
        boolean near = false;
        double r2 = NEAR_RADIUS * NEAR_RADIUS;
        for (Player p : level.players()) {
            if (p.isSpectator()) {
                continue;
            }
            double dx = p.getX() - (worldPosition.getX() + 0.5D);
            double dz = p.getZ() - (worldPosition.getZ() + 0.5D);
            if (dx * dx + dz * dz <= r2) {
                near = true;
                break;
            }
        }
        if (near != anyoneNear) {
            anyoneNear = near;
            setChanged();
            syncToClient();                 // 同步包带到客户端 → 那边开始播展开/闭合
        }
    }

    /**
     * 给「商店展开/闭合」用的重启动画入口。
     *
     * <p>实现就是 {@link #forceRestart}（内部用 {@code forceAnimationReset}）；
     * 单独开一个方法只是让动画逻辑那个类不必知道内部细节。</p>
     */
    public void restartAnimation(String controllerName, String animName) {
        forceRestart(controllerName, animName);
    }

    /** 商店展开/闭合的动画状态机（纯客户端；懒创建，逻辑见 TerminalShopAnim） */
    private com.deepspacedeployer.client.gecko.TerminalShopAnim shopAnim;

    public com.deepspacedeployer.client.gecko.TerminalShopAnim shopAnim() {
        if (shopAnim == null) {
            shopAnim = new com.deepspacedeployer.client.gecko.TerminalShopAnim(this);
        }
        return shopAnim;
    }

    /**
     * 结算一格投料：把输入格里的东西按收购价换成当前账户的余额。
     *
     * @return 这一批有没有真的卖出东西（false = 没得卖/没开机，调用方可以提前收手）
     */
    /**
     * 收购一个"循环"：<b>所有格子一次收光</b>，然后等动画播完再收下一轮。
     *
     * <p>老板的设计：收购效率由动画时长决定 —— 收一轮 → 播一次「投送」→ 等播完 → 再收。</p>
     */
    private boolean processBuyerCycle() {
        return autoSell && processBuyerBatch();
    }

    /**
     * 尝试处理「一组」：按等级累计额度（低级 1 组/1.5s、中级 2 组/1s、高级 3 组/0.5s）。
     *
     * <p>一组 = 一个输入格里的那叠物品。每 tick 最多处理一组，所以单机仍不会超过
     * 64 物品/tick；全服 2000/tick 的硬上限也保留。</p>
     */
    private boolean processBuyerBatch() {
        if (!isOnline() || activeAccount() == null) {
            return false;
        }
        // ★ 头顶被方块挡住 → 不工作（老板要求；界面里会提示"上方被阻挡无法工作"）
        if (blockedAbove()) {
            return false;
        }
        updateBuyerCredit();
        if (buyerBatchCredit < 1.0D) {
            return false;
        }
        if (!sellOneBatch()) {
            return false;
        }
        buyerBatchCredit = Math.max(0.0D, buyerBatchCredit - 1.0D);
        return true;
    }

    /** 按等级把时间换成“组”额度；最多攒一个间隔的量，避免离线/停机后爆一大波。 */
    private void updateBuyerCredit() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        int tick = serverLevel.getServer().getTickCount();
        if (buyerCreditTick < 0) {
            buyerCreditTick = tick;
            return;
        }
        int delta = tick - buyerCreditTick;
        if (delta <= 0) {
            return;
        }
        buyerCreditTick = tick;
        Tier currentTier = tier();
        double rate = (double) currentTier.buyerGroupsPerInterval()
                / currentTier.buyerIntervalTicks();
        double cap = currentTier.buyerGroupsPerInterval();
        buyerBatchCredit = Math.min(cap, buyerBatchCredit + delta * rate);
    }

    /**
     * 本 tick 这台收购机还能处理多少物品 = min(单机 64, 全服剩余 2000)。
     */
    private int buyerBudget() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return 0;
        }
        int tick = serverLevel.getServer().getTickCount();
        if (tick != buyerMachineTick) {
            buyerMachineTick = tick;
            buyerMachineUsed = 0;
        }
        if (tick != buyerGlobalTick) {
            buyerGlobalTick = tick;
            buyerGlobalUsed = 0;
        }
        int machineLeft = Math.max(0, Config.BUYER_ITEMS_PER_TICK.get() - buyerMachineUsed);
        int globalLeft = Math.max(0, Config.BUYER_GLOBAL_ITEMS_PER_TICK.get() - buyerGlobalUsed);
        return Math.min(machineLeft, globalLeft);
    }

    private void buyerRecordSold(int count) {
        if (count <= 0) {
            return;
        }
        buyerMachineUsed += count;
        buyerGlobalUsed += count;
    }

    /**
     * 客户端回执：「投送」动画播完了。
     *
     * <p>只影响表现，不影响结算（老板 2026-10-23 定：动画不一定要匹配）。</p>
     */
    public void onAnimDone() {
        if (level == null || level.isClientSide) {
            return;
        }
        animAckReady = true;
    }

    /**
     * @deprecated 旧的"收一格"实现，保留供参考；现在走 {@link #processBuyerCycle()}。
     */
    @Deprecated
    private boolean processBuyer() {
        if (!isOnline() || activeAccount() == null || !autoSell) {
            return false;
        }
        return processBuyerBatch();
    }

    /** 卖一批（一个格子的东西）：自动模式每周期调一次，点击模式由按钮触发 */
    private boolean sellOneBatch() {
        if (!isOnline() || activeAccount() == null) {
            return false;
        }
        int budget = buyerBudget();
        if (budget <= 0) {
            return false;
        }
        ShopData shop = ShopData.get();
        Company company = company();
        for (int slot = SLOT_INPUT_START; slot < SLOT_COUNT; slot++) {
            ItemStack stack = items.get(slot);
            if (stack.isEmpty()) {
                continue;
            }
            TradeEntry entry = shop.findSellEntry(company, stack);
            if (entry == null) {
                continue;
            }
            int count = Math.min(stack.getCount(), budget);
            int perItem = Math.max(1, (int) Math.round(
                    (double) entry.price() / Math.max(1, entry.count())));
            long earned = (long) perItem * count;
            buyerRecordSold(count);
            addLocal(activeAccount(), earned);
            stack.shrink(count);
            items.set(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
            afterChange();
            notifySold();      // 播「投送」动画（只做表现，不再阻塞）
            return true;
        }
        return false;
    }

    // ---------------- 商店：买东西 ----------------

    /**
     * 购买第 index 个商品（index 对应本公司完整商品列表的下标）。
     *
     * <p>校验顺序：等级 → 声望 → 余额（先方块内、再卡上）。花钱的是"当前账户"。</p>
     */
    private boolean buyOffer(ServerPlayer player, ItemStack card, int index) {
        // 机器卡槽里那张卡是"付款账户"：插着卡才能买（不插卡也能打开界面看价目表）
        if (card.isEmpty()) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.need_card"), true);
            return false;
        }
        // 卡没绑定就顺手绑到操作者（原来这段在 handleButton 的公共路径上，
        // 现在只有"真要花钱"的动作才需要账户）
        if (!CardData.hasAccount(card) && !WalletService.canAdd(player)) {
            WalletService.limitMessage(player);
            return false;
        }
        CardData.bindIfMissing(card, player);
        afterChange();
        if (activeAccount() == null) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.no_account"), false);
            return false;
        }
        List<TradeEntry> offers = ShopData.get().buyOffers(company());
        if (index < 0 || index >= offers.size()) {
            return false;
        }
        TradeEntry entry = offers.get(index);
        if (!entry.isValid()) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.offer_invalid"), true);
            return false;
        }
        ensureShopStock(offers);
        if (shopStockOf(entry) <= 0) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.shop_out_of_stock"), true);
            return false;
        }
        if (entry.tier().rank() > tierIndex) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.tier_locked",
                    entry.tier().displayName(), tier().displayName()), true);
            return false;
        }
        UUID account = activeAccount();
        MinecraftServer server = player.server;
        int reputation = account == null ? 0 : ReputationData.get(server).reputation(account, company());
        if (reputation < entry.reputation()) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.reputation_locked",
                    entry.reputation(), reputation), true);
            return false;
        }
        if (!PaymentService.canPay(player, account, company(), entry.price())) {
            WalletService.companyInsufficientMessage(player);
            return false;
        }
        if (!tryPay(player, entry.price(), company())) {
            WalletService.companyInsufficientMessage(player);
            return false;
        }
        if (account != null) {
            ReputationData.get(server).addSpent(account, company(), entry.price());
        }
        ItemStack result = entry.createStack();
        if (!player.getInventory().add(result)) {
            player.drop(result, false);
        }
        takeShopStock(entry);
        afterChange();
        broadcastShopData();
        Component name = result.getHoverName();
        player.displayClientMessage(Component.translatable("msg.deepspacedeployer.bought",
                name, entry.price(), activeAccountName()), false);
        return true;
    }

    /**
     * 扣款：账户优先；账户不够时自动用实体币，实体币找零只给 1 元券。
     *
     * <p>老板 2026-10-19 的钱包定稿：卡只是账户指针，卡上不再放便携余额；
     * 所以这里不再有"方块里不够再扣卡上"的第二层。</p>
     *
     * @return 是否扣款成功
     */
    public boolean tryPay(ServerPlayer player, long amount, Company payCompany) {
        if (amount <= 0L) {
            return true;
        }
        UUID account = activeAccount();
        if (PaymentService.pay(player, account, payCompany, amount) == null) {
            return false;
        }
        afterChange();
        return true;
    }

    /** 指定公司的账户余额（无归属方块用） */
    public long companyBalance(UUID account, Company company) {
        CompanyFunds funds = funds();
        return funds == null || account == null ? 0L : funds.balance(account, company);
    }

    private long addCompany(UUID account, Company company, long delta) {
        CompanyFunds funds = funds();
        if (funds == null || account == null) {
            return 0L;
        }
        return funds.add(account, company, delta);
    }

    // ---------------- 工厂：蓝图 → 材料 → 组装 ----------------

    /** 每 10 tick 走一次：没组装就把投料吸进材料缓冲；在组装就推进进度 */
    private void processCore() {
        if (isAssembling()) {
            assemblyTicks += 10;
            if (assemblyTicks % 20 == 0) {
                syncToClient();
            }
            if (assemblyTicks >= assemblyTotal) {
                finishAssembly();
            }
            return;
        }
        if (!isOnline() || activeAccount() == null) {
            return;
        }
        boolean changed = false;
        for (int slot = SLOT_INPUT_START; slot < SLOT_COUNT; slot++) {
            ItemStack stack = items.get(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            materials.merge(id, stack.getCount(), Integer::sum);
            items.set(slot, ItemStack.EMPTY);
            changed = true;
        }
        if (changed) {
            afterChange();
        }
    }

    public boolean isAssembling() {
        return assemblyTotal > 0;
    }

    /**
     * 把<b>当前缺的材料</b>写进剪贴板格里那块剪贴板（老板要的"导出给玩家看"）。
     *
     * <p>每 10 tick 检查一次（跟着核心的慢节奏走）：剪贴板内容没变就跳过，
     * 变了才写 —— 这样它总是"当前这一版"的缺料清单，也不会每 tick 都改物品数据。</p>
     *
     * @return 这一 tick 有没有真的写进去
     */
    private boolean exportShortageToClipboard() {
        ItemStack board = items.get(SLOT_CLIPBOARD);
        if (board.isEmpty() || !CreateClipboardBridge.isClipboard(board)) {
            return false;
        }
        BlueprintInfo info = PhysicsBlueprintLibrary.get(selectedBlueprint);
        if (info == null) {
            return false;
        }
        // 组装中就没有"缺料"这回事了
        if (isAssembling()) {
            return false;
        }
        List<CreateClipboardBridge.Line> lines = new ArrayList<>();
        int missingKinds = 0;
        long missingTotal = 0L;
        for (Map.Entry<String, Integer> need : info.materials().entrySet()) {
            int have = materials.getOrDefault(need.getKey(), 0);
            int lack = need.getValue() - have;
            if (lack <= 0) {
                continue;
            }
            missingKinds++;
            missingTotal += lack;
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(need.getKey()));
            String name = item == Items.AIR
                    ? need.getKey()
                    : item.getDescription().getString();
            lines.add(new CreateClipboardBridge.Line(new ItemStack(item), lack,
                    Component.literal(lack + " × " + name)));
        }
        // 内容没变就不重复写（省得每 10 tick 都刷新物品数据）
        String signature = selectedBlueprint + "|" + missingKinds + "|" + missingTotal + "|" + lines.size();
        if (signature.equals(clipboardSignature)) {
            return false;
        }
        Component title = lines.isEmpty()
                ? Component.translatable("clipboard.deepspacedeployer.ready", selectedBlueprint)
                : Component.translatable("clipboard.deepspacedeployer.shortage",
                        selectedBlueprint, missingKinds, missingTotal);
        if (!CreateClipboardBridge.write(board, title, lines)) {
            return false;
        }
        clipboardSignature = signature;
        afterChange();
        return true;
    }

    public String selectedBlueprint() {
        return selectedBlueprint;
    }

    public Map<String, Integer> materials() {
        return materials;
    }

    public int assemblyTicks() {
        return assemblyTicks;
    }

    public int assemblyTotal() {
        return assemblyTotal;
    }

    public List<BlueprintInfo> blueprints() {
        return blueprintSnapshot;
    }

    /** 材料够不够（含正在组装中被预留的那部分） */
    public int haveMaterial(String itemId) {
        return materials.getOrDefault(itemId, 0) + reserved.getOrDefault(itemId, 0);
    }

    private boolean selectBlueprint(ServerPlayer player, int index) {
        List<BlueprintInfo> list = PhysicsBlueprintLibrary.all();
        if (index < 0 || index >= list.size()) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.blueprint_missing"), true);
            return false;
        }
        BlueprintInfo info = list.get(index);
        int max = Math.min(tier().maxBlueprintBlocks(), Config.MAX_BLUEPRINT_BLOCKS.get());
        if (info.totalBlocks() > max) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.blueprint_too_big",
                    info.totalBlocks(), max), true);
            return false;
        }
        selectedBlueprint = info.name();
        afterChange();
        player.displayClientMessage(Component.translatable("msg.deepspacedeployer.blueprint_selected",
                info.name(), info.sizeText(), info.totalBlocks()), false);
        return true;
    }

    /** 创建板条箱模式：完全不限方块数（老板要求）—— 只在创造板条箱旁边生效 */
    private int effectiveMaxBlocks(BlueprintInfo info) {
        if (creativeMode(true)) {
            return Integer.MAX_VALUE;
        }
        return Math.min(tier().maxBlueprintBlocks(), Config.MAX_BLUEPRINT_BLOCKS.get());
    }

    /**
     * 一键组装：组装费按<b>这台核心所属公司</b>结算（核心按公司分成 6 种方块，不再需要玩家选）。
     */
    private boolean startAssembly(ServerPlayer player, ItemStack card) {
        return startAssembly(player, card, company());
    }

    /**
     * 模块打印机：直接打印第 index 个预设模块（只收本公司货币，不消耗材料）。
     */
    private boolean printModule(ServerPlayer player, ItemStack card, int index) {
        List<ModulePresetLibrary.Preset> presets =
                ModulePresetLibrary.visible(company(), tier());
        if (index < 0 || index >= presets.size()) {
            player.displayClientMessage(Component.translatable(
                    "msg.deepspacedeployer.module_no_presets"), true);
            return false;
        }
        ModulePresetLibrary.Preset preset = presets.get(index);
        if (card.isEmpty()) {
            player.displayClientMessage(Component.translatable(
                    "msg.deepspacedeployer.need_card"), true);
            return false;
        }
        if (!CardData.hasAccount(card) && !WalletService.canAdd(player)) {
            WalletService.limitMessage(player);
            return false;
        }
        CardData.bindIfMissing(card, player);
        afterChange();
        UUID account = activeAccount();
        if (account == null) {
            player.displayClientMessage(Component.translatable(
                    "msg.deepspacedeployer.no_account"), false);
            return false;
        }
        CompanyFunds funds = CompanyFunds.get(player.server);
        long balance = funds.balance(account, company());
        if (balance < preset.price()) {
            player.displayClientMessage(Component.translatable(
                    "msg.deepspacedeployer.module_insufficient", preset.price(), balance), true);
            return false;
        }

        PhysicsBlueprintLibrary.Entry entry = preset.entry();
        BlockPos target;
        if (hasTargetOverride) {
            double dx = overrideX - (worldPosition.getX() + 0.5);
            double dz = overrideZ - (worldPosition.getZ() + 0.5);
            int maxRadius = Config.ASSEMBLY_MAX_RADIUS.get();
            if (dx * dx + dz * dz > (double) maxRadius * maxRadius) {
                player.displayClientMessage(Component.translatable(
                        "msg.deepspacedeployer.target_too_far", maxRadius), true);
                return false;
            }
            target = new BlockPos(overrideX - entry.sizeX() / 2,
                    worldPosition.getY(), overrideZ - entry.sizeZ() / 2);
        } else {
            Direction dir = player.getDirection();
            if (dir.getAxis().isVertical()) {
                dir = Direction.NORTH;
            }
            target = worldPosition.relative(dir, 3)
                    .offset(-(entry.sizeX() / 2), 0, -(entry.sizeZ() / 2));
        }
        if (!(level instanceof ServerLevel serverLevel)
                || !PhysicsBlueprintLibrary.areaClearEntry(serverLevel, entry, target)) {
            player.displayClientMessage(Component.translatable(
                    "msg.deepspacedeployer.area_blocked"), true);
            return false;
        }

        // 只扣对应公司的账户余额；实体币/通用账户不参与模块打印
        funds.add(account, company(), -preset.price());
        Vec3 center = new Vec3(
                target.getX() + entry.sizeX() / 2.0D + 0.5D,
                target.getY(),
                target.getZ() + entry.sizeZ() / 2.0D + 0.5D);
        PhysicsBlueprintLibrary.Result result =
                PhysicsBlueprintLibrary.spawnEntry(serverLevel, entry, center);
        if (!result.success()) {
            funds.add(account, company(), preset.price());   // 生成失败退款
            player.displayClientMessage(Component.translatable(
                    "msg.deepspacedeployer.module_print_failed", result.detail()), true);
            return false;
        }
        broadcastNearby(Component.translatable(
                "msg.deepspacedeployer.module_print_done", preset.display(), result.detail()));
        afterChange();
        return true;
    }

    /** 一键组装（指定付费用的公司；保留这个重载给"核心按公司结算"和其它调用方） */
    private boolean startAssembly(ServerPlayer player, ItemStack card, Company payCompany) {
        if (isAssembling()) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.already_assembling"), true);
            return false;
        }
        // 创造板条箱模式：材料无限、不收组装费、立刻放置（老板要求）
        boolean creative = creativeMode(true);
        // 正常模式：组装费从"卡槽里那张卡"的主账户在 payCompany 名下的余额扣，没插卡就没法收费
        if (!creative && card.isEmpty()) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.need_card"), true);
            return false;
        }
        if (!creative) {
            if (!CardData.hasAccount(card) && !WalletService.canAdd(player)) {
                WalletService.limitMessage(player);
                return false;
            }
            CardData.bindIfMissing(card, player);
            afterChange();
            if (activeAccount() == null) {
                player.displayClientMessage(Component.translatable("msg.deepspacedeployer.no_account"), false);
                return false;
            }
        }
        BlueprintInfo info = PhysicsBlueprintLibrary.get(selectedBlueprint);
        if (info == null) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.blueprint_missing"), true);
            return false;
        }
        int max = effectiveMaxBlocks(info);
        if (info.totalBlocks() > max) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.blueprint_too_big",
                    info.totalBlocks(), max), true);
            return false;
        }
        // 1) 材料齐不齐（创造板条箱模式跳过）
        if (!creative) {
            for (Map.Entry<String, Integer> need : info.materials().entrySet()) {
                int have = materials.getOrDefault(need.getKey(), 0);
                if (have < need.getValue()) {
                    player.displayClientMessage(Component.translatable("msg.deepspacedeployer.missing_material",
                            materialName(need.getKey()), need.getValue(), have), true);
                    return false;
                }
            }
        }
        // 2) 场地空不空（先查场地再收钱，免得白花）
        BlockPos target;
        if (hasTargetOverride) {
            // 玩家在地图上选过投放点：以选中点为中心展开蓝图
            double dx = overrideX - (worldPosition.getX() + 0.5);
            double dz = overrideZ - (worldPosition.getZ() + 0.5);
            int maxRadius = Config.ASSEMBLY_MAX_RADIUS.get();
            if (dx * dx + dz * dz > (double) maxRadius * maxRadius) {
                player.displayClientMessage(Component.translatable("msg.deepspacedeployer.target_too_far", maxRadius), true);
                return false;
            }
            target = new BlockPos(overrideX - info.sizeX() / 2, worldPosition.getY(), overrideZ - info.sizeZ() / 2);
        } else {
            Direction dir = player.getDirection();
            if (dir.getAxis().isVertical()) {
                dir = Direction.NORTH;
            }
            BlockPos front = worldPosition.relative(dir, 3);
            target = front.offset(-(info.sizeX() / 2), 0, -(info.sizeZ() / 2));
        }
        if (!(level instanceof ServerLevel assemblyLevel)
                || !PhysicsBlueprintLibrary.areaClear(assemblyLevel, selectedBlueprint, target)) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.area_blocked"), true);
            return false;
        }
        // 3) 收组装费（创造板条箱模式免费）；核心无归属，从玩家选的 payCompany 扣
        long fee = creative ? 0L : (long) Config.ASSEMBLY_FEE_PER_BLOCK.get() * info.totalBlocks();
        if (fee > 0L && !tryPay(player, fee, payCompany)) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.cannot_pay", fee,
                    activeAccountName(), companyBalance(activeAccount(), payCompany)), true);
            return false;
        }
        // 4) 扣材料 → 预留（创造板条箱模式不扣：材料无限）
        reserved.clear();
        if (creative) {
            // 预留表按蓝图需要的量填上，组装时按它扣（但材料不够也照放）
            for (Map.Entry<String, Integer> need : info.materials().entrySet()) {
                reserved.merge(need.getKey(), need.getValue(), Integer::sum);
            }
        } else {
            for (Map.Entry<String, Integer> need : info.materials().entrySet()) {
                int left = materials.getOrDefault(need.getKey(), 0) - need.getValue();
                if (left <= 0) {
                    materials.remove(need.getKey());
                } else {
                    materials.put(need.getKey(), left);
                }
                reserved.merge(need.getKey(), need.getValue(), Integer::sum);
            }
        }
        assemblyTotal = Math.max(20, secondsForTier() * 20);
        assemblyTicks = 0;
        targetX = target.getX();
        targetY = target.getY();
        targetZ = target.getZ();
        hasTarget = true;
        afterChange();
        if (creative) {
            // 老板要求"无组装时间立刻放置"：不留倒计时，直接把结构放出去
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.assembly_instant",
                    info.name()), false);
            finishAssembly();
            return true;
        }
        player.displayClientMessage(Component.translatable("msg.deepspacedeployer.assembly_started",
                info.name(), assemblyTotal / 20, fee), false);
        return true;
    }

    /**
     * 工厂装配核心旁边有没有"创造板条箱"（机械动力）→ 视为材料无限、免费、立刻组装。
     *
     * <p>判定完全走反射（{@link CreativeCrateBridge}），没装机械动力就永远返回 false。</p>
     *
     * <p>扫描范围最大 65×65×65，所以结果做了缓存：默认每 20 tick（1 秒）才重新扫一次，
     * 界面打开时会强制刷新一次（见 {@code createDataAccess}）。</p>
     */
    public boolean creativeMode() {
        return creativeMode(false);
    }

    /** @param force true = 立刻重新扫描（界面打开、组装开始这些时刻用） */
    public boolean creativeMode(boolean force) {
        if (!Config.CREATIVE_CRATE_INFINITE.get()) {
            return false;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        if (!force && level.getGameTime() - creativeCheckedAt < 20L) {
            return creativeCached;
        }
        creativeCheckedAt = level.getGameTime();

        // 诊断：把每一步的结果都打出来，这样一测就知道卡在哪
        boolean bridgeOk = CreativeCrateBridge.available();
        int range = Config.CREATIVE_CRATE_RANGE.get();
        java.util.List<ItemStack> found = CreativeCrateBridge.findNearby(
                serverLevel, worldPosition, range, new ArrayList<>());
        creativeCached = !found.isEmpty();

        // 只在这一格附近真的出现过创造板条箱、或者扫描状态变化时才刷日志，避免刷屏
        String key = bridgeOk + "|" + creativeCached + "|" + found.size();
        if (!key.equals(lastCreativeDiag)) {
            lastCreativeDiag = key;
            com.deepspacedeployer.DeepSpaceDeployer.LOGGER.info(
                    "[深空铸造体][创造板条箱] 位置={} 种类={} 桥接可用={} 扫描半径={} 找到创造板条箱={} 供应物品={} → 无限材料={}",
                    worldPosition, kind(), bridgeOk, range,
                    found.size(),
                    found.isEmpty() ? "无" : net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .getKey(found.get(0).getItem()).toString(),
                    creativeCached);
        }
        return creativeCached;
    }

    /** 上次打过的创造板条箱诊断（状态没变就不重复打） */
    private String lastCreativeDiag = "";

    /** 附近创造板条箱"无限供应"的物品 id（给界面显示、给材料判定用） */
    public java.util.Set<String> creativeSuppliedIds() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return java.util.Set.of();
        }
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (ItemStack stack : CreativeCrateBridge.findNearby(serverLevel, worldPosition,
                Config.CREATIVE_CRATE_RANGE.get(), new ArrayList<>())) {
            ids.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        }
        return ids;
    }

    private int secondsForTier() {
        return switch (tier()) {
            case LOW -> Config.ASSEMBLY_SECONDS_LOW.get();
            case MID -> Config.ASSEMBLY_SECONDS_MID.get();
            case HIGH -> Config.ASSEMBLY_SECONDS_HIGH.get();
        };
    }

    /**
     * 重新扫描载具图纸文件夹，把新放进去的文件读进来。
     *
     * <p>老板要求"打开后可以通过 Sable-Schematics 文件夹导入自己的载具"：
     * Sable 的 {@code SableBlueprintRootFiles.list()} 每次都是现扫磁盘，所以这里只要重新读一遍
     * 就能看到新丢进去的 {@code .nbt}（另外还会读 {@code enxv_aeronautics_structures}）。
     * 读完把新的蓝图列表推给客户端，界面上立刻能看到。</p>
     */
    private boolean reloadBlueprints(ServerPlayer player) {
        if (level == null || level.getServer() == null) {
            return false;
        }
        if (!PhysicsBlueprintLibrary.available()) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.reload_no_sable"), true);
            return false;
        }
        int count;
        try {
            count = PhysicsBlueprintLibrary.refresh(level.getServer());
        } catch (Throwable t) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.reload_failed",
                    String.valueOf(t)), true);
            return false;
        }
        syncToClient();
        player.displayClientMessage(Component.translatable("msg.deepspacedeployer.reload_done",
                count, PhysicsBlueprintLibrary.folderHint()), false);
        return true;
    }

    private boolean cancelAssembly(ServerPlayer player) {        if (!isAssembling()) {
            return false;
        }
        // 创造板条箱模式那次没扣材料，取消时也不能退（否则凭空生材料）
        if (!creativeMode()) {
            for (Map.Entry<String, Integer> entry : reserved.entrySet()) {
                materials.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
        }
        reserved.clear();
        assemblyTicks = 0;
        assemblyTotal = 0;
        hasTarget = false;
        afterChange();
        player.displayClientMessage(Component.translatable("msg.deepspacedeployer.assembly_cancelled"), false);
        return true;
    }

    /**
     * 目标位置能不能放下（蓝图里每个非空气方块对应的位置都必须是空气/可替换）。
     *
     * <p>用的是我们自己解析出来的方块坐标，不再依赖 {@code filterBlocks(…, null)}
     * ——那个过滤器传 null 时一个方块都匹配不到（见 BlueprintParser 的注释）。</p>
     */
    private boolean areaClear(ParsedBlueprint blueprint, BlockPos target) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        for (BlockPos relative : blueprint.positions()) {
            if (!serverLevel.getBlockState(target.offset(relative)).canBeReplaced()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 蓝图里第一个实体方块在世界里的位置（物理化的"起始方块"）。
     * 用蓝图自己的方块清单，而不是从角落做连通搜索 —— 角落很可能是空气，那样会搜出空集合。
     */
    private BlockPos firstSolidBlock(ParsedBlueprint blueprint, BlockPos target) {
        for (BlockPos relative : blueprint.positions()) {
            BlockPos world = target.offset(relative);
            if (level != null && !level.getBlockState(world).isAir()) {
                return world;
            }
        }
        return null;
    }

    /** 组装完成：把结构放进世界；放不下就把材料退回缓冲 */
    private void finishAssembly() {
        BlueprintInfo info = PhysicsBlueprintLibrary.get(selectedBlueprint);
        boolean spawned = false;
        boolean creative = creativeMode();
        PhysicsBlueprintLibrary.Result spawn = null;
        BlockPos target = hasTarget ? new BlockPos(targetX, targetY, targetZ) : null;
        if (info != null && level instanceof ServerLevel serverLevel && target != null) {
            if (PhysicsBlueprintLibrary.areaClear(serverLevel, selectedBlueprint, target)) {
                // 直接把蓝图生成成物理结构（不再是"先放方块、再物理化"）。
                // 蓝图里的位置是结构中心，所以把投放点换算成中心点。
                Vec3 center = new Vec3(
                        target.getX() + info.sizeX() / 2.0D + 0.5D,
                        target.getY(),
                        target.getZ() + info.sizeZ() / 2.0D + 0.5D);
                spawn = PhysicsBlueprintLibrary.spawn(serverLevel, selectedBlueprint, center);
                spawned = spawn.success();
            } else {
                spawn = new PhysicsBlueprintLibrary.Result(false, 0, "投放点放不下（那里有方块）");
            }
        } else {
            spawn = new PhysicsBlueprintLibrary.Result(false, 0, "蓝图或投放点丢了");
        }
        assemblyTicks = 0;
        assemblyTotal = 0;
        hasTarget = false;
        if (spawned) {
            reserved.clear();
            broadcastNearby(Component.translatable("msg.deepspacedeployer.assembly_done", selectedBlueprint));
            broadcastNearby(Component.translatable("msg.deepspacedeployer.physics_done", spawn.detail()));
        } else {
            // 只有"真扣过材料"的那次才退材料：创造板条箱模式没扣过，退回去等于凭空生材料
            if (!creative) {
                for (Map.Entry<String, Integer> entry : reserved.entrySet()) {
                    materials.merge(entry.getKey(), entry.getValue(), Integer::sum);
                }
            }
            reserved.clear();
            broadcastNearby(Component.translatable("msg.deepspacedeployer.physics_failed",
                    spawn == null ? "未知原因" : spawn.detail()));
        }
        afterChange();
    }

    private void broadcastNearby(Component message) {
        if (level instanceof ServerLevel serverLevel) {
            for (ServerPlayer other : serverLevel.players()) {
                if (other.blockPosition().distSqr(worldPosition) <= 64.0D * 64.0D) {
                    other.displayClientMessage(message, false);
                }
            }
        }
    }

    private static Component materialName(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
        return item == Items.AIR ? Component.literal(itemId) : item.getDescription();
    }

    // ---------------- GUI 数据槽 ----------------

    public ContainerData createDataAccess() {
        return new ContainerData() {
            @Override
            public int get(int index) {
                long local = activeLocalBalance();
                return switch (index) {
                    case MachineMenu.DATA_POS_X -> worldPosition.getX();
                    case MachineMenu.DATA_POS_Y -> worldPosition.getY();
                    case MachineMenu.DATA_POS_Z -> worldPosition.getZ();
                    case MachineMenu.DATA_COMPANY -> company().ordinal();
                    case MachineMenu.DATA_ONLINE -> isOnline() ? 1 : 0;
                    case MachineMenu.DATA_LOCAL_LOW -> (int) (local & 0xFFFFFFFFL);
                    case MachineMenu.DATA_LOCAL_HIGH -> (int) (local >>> 32);
                    case MachineMenu.DATA_HAS_ACCOUNT -> activeAccount() != null ? 1 : 0;
                    case MachineMenu.DATA_TIER -> tierIndex;
                    case MachineMenu.DATA_REPUTATION -> activeReputation();
                    case MachineMenu.DATA_SYNCED -> 1;
                    case MachineMenu.DATA_TARGET_SET -> hasTargetOverride ? 1 : 0;
                    case MachineMenu.DATA_AUTO_SELL -> autoSell ? 1 : 0;
                    case MachineMenu.DATA_CREATIVE -> creativeMode(true) ? 1 : 0;
                    case MachineMenu.DATA_BLOCKED_ABOVE -> blockedAbove() ? 1 : 0;
                    case MachineMenu.DATA_IDLE_REASON -> buyerIdleReason();
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) {
                // 客户端只读
            }

            @Override
            public int getCount() {
                return MachineMenu.DATA_COUNT;
            }
        };
    }

    // ---------------- 玩家点按钮 ----------------

    public boolean handleButton(Player player, int id) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        // 「资金转化」：把界面换成资金转化界面（机器卡槽里那张卡跟着搬过去，返回时搬回来）
        // ⚠️ 必须排在"纯客户端动作"判断之前：ACTION_OPEN_TRANSFER(202) > ACTION_UI_BASE(150)，
        //    写在后面会被当成客户端动作直接吞掉（曾经真的踩过一次）。
        if (id == MachineMenu.ACTION_OPEN_TRANSFER) {
            return openTransfer(serverPlayer);
        }
        // ⚠️ 「列表下标」类动作必须全部排在"纯客户端动作"判断<b>之前</b>：
        //    蓝图下标区间是 1000+、购买是 100+，两者都 ≥ ACTION_UI_BASE(150)，
        //    写在后面会被当成客户端动作吞掉（这个坑踩过两次：先是资金转化按钮，后是选蓝图）。
        ItemStack card = items.get(SLOT_CARD);
        // 选蓝图：id = 1000 + 蓝图下标（蓝图份数不限，所以单独一个区间）
        if (kind() == MachineKind.CORE
                && id >= MachineMenu.ACTION_BLUEPRINT_BASE && id < MachineMenu.ACTION_BLUEPRINT_BASE + 256) {
            return selectBlueprint(serverPlayer, id - MachineMenu.ACTION_BLUEPRINT_BASE);
        }
        // 模块打印机：di = 1200 + 预设下标
        if (kind() == MachineKind.MODULE_PRINTER
                && id >= MachineMenu.ACTION_MODULE_PRINT_BASE
                && id < MachineMenu.ACTION_MODULE_PRINT_BASE + 256) {
            return printModule(serverPlayer, card, id - MachineMenu.ACTION_MODULE_PRINT_BASE);
        }
        // 购买商品：id = 100 + 商品下标（只有商店终端用这个区间）
        if (kind() == MachineKind.TERMINAL
                && id >= MachineMenu.ACTION_BUY_BASE && id < MachineMenu.ACTION_BUY_BASE + 100) {
            return buyOffer(serverPlayer, card, id - MachineMenu.ACTION_BUY_BASE);
        }
        // 剩下 ≥ ACTION_UI_BASE 的 id 才是纯客户端动作（服务端直接忽略）
        if (id >= MachineMenu.ACTION_UI_BASE) {
            return true;
        }
        // 老板要求：不插卡也能打开界面。所以这里<b>不再</b>用"机器有没有插卡 / 有没有账户"提前拦，
        // 改成每个动作自己判断：买/组装各自会提示"先插卡"，收购开关、看余额这些不花钱的动作照常可用。
        return switch (kind()) {
            // 终端：金钱相关的按钮（存票据/取现/兑换/余额）都搬进「资金转化界面」了，
            // 这里只剩"购买商品"（上面 id = 100 + 下标那段），别的 id 一律不认。
            case TERMINAL -> false;
            case MODULE_PRINTER -> false;
            case BUYER -> {
                yield switch (id) {
                    case 1 -> {
                        // 开关自动收购
                        autoSell = !autoSell;
                        player.displayClientMessage(Component.translatable(autoSell
                                ? "msg.deepspacedeployer.buyer_auto_on"
                                : "msg.deepspacedeployer.buyer_auto_off"), false);
                        afterChange();
                        yield true;
                    }
                    case 2 -> {
                        // 立即收购一批（关掉自动收购后手动卖）；同样受等级节奏/限流约束
                        int sold = 0;
                        while (sold < 9 && processBuyerBatch()) {
                            sold++;
                        }
                        player.displayClientMessage(Component.translatable(
                                "msg.deepspacedeployer.buyer_manual_sold", sold), sold == 0);
                        yield true;
                    }
                    case 3 -> {
                        // 切换收购结算公司（不改变方块归属）
                        setBuyerCompany(company().next());
                        sendShopData(serverPlayer);
                        player.displayClientMessage(Component.translatable(
                                "msg.deepspacedeployer.buyer_company_set",
                                company().displayName()), false);
                        yield true;
                    }
                    case 4 -> {
                        player.displayClientMessage(Component.translatable("msg.deepspacedeployer.buyer_report",
                                activeAccountName(), activeLocalBalance()), false);
                        yield true;
                    }
                    default -> false;
                };
            }
            case CORE -> switch (id) {
                // 组装费按"这台核心所属公司"结算（核心按公司分成 6 种方块）
                case MachineMenu.ACTION_ASSEMBLE -> startAssembly(serverPlayer, card);
                case 3 -> cancelAssembly(serverPlayer);
                case 4 -> reloadBlueprints(serverPlayer);
                default -> false;
            };
            // 寄售市场：挂单 / 购买 / 撤单 / 载具挂单相关，全部走 MarketPayload（见 MarketActions）
            case MARKET -> false;
        };
    }

    // ---------------- 资金转化界面（两张卡的界面） ----------------

    /**
     * 打开资金转化界面：<b>把机器卡槽里的卡搬进那个界面的第一个展示槽</b>。
     *
     * <p>为什么要把卡搬走：机器卡槽只有一个，而那个界面要两张卡（来源 / 目标）。
     * 这一步在服务端做完，界面关掉时服务端会把卡还给玩家 ——
     * 不会出现"卡在机器里、界面也拿着一份"的复制问题。</p>
     */
    public boolean openTransfer(ServerPlayer player) {
        if (!(player.containerMenu instanceof MachineMenu)) {
            return false;
        }
        final ItemStack[] handoff = new ItemStack[TransferMenu.CARD_SLOTS];
        for (int i = 0; i < TransferMenu.CARD_SLOTS; i++) {
            handoff[i] = ItemStack.EMPTY;
        }
        ItemStack inSlot = items.get(SLOT_CARD);
        if (!inSlot.isEmpty()) {
            // 机器卡槽 → 界面卡槽 1（卡由菜单保管，关界面 / 返回商店时再还回去）
            //
            // ⚠️ 这里千万不要再调 player.getInventory().add(...)：
            //    Inventory#add 会把传进去的那个栈本身改成"没塞下的剩余量"（addResource 的约定），
            //    塞下了 → 那个栈变成空栈 → 后面 copyWithCount 得到空栈（卡槽看着是空的）；
            //    塞不下 → 掉一张 + 卡槽里再放一张 = 复制。
            handoff[0] = inSlot.copyWithCount(1);
            items.set(SLOT_CARD, ItemStack.EMPTY);
            afterChange();
        }
        player.closeContainer();
        java.util.OptionalInt opened = player.openMenu(new SimpleMenuProvider(
                (windowId, inventory, p) -> {
                    TransferMenu menu = new TransferMenu(windowId, inventory, this);
                    for (int i = 0; i < TransferMenu.CARD_SLOTS; i++) {
                        menu.putCard(i, handoff[i]);
                    }
                    return menu;
                },
                Component.translatable("gui.deepspacedeployer.transfer_title")));
        if (opened.isEmpty()) {
            // 兜底：菜单没开成（正常情况下不会发生），卡还在手里那份 handoff 里，
            // 必须还回去 —— 机器卡槽这时候已经空了。
            for (ItemStack stack : handoff) {
                if (!stack.isEmpty() && !player.getInventory().add(stack.copyWithCount(1))) {
                    player.drop(stack.copyWithCount(1), false);
                }
            }
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.transfer_open_failed"), true);
            return false;
        }
        return true;
    }

    /**
     * 「返回商店」：把界面里的卡收回来（第一张卡插回机器，其余的留在玩家背包），再打开机器界面。
     */
    public boolean backToMachine(ServerPlayer player) {
        if (!(player.containerMenu instanceof TransferMenu menu)) {
            return false;
        }
        // 机器可能已经被别人拆了（这个方块实体已经不在世界里）：
        // 那就别再把卡插回"不存在的机器"，直接还给玩家。
        boolean machinePresent = level != null
                && level.getBlockEntity(worldPosition) == this
                && !isRemoved();
        for (int i = 0; i < TransferMenu.CARD_SLOTS; i++) {
            ItemStack stack = menu.takeCard(i);
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack one = stack.copyWithCount(1);
            if (i == 0 && machinePresent && items.get(SLOT_CARD).isEmpty()) {
                // 第一张卡仍然插回机器（相当于"没离开过"）
                //
                // 注意：这里的 one 不会被 Inventory#add 改（走的是 items.set），
                // 下面那两条分支里的 add 会改 one，所以都在 add 之后不再使用 one。
                items.set(SLOT_CARD, one);
            } else if (!player.getInventory().add(one)) {
                player.drop(one, false);
            }
        }
        if (machinePresent) {
            afterChange();
        }
        player.closeContainer();
        player.openMenu(new SimpleMenuProvider(
                (windowId, inventory, p) -> new MachineMenu(ModMenus.get(kind()), windowId, inventory, this),
                Component.translatable("block.deepspacedeployer." + kind().id())));
        // 菜单包之后补三包：插回去的卡、价目表、蓝图列表（和 MachineBlock#openMenu 保持一致）
        PacketDistributor.sendToPlayer(player, new TransferSyncPayload(
                List.of(items.get(SLOT_CARD).copyWithCount(1), ItemStack.EMPTY)));
        sendShopData(player);
        if (kind() == MachineKind.CORE) {
            syncToClient();
        }
        return true;
    }

    // ---------------- 工厂最近一次组装的结构 ----------------
    // 焊接台已经删除；这份记录本身还留着（存档里有），暂时没有别的读取者。

    public boolean hasLastAssembly() {
        return hasLastAssembly;
    }

    /** 蓝图凭证：直接给工厂核心选上某份蓝图（凭证由物品那边扣掉） */
    public boolean applyBlueprint(String blueprintName) {
        BlueprintInfo info = PhysicsBlueprintLibrary.get(blueprintName);
        if (info == null) {
            return false;
        }
        int max = effectiveMaxBlocks(info);
        if (info.totalBlocks() > max) {
            return false;
        }
        selectedBlueprint = info.name();
        afterChange();
        return true;
    }

    public BlockPos lastOrigin() {
        return new BlockPos(lastOriginX, lastOriginY, lastOriginZ);
    }

    // ---------------- 投放点（地图选点） ----------------

    public boolean hasTargetOverride() {
        return hasTargetOverride;
    }

    public int overrideX() {
        return overrideX;
    }

    public int overrideZ() {
        return overrideZ;
    }

    /** 设置投放点；太远就拒绝（返回 false） */
    public boolean setAssemblyTarget(int x, int z) {
        int max = Config.ASSEMBLY_MAX_RADIUS.get();
        double dx = x - (worldPosition.getX() + 0.5);
        double dz = z - (worldPosition.getZ() + 0.5);
        if (dx * dx + dz * dz > (double) max * max) {
            return false;
        }
        hasTargetOverride = true;
        overrideX = x;
        overrideZ = z;
        afterChange();
        return true;
    }

    public void clearAssemblyTarget() {
        hasTargetOverride = false;
        afterChange();
    }

    /**
     * 卡 → 卡转账（老板要求的"副卡"就靠这个充值）。
     *
     * <p>源卡扣的是<b>它自己的</b>余额池（副卡=副卡那笔独立钱，主卡=卡上的便携余额），
     * 目标卡加的是<b>它自己的</b>池子；两张卡所属的公司由 {@code company} 决定。
     * 副卡的钱不能取现、不能直接花，只能这样进来、再这样出去。</p>
     */
    public boolean transferBetweenCards(ServerPlayer player, ItemStack from, ItemStack to, long amount) {
        if (from == null || from.isEmpty() || to == null || to.isEmpty()) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.transfer_need_two_cards"), true);
            return false;
        }
        if (!CardData.hasAccount(from) || !CardData.hasAccount(to)) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.deposit_need_account"), true);
            return false;
        }
        if (from == to || ItemStack.isSameItemSameComponents(from, to)) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.transfer_same_entity"), true);
            return false;
        }
        Company company = company();
        long onCard = CardData.getBalance(from, company);
        long actual = Math.min(onCard, Math.max(1L, amount));
        if (actual <= 0L) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.transfer_source_empty",
                    company.displayName()), true);
            return false;
        }
        CardData.addBalance(from, company, -actual);
        long now = CardData.addBalance(to, company, actual);
        afterChange();
        player.displayClientMessage(Component.translatable("msg.deepspacedeployer.transferred",
                actual, company.displayName(), CardData.accountName(to), now), false);
        return true;
    }

    /**
     * 转账给另一个玩家：把本公司的余额给他（玩家之间交易的基础手段）。
     *
     * <p>票据物品已经删除，钱只有"账户数字"一套，所以给钱就靠这个：
     * 从<b>这张卡卡主</b>在这家公司的账户扣，加到对方的同名账户上（跨公司由对方自己去兑换）。
     * 目标玩家必须在这台服务器上待过（离线也行，用 usercache 查得到名字就能转）。</p>
     */
    public boolean transferTo(ServerPlayer player, ItemStack card, String targetName, long amount) {
        if (card == null || card.isEmpty()) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.need_card_in_slot"), true);
            return false;
        }
        // 给玩家的钱从"卡主的主账户"出（副卡也一样）
        UUID account = CardData.ownerAccount(card);
        Company company = company();
        if (account == null) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.deposit_need_account"), true);
            return false;
        }
        if (targetName == null || targetName.isBlank()) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.transfer_need_name"), true);
            return false;
        }
        long local = localBalance(account);
        long actual = Math.min(local, Math.max(1L, amount));
        if (actual <= 0) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.nothing_to_move", local), true);
            return false;
        }
        UUID target = findPlayerId(player, targetName.trim());
        if (target == null) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.transfer_no_player",
                    targetName.trim()), true);
            return false;
        }
        if (target.equals(account)) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.transfer_to_self"), true);
            return false;
        }
        addLocal(account, -actual);
        long now = addLocal(target, actual);
        afterChange();
        player.displayClientMessage(Component.translatable("msg.deepspacedeployer.transferred_money",
                actual, company.displayName(), targetName.trim(), now), false);
        return true;
    }

    /** 按玩家名/UUID 找玩家 id：先查在线玩家，再查服务器 usercache（离线玩家也能转） */
    private UUID findPlayerId(ServerPlayer player, String name) {
        // 1) 在线玩家（按名字，忽略大小写）
        for (ServerPlayer online : player.server.getPlayerList().getPlayers()) {
            if (online.getGameProfile().getName().equalsIgnoreCase(name)) {
                return online.getUUID();
            }
        }
        // 2) 服务器缓存里待过的玩家（离线也能转）
        try {
            java.util.Optional<com.mojang.authlib.GameProfile> cached =
                    player.server.getProfileCache() == null
                            ? java.util.Optional.empty()
                            : player.server.getProfileCache().get(name);
            if (cached.isPresent()) {
                return cached.get().getId();
            }
        } catch (Throwable ignored) {
            // 缓存查不到就当找不到
        }
        // 3) 直接给 UUID 也认
        try {
            return UUID.fromString(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 公司账户 → 这张卡自己的余额池。
     *
     * <p>"公司账户"用的是<b>卡主的主账户</b>（副卡插着也一样），
     * 加到的却是<b>这张卡自己的池子</b> —— 所以对副卡来说这就是"从主账户拨钱到副卡"。</p>
     */
    public boolean moveAccountToCard(ServerPlayer player, ItemStack card, long amount) {
        if (card == null || card.isEmpty()) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.need_card_in_slot"), true);
            return false;
        }
        UUID account = CardData.ownerAccount(card);
        Company company = company();
        if (account == null) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.deposit_need_account"), true);
            return false;
        }
        long local = localBalance(account);
        long actual = Math.min(local, Math.max(1L, amount));
        if (actual <= 0) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.nothing_to_move",
                    local), true);
            return false;
        }
        addLocal(account, -actual);
        CardData.addBalance(card, company, actual);
        afterChange();
        player.displayClientMessage(Component.translatable("msg.deepspacedeployer.moved_to_card",
                actual, company.displayName(), CardData.getBalance(card, company)), false);
        return true;
    }

    /**
     * 这张卡自己的余额池 → 公司账户（主账户）。
     *
     * <p>副卡的钱就是这样"划回主账户"的 —— 副卡不能直接花、也不能取现。</p>
     */
    public boolean moveCardToAccount(ServerPlayer player, ItemStack card, long amount) {
        if (card == null || card.isEmpty()) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.need_card_in_slot"), true);
            return false;
        }
        UUID account = CardData.ownerAccount(card);
        Company company = company();
        if (account == null) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.deposit_need_account"), true);
            return false;
        }
        long onCard = CardData.getBalance(card, company);
        long actual = Math.min(onCard, Math.max(1L, amount));
        if (actual <= 0) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.nothing_to_move", onCard), true);
            return false;
        }
        CardData.addBalance(card, company, -actual);
        long now = addLocal(account, actual);
        afterChange();
        player.displayClientMessage(Component.translatable("msg.deepspacedeployer.moved_from_card",
                actual, company.displayName(), now), false);
        return true;
    }

    /**
     * 兑换（可以指定目标公司）：把卡上其它公司的便携余额全部换过去，扣手续费。
     *
     * <p>「资金转化界面」有两张卡，目标卡要换成本公司，所以这里把"目标公司"做成参数。</p>
     */
    public boolean exchangeOn(ServerPlayer player, ItemStack card, Company target) {
        if (card == null || card.isEmpty()) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.need_card_in_slot"), true);
            return false;
        }
        long[] balances = CardData.getBalances(card);
        long moved = 0L;
        for (Company other : Company.VALUES) {
            if (other != target) {
                moved += balances[other.ordinal()];
                balances[other.ordinal()] = 0L;
            }
        }
        if (moved <= 0L) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.nothing_to_exchange"), true);
            return false;
        }
        int feePercent = Config.CROSS_COMPANY_EXCHANGE_FEE.get();
        long fee = moved * feePercent / 100L;
        long gained = moved - fee;
        balances[target.ordinal()] = balances[target.ordinal()] + gained;
        CardData.setBalances(card, balances);
        afterChange();
        player.displayClientMessage(Component.translatable("msg.deepspacedeployer.exchanged",
                moved, gained, fee, target.displayName()), false);
        return true;
    }

    // ---------------- 存档 / 同步 ----------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        // 放置动画是否播过（由客户端回报后置位）—— 这是"要不要播放置动画"的唯一依据
        tag.putBoolean("PlaceAnimDone", placeAnimDone);
        // 商店：有没有玩家在 2 格内（服务端权威，客户端照着播展开/闭合）
        tag.putBoolean("AnyoneNear", anyoneNear);
        tag.putInt("TotalSold", totalSold);
        // ⚠️ animPhase **故意不存 NBT**：它是纯客户端渲染状态。
        //    存了的话，每次收到方块实体同步包 → loadAdditional → 阶段被重置回 PENDING，
        //    计时器 phaseStartMs 跟着归零 → 永远播不完、永远进不了下一阶段。
        //    （实测现象：日志里「起始」一直跟着「elapsed」一起涨，"差"永远只有几十毫秒。）
        ContainerHelper.saveAllItems(tag, items, registries);

        // 余额是世界级存档（CompanyFunds），不再写进方块 NBT
        tag.putInt(NBT_COMPANY, companyIndex);
        if (kind() == MachineKind.BUYER) {
            tag.putString(NBT_BUYER_COMPANY, company().id());
        }
        tag.put("ShopStock", writeShopStock());
        tag.putInt("ShopRestockTicks", shopRestockTicks);
        tag.putBoolean(NBT_AUTO_SELL, autoSell);
        tag.putInt(NBT_TIER, tierIndex);

        tag.putString(NBT_BLUEPRINT, selectedBlueprint);
        tag.put(NBT_MATERIALS, writeCounts(materials));
        tag.put(NBT_RESERVED, writeCounts(reserved));
        tag.putInt(NBT_ASSEMBLY_TICKS, assemblyTicks);
        tag.putInt(NBT_ASSEMBLY_TOTAL, assemblyTotal);
        if (hasTargetOverride) {
            CompoundTag override = new CompoundTag();
            override.putInt("x", overrideX);
            override.putInt("z", overrideZ);
            tag.put(NBT_TARGET_OVERRIDE, override);
        }
        if (hasLastAssembly) {
            CompoundTag last = new CompoundTag();
            last.putInt("ox", lastOriginX);
            last.putInt("oy", lastOriginY);
            last.putInt("oz", lastOriginZ);
            last.putInt("sx", lastSizeX);
            last.putInt("sy", lastSizeY);
            last.putInt("sz", lastSizeZ);
            tag.put(NBT_LAST_ASSEMBLY, last);
        }
        if (hasTarget) {
            CompoundTag target = new CompoundTag();
            target.putInt("x", targetX);
            target.putInt("y", targetY);
            target.putInt("z", targetZ);
            tag.put(NBT_TARGET, target);
        }
    }

    /**
     * 机器被拆掉时把里面的东西全部吐出来（掉落表只负责方块本身，这里负责内容物）。
     *
     * <p>吐出来的有：9 个投料格、银行卡、材料缓冲、预留材料，
     * 以及<b>存在方块里的各玩家余额</b>（按本公司票据掉出来，谁捡到算谁的 ——
     * 总比直接蒸发掉好）。</p>
     */
    public void dropEverything() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                Block.popResource(serverLevel, worldPosition, stack);
            }
        }
        dropCounts(serverLevel, materials);
        dropCounts(serverLevel, reserved);
        materials.clear();
        reserved.clear();
        // 余额在世界存档里，拆机器不会丢（这是改成公司账户之后白捡的好处）

        for (int i = 0; i < items.size(); i++) {
            items.set(i, ItemStack.EMPTY);
        }
        selectedBlueprint = "";
        setChanged();
    }

    /** 把"物品 id → 数量"的缓冲还原成物品掉出来 */
    private void dropCounts(ServerLevel serverLevel, Map<String, Integer> counts) {
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            ResourceLocation id = ResourceLocation.tryParse(entry.getKey());
            if (id == null) {
                continue;
            }
            Item item = BuiltInRegistries.ITEM.get(id);
            if (item == Items.AIR) {
                continue;
            }
            int left = entry.getValue() == null ? 0 : entry.getValue();
            while (left > 0) {
                int give = Math.min(64, left);
                Block.popResource(serverLevel, worldPosition, new ItemStack(item, give));
                left -= give;
            }
        }
    }

    private static CompoundTag writeCounts(Map<String, Integer> counts) {        CompoundTag tag = new CompoundTag();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                tag.putInt(entry.getKey(), entry.getValue());
            }
        }
        return tag;
    }

    private static void readCounts(CompoundTag tag, Map<String, Integer> out) {
        out.clear();
        for (String key : tag.getAllKeys()) {
            int value = tag.getInt(key);
            if (value > 0) {
                out.put(key, value);
            }
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        // 放置动画"是否已经播过" —— 只由**服务端**置位（客户端播完发回执）。
        // 客户端据此决定要不要播放置动画。
        //
        // ⚠️ 这里**不要**恢复 animPhase 本身：同步包里的值总是服务端那份（它不知道
        //    客户端播到哪了），逐字恢复会把客户端的进度打回去，动画就会反复重播。
        placeAnimDone = tag.getBoolean("PlaceAnimDone");
        // 商店：玩家是否在附近（同步包/存档带过来；客户端据此播展开或闭合）
        anyoneNear = tag.getBoolean("AnyoneNear");
        totalSold = tag.getInt("TotalSold");
        // ⚠️ 这里**不要**恢复 animPhase（见 saveAdditional 的注释）。
        //    阶段由客户端自己决定：刚放下 → 从 PENDING 开始播放置；
        //    已经用过（totalSold > 0）→ 跳过放置，直接进入"只播投送"。
        items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, items, registries);

        companyIndex = tag.getInt(NBT_COMPANY);
        buyerCompany = tag.contains(NBT_BUYER_COMPANY)
                ? Company.byId(tag.getString(NBT_BUYER_COMPANY))
                : null;
        readShopStock(tag.getCompound("ShopStock"));
        shopRestockTicks = tag.getInt("ShopRestockTicks");
        autoSell = !tag.contains(NBT_AUTO_SELL) || tag.getBoolean(NBT_AUTO_SELL);
        tierIndex = tag.contains(NBT_TIER) ? tag.getInt(NBT_TIER) : Tier.HIGH.rank();

        selectedBlueprint = tag.getString(NBT_BLUEPRINT);
        readCounts(tag.getCompound(NBT_MATERIALS), materials);
        readCounts(tag.getCompound(NBT_RESERVED), reserved);
        assemblyTicks = tag.getInt(NBT_ASSEMBLY_TICKS);
        assemblyTotal = tag.getInt(NBT_ASSEMBLY_TOTAL);
        hasTargetOverride = tag.contains(NBT_TARGET_OVERRIDE);
        if (hasTargetOverride) {
            CompoundTag override = tag.getCompound(NBT_TARGET_OVERRIDE);
            overrideX = override.getInt("x");
            overrideZ = override.getInt("z");
        }
        hasLastAssembly = tag.contains(NBT_LAST_ASSEMBLY);
        if (hasLastAssembly) {
            CompoundTag last = tag.getCompound(NBT_LAST_ASSEMBLY);
            lastOriginX = last.getInt("ox");
            lastOriginY = last.getInt("oy");
            lastOriginZ = last.getInt("oz");
            lastSizeX = last.getInt("sx");
            lastSizeY = last.getInt("sy");
            lastSizeZ = last.getInt("sz");
        }
        hasTarget = tag.contains(NBT_TARGET);
        if (hasTarget) {
            CompoundTag target = tag.getCompound(NBT_TARGET);
            targetX = target.getInt("x");
            targetY = target.getInt("y");
            targetZ = target.getInt("z");
        }

        if (tag.contains(NBT_BLUEPRINT_LIST, Tag.TAG_LIST)) {
            ListTag list = tag.getList(NBT_BLUEPRINT_LIST, Tag.TAG_COMPOUND);
            List<BlueprintInfo> parsed = new java.util.ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                parsed.add(BlueprintInfo.fromNbt(list.getCompound(i)));
            }
            blueprintSnapshot = List.copyOf(parsed);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        // 顺带把蓝图列表发给客户端（界面要显示）
        List<BlueprintInfo> list = (level != null && !level.isClientSide)
                ? PhysicsBlueprintLibrary.all() : blueprintSnapshot;
        ListTag listTag = new ListTag();
        for (BlueprintInfo info : list) {
            listTag.add(info.toNbt());
        }
        tag.put(NBT_BLUEPRINT_LIST, listTag);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // ---------------- Container / WorldlyContainer ----------------

    @Override
    public int getContainerSize() {
        return SLOT_COUNT;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return items.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack result = ContainerHelper.removeItem(items, slot, amount);
        if (!result.isEmpty()) {
            afterChange();
        }
        return result;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        items.set(slot, stack);
        afterChange();
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        items.clear();
    }

    // 只有收购站对外暴露输入格；银行卡格永远不给外部拿
    @Override
    public int[] getSlotsForFace(Direction side) {
        if (kind() != MachineKind.BUYER) {
            return new int[0];
        }
        int[] slots = new int[INPUT_SLOTS];
        for (int i = 0; i < INPUT_SLOTS; i++) {
            slots[i] = SLOT_INPUT_START + i;
        }
        return slots;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction side) {
        return kind() == MachineKind.BUYER && slot >= SLOT_INPUT_START;
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
        return false;
    }

    /**
     * 卡槽里的卡能不能被拿走（老板要求：只有卡主本人，创造模式随便拿）。
     *
     * <p>别人借卡来用没问题 —— 花钱和收款都走卡主账户；但卡本身不能被"顺手拿走",
     * 否则"借"就等于"给"了。创造模式留个口子，方便调试和管理员回收。</p>
     *
     * @param taker 想拿卡的人（可能是任何打开这台机器界面的玩家）
     */
    public boolean canPickUpCard(Player taker) {
        ItemStack stored = items.get(SLOT_CARD);
        if (stored.isEmpty() || taker == null) {
            return true;
        }
        if (taker.isCreative()) {
            return true;
        }
        return CardData.isBoundTo(stored, taker);
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        if (slot == SLOT_CARD) {
            return ModItems.isBankCard(stack);
        }
        if (slot == SLOT_CLIPBOARD) {
            // 只收机械动力的剪贴板（没有 Create 时这个格子永远空着）
            return CreateClipboardBridge.isClipboard(stack);
        }
        return kind() == MachineKind.BUYER;
    }

    /** 给机械手 / 管道用的物品接口（只暴露收购站的输入格） */
    public IItemHandler itemHandler() {
        return itemHandler;
    }

    // ---------------- 寄售市场 ----------------

    /** 市场挂单存档；拿不到服务器就返回 null */
    public com.deepspacedeployer.market.MarketData marketData() {
        MinecraftServer server = server();
        return server == null ? null : com.deepspacedeployer.market.MarketData.get(server);
    }

    /** 给市场逻辑用的公司账户存档 */
    public CompanyFunds fundsOrNull() {
        return funds();
    }

    public ItemStack sellSlot() {
        return items.get(SLOT_MARKET_SELL);
    }

    public void setSellSlot(ItemStack stack) {
        items.set(SLOT_MARKET_SELL, stack == null ? ItemStack.EMPTY : stack);
        afterChange();
    }

    public ItemStack barterSlot() {
        return items.get(SLOT_MARKET_BARTER);
    }

    public ItemStack collectSlot() {
        return items.get(SLOT_MARKET_COLLECT);
    }

    public void setCollectSlot(ItemStack stack) {
        items.set(SLOT_MARKET_COLLECT, stack == null ? ItemStack.EMPTY : stack);
        afterChange();
    }

    /** 挂单变了：立刻推给附近玩家（界面自己会重建列表） */
    public void bumpMarketVersion() {
        marketVersion++;
        afterChange();
    }

    public int marketVersion() {
        return marketVersion;
    }

    /** 打开界面时直接把挂单列表推给这个玩家（不用等 tick 那一轮） */
    public void pushMarketNow(ServerPlayer player) {
        com.deepspacedeployer.market.MarketData data = marketData();
        if (data == null) {
            return;
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                new MarketListPayload(data.listings()));
    }

    /** 把当前挂单列表推给附近玩家 */
    public void sendMarketToNearby() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        com.deepspacedeployer.market.MarketData data = marketData();
        if (data == null) {
            return;
        }
        MarketListPayload payload = new MarketListPayload(data.listings());
        for (ServerPlayer player : serverLevel.players()) {
            if (player.blockPosition().distSqr(worldPosition) <= 64.0D * 64.0D) {
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    private final class MachineItemHandler implements IItemHandler {

        @Override
        public int getSlots() {
            return kind() == MachineKind.BUYER ? INPUT_SLOTS : 0;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return getSlots() == 0 ? ItemStack.EMPTY : items.get(SLOT_INPUT_START + slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (getSlots() == 0 || stack.isEmpty()) {
                return stack;
            }
            ItemStack existing = items.get(SLOT_INPUT_START + slot);
            int limit = 64;
            if (!existing.isEmpty()) {
                if (!ItemStack.isSameItemSameComponents(existing, stack)) {
                    return stack;
                }
                limit -= existing.getCount();
            }
            if (limit <= 0) {
                return stack;
            }
            int moved = Math.min(limit, stack.getCount());
            if (!simulate) {
                if (existing.isEmpty()) {
                    items.set(SLOT_INPUT_START + slot, stack.copyWithCount(moved));
                } else {
                    existing.grow(moved);
                }
                afterChange();
            }
            ItemStack remainder = stack.copy();
            remainder.shrink(moved);
            return remainder;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            // 收购站只进不出
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 64;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return getSlots() > 0;
        }
    }

    // ================= 收购投送机：动画 =================

    /** 「放置」动画（只在第一次放下时播） */
    public static final String ANIM_PLACE = "放置";
    /** 「投送」动画（之后每次卖掉东西播一次） */
    public static final String ANIM_DELIVER = "投送";
    /** 两个控制器各管一个动画 —— 物理上不会串轨 */
    public static final String CTRL_PLACE = "place";
    public static final String CTRL_DELIVER = "deliver";

    /** 动画阶段：记在 NBT 里，退出重进不会重播「放置」 */
    public enum AnimPhase {
        /** 还没播「放置」 */
        PENDING,
        /** 正在播「放置」 */
        PLAYING_PLACE,
        /** 「放置」播完了，等第一次投送 */
        WAITING_FIRST_DELIVER,
        /** 正在播「投送」 */
        DELIVERING,
        /** 已经在投送阶段：之后**只播投送** */
        WAITING_DELIVER
    }

    private AnimPhase animPhase = AnimPhase.PENDING;
    /**
     * 放置动画是否已经播过。
     *
     * <p>由<b>服务端</b>持有（存 NBT），客户端播完动画后发回执置位。
     * 客户端以此决定"要不要播放置动画" —— 这样重复同步不会让动画重播。</p>
     */
    private boolean placeAnimDone;
    /** 客户端：加载后经过的 tick 数（由墙上时钟算出来，见 clientAnimTick） */
    private int animTicks;
    /**
     * 方块实体"年龄"（tick）—— 给 GeckoLib 当动画时间轴用。
     *
     * <p>见 {@link #getTick}：不能用世界总时间（动画会被一步跳到结尾），
     * 也不能用 tick 计数（帧率/ticker 都不可靠）。由 {@code System.nanoTime()} 换算。</p>
     */
    private double animAgeTicks;
    /** 动画时间原点（首次渲染时的 nanoTime） */
    private long animOriginNs;
    /** 上一次真正推进阶段的时刻（去重用，避免同帧被调多次） */
    private long lastAnimTickNs;
    /**
     * 兜底标志：本实例是否已经**无条件**播过一次放置动画。
     *
     * <p>见 {@code clientAnimTick} 里的"首次渲染兜底" —— 不看阶段、不看同步，
     * 第一次被渲染到就播一次。这样能彻底绕开"阶段被同步冲掉"这类问题。</p>
     */
    private boolean placeAnimTriggered;
    /** 进入 PLAYING_PLACE 的时刻（毫秒）—— 兜底：万一动画没启动也不会卡死 */
    private long animArrivedAtMs;
    /** 当前动画阶段的起始时刻（毫秒，相对动画原点） */
    private long phaseStartMs;
    /** 「放置」动画时长（毫秒）= 0.75 秒 */
    private static final long PLACE_MS = 800L;
    /** 「投送」动画时长（毫秒）—— 老板把动画改成了 0.5 秒，这里跟着改 */
    private static final long DELIVER_MS = 550L;
    /** 服务端：两次「投送」广播之间至少隔多少毫秒（和动画时长对齐） */
    private static final long SOLD_ANIM_COOLDOWN_MS = 400L;
    /**
     * 兜底超时（毫秒）：万一客户端的「播完了」回执丢了，
     * 超过这个时间也放行，避免收购永久卡死。
     *
     * <p>= 动画时长（0.5 秒）× 3，给客户端卡顿留足余量。</p>
     */
    private static final long ANIM_FALLBACK_MS = 1500L;
    /** 服务端：上次广播「投送」的时刻（毫秒，System.nanoTime） */
    private long lastSoldAnimNs;
    /** 服务端：客户端是否已回报「上一轮动画播完了」（这就是收下一轮的许可） */
    private boolean animAckReady = true;
    /** 客户端：播放阶段收到的售卖请求（等当前动画播完补播一次） */
    private boolean pendingSold;
    /** 诊断用：控制器被调用的次数 */
    private int placeDiag;
    private int deliverDiag;
    /** 诊断用：阶段推进被调用的次数 */
    private int diagTick;
    /**
     * 累计卖出次数（服务端维护，随方块实体同步到客户端）。
     *
     * <p><b>这是「投送」动画的触发源</b>：客户端每帧比较它是否变化。
     * 不用自定义网络包 —— 方块实体的更新包由原版负责同步，可靠得多。</p>
     */
    private int totalSold;
    /** 客户端：上次看到的卖出次数 */
    private int lastSeenSold = -1;

    /**
     * 两个动画都**播完即止**（{@code PLAY_ONCE}）。
     *
     * <p>老板的新模型：<b>固定姿势就是完全展开</b>，动画的最后一帧本来就等于固定姿势，
     * 所以播完让 GeckoLib 回到默认姿势即可 —— 视觉上"展开后就停在那里"。</p>
     *
     * <p>⚠️ 注意这两个是**静态常量**（同一个对象）。GeckoLib 在"动画对象没变"时
     * <b>不会重新开始播</b> —— 所以每次都要额外调 {@code triggerAnim()} 强制重启，
     * 否则会出现"只播了第一次"的现象。</p>
     */
    private static final software.bernie.geckolib.animation.RawAnimation PLACE_ANIM =
            software.bernie.geckolib.animation.RawAnimation.begin().then(
                    ANIM_PLACE, software.bernie.geckolib.animation.Animation.LoopType.PLAY_ONCE);
    private static final software.bernie.geckolib.animation.RawAnimation DELIVER_ANIM =
            software.bernie.geckolib.animation.RawAnimation.begin().then(
                    ANIM_DELIVER, software.bernie.geckolib.animation.Animation.LoopType.PLAY_ONCE);

    private final software.bernie.geckolib.animatable.instance.AnimatableInstanceCache animCache =
            new software.bernie.geckolib.animatable.instance.SingletonAnimatableInstanceCache(this);

    /** 本方块是不是收购投送机（只有它做动画） */
    public boolean isBuyer() {
        return kind() == MachineKind.BUYER;
    }

    // ---------- 两个控制器（互不相干，所以永远不会播错） ----------

    @Override
    public void registerControllers(
            software.bernie.geckolib.animation.AnimatableManager.ControllerRegistrar controllers) {
        // A：「放置」——只在 PLAYING_PLACE 阶段返回动画
        //
        // 分工：**状态处理器**决定"这一刻该不该有动画"；
        //      **AnimationController.forceAnimationReset()** 决定"从头开始播"（见 forceRestart）。
        //
        // ⚠️ 这里**不要**注册 .triggerableAnim(...)：那属于"触发式动画"，
        //    它一播完 GeckoLib 就会置 needsAnimationReload 再回头问状态处理器，
        //    同一个动画会被立刻重建、从头再播一遍（"播两次"的元凶，见 forceRestart 注释）。
        controllers.add(new software.bernie.geckolib.animation.AnimationController<>(
                this, CTRL_PLACE, 0, state -> {
            // ⚠️ 关键：**在这里推进动画阶段**。
            //
            // 控制器回调是 GeckoLib 每帧必调的（渲染管线自己的心跳），
            // 而方块 ticker / 渲染器覆盖那几个入口都出现过"没走到"的情况
            // （诊断日志见过 原点=0、阶段永远 PENDING 的机器）。
            // clientAnimTick() 是幂等的，多调几次无害。
            clientAnimTick();

            return animPhase == AnimPhase.PLAYING_PLACE
                    ? state.setAndContinue(PLACE_ANIM)
                    : software.bernie.geckolib.animation.PlayState.STOP;
        }));

        // B：「投送」——只在 DELIVERING 阶段返回动画（同上，不注册触发式动画）
        controllers.add(new software.bernie.geckolib.animation.AnimationController<>(
                this, CTRL_DELIVER, 0, state -> {
            clientAnimTick();        // 同上：阶段推进挂在这条心跳上

            return animPhase == AnimPhase.DELIVERING
                    ? state.setAndContinue(DELIVER_ANIM)
                    : software.bernie.geckolib.animation.PlayState.STOP;
        }));

        // C：「展开 / 闭合」—— 星联动力联盟商店专用（玩家靠近展开、离开闭合）
        //
        // ⚠️ 只给商店注册：别的机器一个字节都不参与（隔离原则，铁律 14）。
        //    逻辑全在 TerminalShopAnim 里：它管"什么时候播、播完才许切换"，
        //    这里只负责每帧给它一次心跳 + 问它"这一刻该播哪个动画"。
        if (kind() == MachineKind.TERMINAL) {
            controllers.add(new software.bernie.geckolib.animation.AnimationController<>(
                    this, com.deepspacedeployer.client.gecko.TerminalShopAnim.CTRL, 0, state -> {
                com.deepspacedeployer.client.gecko.TerminalShopAnim anim = shopAnim();
                anim.tick();
                return anim.playState(state);
            }));
        }
    }

    // ---------- 客户端 tick ----------

    /**
     * 推进动画阶段 —— <b>由渲染器每帧调用</b>。
     *
     * <p>⚠️ <b>这里刻意不用 tick 计数</b>，理由（踩了很久才明白）：</p>
     * <ul>
     *   <li>渲染帧率远高于 20，用"游戏 tick"做节流会丢帧；
     *       实测 {@code animPlayed} 在 1~7 之间反复回退，永远到不了 18，
     *       于是阶段永远停在"正在播放置"，控制器一直返回动画却从未被启动（状态恒为 STOPPED）。</li>
     *   <li>方块实体的 {@code getTicker} 也不保证每 tick 被调用。</li>
     * </ul>
     *
     * <p>所以改成<b>墙上时钟</b>：用 {@code System.nanoTime()} 算真实经过的毫秒数。
     * 它每帧都在走、不受帧率与 ticker 影响，是唯一稳定的时间源。</p>
     */
    public void clientAnimTick() {
        if (level == null || !level.isClientSide || !isBuyer()) {
            return;
        }
        long nowNs = System.nanoTime();
        // 同一帧被调多次时，只有第一次真正推进（按帧时间戳去重）
        if (nowNs - lastAnimTickNs < 1_000_000L) {
            return;
        }
        lastAnimTickNs = nowNs;

        // 1) 首次调用：定下动画时间原点
        if (animOriginNs == 0L) {
            animOriginNs = nowNs;
            // 这台机器的放置动画已经播过（或已经卖过东西）→ 直接进入"只播投送"。
            // 用来区分"刚放下"和"退出重进/别人放的老机器"。
            if (placeAnimDone || totalSold > 0) {
                animPhase = AnimPhase.WAITING_DELIVER;
            }
        }

        // 给 GeckoLib 的时间轴：真实经过的 tick 数（每 50 ms 一个 tick）
        animAgeTicks = (nowNs - animOriginNs) / 50_000_000.0D;

        long elapsedMs = (nowNs - animOriginNs) / 1_000_000L;

        // ★ 方案 B：首次渲染兜底 ★
        //
        // 不走"阶段判定"、不等任何同步 —— 只要本实例还没播过放置动画，
        // 第一次被渲染到就直接播。这样"阶段被同步冲掉 / 原点没被推进"这类
        // 问题全都不影响它。
        if (!placeAnimTriggered) {
            placeAnimTriggered = true;
            if (!placeAnimDone && totalSold == 0) {
                animPhase = AnimPhase.PLAYING_PLACE;
                phaseStartMs = elapsedMs;
                animArrivedAtMs = elapsedMs;      // 供状态处理器判断"动画是否真跑过"
                animArrivedAtMs = elapsedMs;
                forceRestart(CTRL_PLACE, ANIM_PLACE);
                com.deepspacedeployer.DeepSpaceDeployer.LOGGER.info(
                        "[深空铸造体][动画] 首次渲染兜底 → 播放「{}」（位置={} elapsed={} 实例={}）",
                        ANIM_PLACE, worldPosition, elapsedMs,
                        Integer.toHexString(System.identityHashCode(this)));
            }
        }

        // 0) 卖出检测：以**服务端信号**为准（totalSold 由服务端累加并随方块更新同步）。
        //
        //    ⚠️ 不能用"格子空了"来判断 —— 玩家把东西拿走也会让格子变空，会误触发。
        if (lastSeenSold < 0) {
            lastSeenSold = totalSold;          // 首次先对齐，避免一进游戏就补播
        } else if (totalSold != lastSeenSold) {
            lastSeenSold = totalSold;
            onBuyerSold();
        }

        switch (animPhase) {
            case PENDING -> {
                // 放下后 100 ms 开始播「放置」（等渲染器就绪）
                if (elapsedMs >= 100L) {
                    animPhase = AnimPhase.PLAYING_PLACE;
                    phaseStartMs = elapsedMs;
                    com.deepspacedeployer.DeepSpaceDeployer.LOGGER.info(
                            "[深空铸造体][动画诊断] PENDING → PLAYING_PLACE（位置={} 种类={} elapsed={}）",
                            worldPosition, kind(), elapsedMs);
                    forceRestart(CTRL_PLACE, ANIM_PLACE);   // 强制重启，保证一定播
                }
            }
            case PLAYING_PLACE -> {
                // 「放置」0.75 秒
                if (diagTick++ % 40 == 0) {
                    com.deepspacedeployer.DeepSpaceDeployer.LOGGER.info(
                            "[深空铸造体][动画诊断] PLAYING_PLACE: elapsed={} 起始={} 需={} 差={} 阶段={}",
                            elapsedMs, phaseStartMs, PLACE_MS, elapsedMs - phaseStartMs, animPhase);
                }
                if (elapsedMs - phaseStartMs >= PLACE_MS) {
                    if (pendingSold) {
                        // 放置期间就卖掉了 → 播完立刻补播「投送」
                        animPhase = AnimPhase.DELIVERING;
                        phaseStartMs = elapsedMs;
                        pendingSold = false;
                        com.deepspacedeployer.DeepSpaceDeployer.LOGGER.info(
                                "[深空铸造体][动画] 「{}」播完 → 补播「{}」（放置期间有售卖）",
                                ANIM_PLACE, ANIM_DELIVER);
                    } else {
                        animPhase = AnimPhase.WAITING_FIRST_DELIVER;
                        sendPlaceAnimDone();     // 回报服务端：放置动画播过了，别再让我播
                        com.deepspacedeployer.DeepSpaceDeployer.LOGGER.info(
                                "[深空铸造体][动画] 「{}」播放完毕（{} ms），回到固定姿势",
                                ANIM_PLACE, PLACE_MS);
                    }
                }
            }
            case DELIVERING -> {
                // 「投送」0.5 秒 —— 播完就回执给服务端，服务端才收下一轮
                if (elapsedMs - phaseStartMs >= DELIVER_MS) {
                    com.deepspacedeployer.DeepSpaceDeployer.LOGGER.info(
                            "[深空铸造体][投送时序] 播完：从进入 DELIVERING 到现在 {}ms（应≈{}）",
                            elapsedMs - animArrivedAtMs, DELIVER_MS);
                    animPhase = AnimPhase.WAITING_DELIVER;
                    pendingSold = false;
                    sendAnimDone();
                    com.deepspacedeployer.DeepSpaceDeployer.LOGGER.info(
                            "[深空铸造体][动画] 「{}」播放完毕（{} ms）→ 已通知服务端收下一轮",
                            ANIM_DELIVER, DELIVER_MS);
                }
            }
            default -> {
            }
        }
    }

    // ---------- 服务端广播 / 客户端接收 ----------

    /**
     * 服务端：卖掉一批东西之后调用，广播给附近客户端播「投送」。
     *
     * <p>老板的要求：卖掉 → 播一次「投送」→ <b>等这次播完</b>才允许下一次；
     * 没检测到新东西就不播。</p>
     *
     * <p>⚠️ <b>必须限流</b>：收购站默认每 10 tick 结算一批，若每批都发包，
     * 而动画只有 0.2 秒，客户端会被淹没、大部分包被丢弃，看起来就是"没有动画"。
     * 这里用<b>墙上时钟</b>限流（每 {@value #SOLD_ANIM_COOLDOWN_MS} 毫秒最多一次），
     * 和动画时长对齐 —— 节奏就是「卖掉 → 播动画 → 播完 → 再检测」。</p>
     */
    public void notifySold() {
        if (level == null || level.isClientSide || !isBuyer()) {
            return;
        }
        // 累计卖出次数 ++ —— 这个值会随方块实体的更新包自动同步到客户端，
        // 客户端靠"次数变了"来播「投送」动画（比自定义网络包可靠得多）。
        totalSold++;
        setChanged();
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            serverLevel.getChunkSource().blockChanged(worldPosition);
        }
        long nowNs = System.nanoTime();
        if (lastSoldAnimNs != 0L && (nowNs - lastSoldAnimNs) / 1_000_000L < SOLD_ANIM_COOLDOWN_MS) {
            return;      // 限流只影响"额外"的广播，不影响 totalSold
        }
        lastSoldAnimNs = nowNs;
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingChunk(
                (net.minecraft.server.level.ServerLevel) level,
                new net.minecraft.world.level.ChunkPos(worldPosition),
                new com.deepspacedeployer.net.BuyerSoldPayload(worldPosition));
    }

    /**
     * 客户端：收到「卖掉了」的广播。
     *
     * <p>不在可播阶段时**记一个待播标记**（而不是丢弃）——
     * 等「放置」播完 / 上一次「投送」播完后立刻补播一次。
     * 这样"排到放置动画之后再触发"是自动成立的。</p>
     */
    public void onBuyerSold() {
        if (level == null || !level.isClientSide || !isBuyer()) {
            return;
        }
        // 正在播 / 放置没过 → 忽略（**不排队**）。
        // 不排队是关键：排队会导致"连着播两次"，那正是"收购频率和动画不匹配"的来源。
        if (animPhase == AnimPhase.DELIVERING
                || animPhase == AnimPhase.PENDING
                || animPhase == AnimPhase.PLAYING_PLACE) {
            com.deepspacedeployer.DeepSpaceDeployer.LOGGER.info(
                    "[深空铸造体][投送时序] 成交信号被忽略（阶段={} 本阶段已过={}ms）",
                    animPhase, animArrivedAtMs < 0L ? -1L
                            : (System.nanoTime() - animOriginNs) / 1_000_000L - animArrivedAtMs);
            return;
        }
        animPhase = AnimPhase.DELIVERING;
        phaseStartMs = animOriginNs == 0L ? 0L : (System.nanoTime() - animOriginNs) / 1_000_000L;
        animArrivedAtMs = phaseStartMs;
        pendingSold = false;
        forceRestart(CTRL_DELIVER, ANIM_DELIVER);
        com.deepspacedeployer.DeepSpaceDeployer.LOGGER.info(
                "[深空铸造体][动画] 收到售卖广播 → 播「{}」（当前阶段={}）",
                ANIM_DELIVER, animPhase);
    }

    /**
     * 强制把某个动画<b>从头</b>播一次。
     *
     * <p>⚠️ <b>不要用 GeckoLib 的 {@code triggerAnim()} / {@code tryTriggerAnimation()}</b>。</p>
     *
     * <p>理由一（老坑）：{@code triggerAnim(controller, anim)} 在客户端是空操作，见下。</p>
     * <pre>
     *   客户端分支： getAnimatableInstanceCache().getManagerForId(0L).tryTriggerAnimation(...)
     *   服务端分支： GeckoLibNetworking.triggerBlockEntityAnim(...)
     * </pre>
     *
     * <p>理由二（2026-10-15 才查清，<b>"播两次"的元凶</b>）：{@code tryTriggerAnimation} 登记的是
     * "<b>触发式动画</b>"，而触发式动画一播完，{@code handleAnimationState} 会做两件事
     * （{@code javap -p -c} 看字节码，偏移 0~85）：</p>
     * <pre>
     *   triggeredAnimation = null;
     *   needsAnimationReload = true;              // ★ 置位
     *   return getStateHandler().handle(state);   // ★ 然后【照样】回头问状态处理器
     * </pre>
     * <p>而此刻我们的相位还没结束（「放置」相位 800ms &gt; 动画 750ms），状态处理器仍然返回
     * {@code setAndContinue(同一个动画)}；{@code setAnimation} 一看
     * {@code needsAnimationReload == true} 就<b>不早退</b>（偏移 21~36），
     * 于是重建队列、{@code shouldResetTick = true} —— <b>动画从头又播一遍</b>。</p>
     *
     * <p><b>正确做法</b>：不产生"触发式动画"，只把控制器的
     * {@code needsAnimationReload} 置位，让状态处理器自己把动画重建一次：</p>
     * <pre>
     *   AnimationController.forceAnimationReset()   // 方法体就一句：needsAnimationReload = true
     *   // 下一帧 setAnimation() 重建队列并把该标志清回 false（偏移 93~95）→ 只重建一次
     * </pre>
     * <p>这样相位期间每帧的 {@code setAndContinue} 都会早退（动画不被打断），
     * 动画自然播完后停下、回到固定姿势，<b>不会再被"请求第二次"</b>。</p>
     */
    public void forceRestart(String controllerName, String animName) {
        try {
            var manager = animCache.getManagerForId(instanceId());
            if (manager == null) {
                com.deepspacedeployer.DeepSpaceDeployer.LOGGER.warn(
                        "[深空铸造体][动画] 取不到动画管理器（instanceId={}），无法重启动画",
                        instanceId());
                return;
            }
            var controller = manager.getAnimationControllers().get(controllerName);
            if (controller == null) {
                com.deepspacedeployer.DeepSpaceDeployer.LOGGER.warn(
                        "[深空铸造体][动画] 取不到控制器 {}，无法重启动画", controllerName);
                return;
            }
            controller.forceAnimationReset();
            com.deepspacedeployer.DeepSpaceDeployer.LOGGER.info(
                    "[深空铸造体][投送时序] forceRestart({}, {}) → forceAnimationReset() 阶段={} 本阶段已过={}ms",
                    controllerName, animName, animPhase,
                    animArrivedAtMs < 0L ? -1L
                            : (System.nanoTime() - animOriginNs) / 1_000_000L - animArrivedAtMs);
        } catch (Throwable t) {
            com.deepspacedeployer.DeepSpaceDeployer.LOGGER.warn(
                    "[深空铸造体][动画] 强制重启动画失败：{}", t.toString());
        }
    }

    /**
     * 服务端：客户端报告"放置动画播完了" → 置位并存进 NBT。
     *
     * <p>置位之后，服务端下发的同步包里就带着 {@code PlaceAnimDone=true}，
     * 客户端不会再播放置动画 —— 这是"只播一次"的关键。</p>
     */
    public void onPlaceAnimDone() {
        if (level == null || level.isClientSide || placeAnimDone) {
            return;
        }
        placeAnimDone = true;
        setChanged();
        syncToClient();
        com.deepspacedeployer.DeepSpaceDeployer.LOGGER.info(
                "[深空铸造体][动画] 服务端记下「放置」已播完 @{}", worldPosition);
    }

    /** 客户端：告诉服务端"放置动画播完了" */
    private void sendPlaceAnimDone() {
        if (level == null || !level.isClientSide) {
            return;
        }
        try {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new com.deepspacedeployer.net.BuyerAnimDonePayload(worldPosition));
        } catch (Throwable t) {
            com.deepspacedeployer.DeepSpaceDeployer.LOGGER.warn(
                    "[深空铸造体][动画] 发送放置回执失败：{}", t.toString());
        }
    }

    /** 客户端：告诉服务端"动画播完了，可以收下一轮" */
    private void sendAnimDone() {
        if (level == null || !level.isClientSide) {
            return;
        }
        try {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new com.deepspacedeployer.net.BuyerAnimDonePayload(worldPosition));
        } catch (Throwable t) {
            com.deepspacedeployer.DeepSpaceDeployer.LOGGER.warn(
                    "[深空铸造体][动画] 发送回执失败：{}", t.toString());
        }
    }

    /**
     * 本方块实体在 GeckoLib 里的实例 ID。
     *
     * <p>必须和渲染器内部用的一致（见 {@code GeoBlockRenderer.getInstanceId}）：
     * 对方块实体来说是<b>方块坐标的 hash</b>。不一致 {@code getManagerForId} 就取不到。</p>
     */
    private long instanceId() {
        return worldPosition.hashCode();
    }

    @Override
    public software.bernie.geckolib.animatable.instance.AnimatableInstanceCache getAnimatableInstanceCache() {
        return animCache;
    }

    @Override
    public double getTick(Object object) {
        // ⚠️ 这里必须返回**客户端本地的时间轴**，不能返回 level.getGameTime()。
        //
        //    GeckoLib 的 AnimationController.adjustTick 是这么算动画进度的：
        //        delta = getTick() - manager.lastUpdateTime
        //    如果返回世界总时间（老存档动辄几万 tick），而控制器管理器刚建好时
        //    lastUpdateTime 是很小的值 —— 第一帧算出的 delta 就是"几万 tick"，
        //    动画一步跳到结尾，看起来【完全不动】。
        //    （我们的动画全程只有 15 tick，最容易被这个吃掉。）
        //
        //    用本地计数器后：delta 恒为 1，动画按 20 tick/秒 正常推进。
        return animAgeTicks;
    }

}
