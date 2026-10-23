package com.deepspacedeployer.blockentity;

import com.deepspacedeployer.Company;
import com.deepspacedeployer.economy.CardData;
import com.deepspacedeployer.economy.CompanyFunds;
import com.deepspacedeployer.economy.PaymentService;
import com.deepspacedeployer.economy.ReputationData;
import com.deepspacedeployer.economy.WalletService;
import com.deepspacedeployer.registry.ModBlockEntities;
import com.deepspacedeployer.shop.ShopData;
import com.deepspacedeployer.shop.TradeEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animatable.instance.SingletonAnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 通用终端 —— 6 个卡槽 + 插卡切换公司 + 该公司的商店。
 *
 * <p>老板要求："里面可以存放卡，有几种卡就有几种公司；插卡后变成该公司的商店/交易终端"。</p>
 *
 * <p><b>公司怎么定</b>：每张卡上带着公司标记（银行卡的显示名里有公司名），
 * 终端扫描 6 个卡槽，第一张能认出公司的卡就是"当前公司"。
 * 所以插卡 = 切换公司，取卡 = 回到无归属状态。</p>
 *
 * <p>动画：几何/动画都是 Blockbench 官方导出器的产物；控制器直接返回「放置」动画
 * （不依赖 triggerAnim）。</p>
 */
public class UniversalTerminalBlockEntity extends BlockEntity implements GeoBlockEntity, Container {

    public static final String ANIM_PLACE = "放置";
    public static final String CONTROLLER = "main";
    /** 卡槽数量 = 公司数量（几种卡就有几种公司） */
    public static final int SLOT_COUNT = Company.COUNT;
    /** 页面大小（商店列表每页条数） */
    public static final int PAGE_SIZE = 8;

    private final AnimatableInstanceCache cache = new SingletonAnimatableInstanceCache(this);
    private final net.minecraft.core.NonNullList<ItemStack> items =
            net.minecraft.core.NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
    /** 当前公司（由卡槽里的卡决定）；无卡时无归属，用 Company.VALUES[0] 占位 */
    private Company company = Company.VALUES[0];
    private boolean hasCompany;
    /** 商店页 */
    private int page;
    /** 动画是否已触发（只触发一次） */
    private boolean animationTriggered;
    /** 加载后经过的客户端 tick 数 */
    private int ticksAlive;

    public UniversalTerminalBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.UNIVERSAL_TERMINAL.get(), pos, state);
    }

    // ---------------- 公司 ----------------

    public Company company() {
        return company;
    }

    public boolean hasCompany() {
        return hasCompany;
    }

    /** 扫一遍卡槽，决定当前公司 */
    public void refreshCompany() {
        Company found = null;
        for (int i = 0; i < SLOT_COUNT && found == null; i++) {
            found = companyOf(items.get(i));
        }
        if (found == null) {
            hasCompany = false;
        } else {
            hasCompany = true;
            company = found;
        }
        setChanged();
    }

    /** 从一张卡上认出公司（按显示名里是否含公司名匹配） */
    public static Company companyOf(ItemStack stack) {
        if (stack.isEmpty() || !CardData.isCard(stack)) {
            return null;
        }
        String label = stack.getHoverName().getString();
        for (Company c : Company.VALUES) {
            if (label.contains(c.cnName()) || label.contains(c.enName())) {
                return c;
            }
        }
        // 认不出公司名的卡：用主卡标签兜底（"银行卡"这种）
        return null;
    }

    /** 当前用于付款的卡（第一张能认出公司的卡；没有就用第一张卡） */
    public ItemStack activeCard() {
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (companyOf(items.get(i)) != null) {
                return items.get(i);
            }
        }
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (!items.get(i).isEmpty()) {
                return items.get(i);
            }
        }
        return ItemStack.EMPTY;
    }

    // ---------------- 商店 ----------------

    public int page() {
        return page;
    }

    public void setPage(int value) {
        page = Math.max(0, value);
    }

    public List<TradeEntry> offers() {
        return ShopData.get().buyOffers(company());
    }

    /** 买第 index 个商品（用卡槽里的卡付款） */
    public boolean buy(ServerPlayer player, int index) {
        ItemStack card = activeCard();
        if (card.isEmpty()) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.need_card"), true);
            return false;
        }
        if (!hasCompany) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.terminal_need_company"), true);
            return false;
        }
        // 未绑定的新卡：先查 2 张上限
        if (!CardData.hasAccount(card) && !WalletService.canAdd(player)) {
            WalletService.limitMessage(player);
            return false;
        }
        CardData.bindIfMissing(card, player);
        UUID account = CardData.ownerAccount(card);
        if (account == null) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.no_account"), false);
            return false;
        }
        if (WalletService.isVoided(player.server, card)) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.card_voided"), false);
            return false;
        }
        // 老卡的便携余额迁进账户，并补登记卡表
        CardData.migrateToAccount(card, CompanyFunds.get(player.server), account);
        WalletService.ensureRegistered(player, card);
        List<TradeEntry> list = offers();
        if (index < 0 || index >= list.size()) {
            return false;
        }
        TradeEntry entry = list.get(index);
        if (!entry.isValid()) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.offer_invalid"), true);
            return false;
        }
        int reputation = ReputationData.get(player.server).reputation(account, company);
        if (reputation < entry.reputation()) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.reputation_locked",
                    entry.reputation(), reputation), true);
            return false;
        }
        if (!PaymentService.canPay(player, account, company, entry.price())) {
            WalletService.companyInsufficientMessage(player);
            return false;
        }
        if (!pay(player, account, entry.price())) {
            WalletService.companyInsufficientMessage(player);
            return false;
        }
        ReputationData.get(player.server).addSpent(account, company, entry.price());
        ItemStack result = entry.createStack();
        if (!player.getInventory().add(result)) {
            player.drop(result, false);
        }
        setChanged();
        player.displayClientMessage(Component.translatable("msg.deepspacedeployer.bought",
                result.getHoverName(), entry.price(), CardData.accountName(card)), false);
        return true;
    }

    /** 扣款：账户优先；账户不够时自动用实体币，找零只给 1 元券。 */
    private boolean pay(ServerPlayer player, UUID account, long amount) {
        return PaymentService.pay(player, account, company, amount) != null;
    }

    // ---------------- 动画 / 每 tick ----------------

    /**
     * 客户端每 tick 一次（由方块类的 {@code getTicker} 调用）。
     *
     * <p>动画由控制器直接播放，这里只做一件小事：卡槽内容变化后同步一次公司
     * （插卡/取卡时客户端也能立刻看到归属变化）。</p>
     */
    public void clientTick() {
        if (level == null || !level.isClientSide) {
            return;
        }
        // 1) 记录一次"客户端 tick 有在跑"（动画由控制器自己循环播，这里只做日志）
        if (!animationTriggered) {
            ticksAlive++;
            if (ticksAlive >= 2) {
                animationTriggered = true;
                com.deepspacedeployer.DeepSpaceDeployer.LOGGER.info(
                        "[深空铸造体][终端] 客户端 tick 正常（动画由控制器循环播放，tick={}）", ticksAlive);
            }
        }
        // 2) 每 10 tick 同步一次公司（插卡/取卡后界面立刻反映）
        if (level.getGameTime() % 10L == 0L) {
            Company before = company;
            boolean hadCompany = hasCompany;
            refreshCompany();
            if (before != company || hadCompany != hasCompany) {
                setChanged();
            }
        }
    }


    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // 每帧都返回「放置」→ 动画持续循环播放。
        //
        // 这是**之前成功那版**的写法，先回到它来确认渲染链路是通的：
        // 只要控制器在跑动画，模型就应该动（首轮从第 0 帧开始，能直接看到升起 + 张爪）。
        controllers.add(new AnimationController<>(this, CONTROLLER, 0,
                state -> state.setAndContinue(RawAnimation.begin().thenPlay(ANIM_PLACE))));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    @Override
    public double getTick(Object object) {
        return level == null ? 0.0D : level.getGameTime();
    }

    // ---------------- Container ----------------

    @Override
    public int getContainerSize() {
        return SLOT_COUNT;
    }

    @Override
    public boolean isEmpty() {
        return items.stream().allMatch(ItemStack::isEmpty);
    }

    @Override
    public ItemStack getItem(int slot) {
        return items.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack result = ContainerHelper.removeItem(items, slot, amount);
        if (!result.isEmpty()) {
            refreshCompany();
        }
        return result;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack result = ContainerHelper.takeItem(items, slot);
        refreshCompany();
        return result;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        items.set(slot, stack);
        refreshCompany();
    }

    @Override
    public boolean stillValid(Player player) {
        return level != null && level.getBlockEntity(worldPosition) == this
                && player.distanceToSqr(worldPosition.getX() + 0.5D, worldPosition.getY() + 0.5D,
                worldPosition.getZ() + 0.5D) <= 64.0D;
    }

    /** 只允许放"卡" */
    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return CardData.isCard(stack);
    }

    @Override
    public void clearContent() {
        items.clear();
        refreshCompany();
    }

    // ---------------- 存档 ----------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items, registries);
        tag.putString("Company", company.id());
        tag.putBoolean("HasCompany", hasCompany);
        tag.putInt("Page", page);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        ContainerHelper.loadAllItems(tag, items, registries);
        company = Company.byId(tag.getString("Company"));
        hasCompany = tag.getBoolean("HasCompany");
        page = tag.getInt("Page");
    }

    /** 聊天栏/界面标题用 */
    public Component title() {
        return hasCompany
                ? Component.translatable("gui.deepspacedeployer.terminal_titled", company.displayName())
                : Component.translatable("gui.deepspacedeployer.terminal");
    }

    /** 当前公司卖的东西（给界面用） */
    public List<TradeEntry> currentPageOffers() {
        List<TradeEntry> all = offers();
        int from = Math.min(page * PAGE_SIZE, all.size());
        int to = Math.min(from + PAGE_SIZE, all.size());
        return new ArrayList<>(all.subList(from, to));
    }
}
