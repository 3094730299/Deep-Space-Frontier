package com.deepspacedeployer.market;

import com.deepspacedeployer.Company;
import com.deepspacedeployer.blockentity.MachineBlockEntity;
import com.deepspacedeployer.economy.CompanyFunds;
import com.deepspacedeployer.factory.PhysicsBlueprintLibrary;
import com.deepspacedeployer.menu.MachineMenu;
import com.deepspacedeployer.net.MarketPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 寄售市场的服务端逻辑：挂单 / 购买 / 撤单。
 *
 * <p>原则：<b>钱和货只认服务端自己那一份</b>。
 * 发单时货从"待售格"搬进挂单存档（所以挂上去就拿不回来了，只能撤单拿回）；
 * 成交时按挂单里存的那份货给买家，卖家收钱，全程不看客户端发的任何物品数据。</p>
 */
public final class MarketActions {

    /** 挂单存的是"一份"，所以待售格放多少都只挂 1 个（想挂一堆就多挂几单） */
    private static final int ONE = 1;

    public static void handle(ServerPlayer player, MachineBlockEntity machine, MachineMenu menu,
                              MarketPayload payload) {
        if (machine.kind() != com.deepspacedeployer.blockentity.MachineKind.MARKET) {
            return;
        }
        switch (payload.action()) {
            case MarketPayload.ACT_POST -> post(player, machine, payload);
            case MarketPayload.ACT_BUY -> buy(player, machine, payload);
            case MarketPayload.ACT_CANCEL -> cancel(player, machine, payload);
            default -> {
                // 不认识的动过就当没发生
            }
        }
    }

    // ---------------- 挂单 ----------------

    /** 定价模式：0 = 以物易物；1..6 = 以币，值是公司序号+1（见客户端那几个按钮） */
    private static boolean isBarterMode(int mode) {
        return mode == 0;
    }

    /** 以币模式下的收款公司；mode 不合法时退回第一家 */
    private static Company companyOfMode(int mode) {
        int index = mode - 1;
        return index < 0 || index >= Company.COUNT ? Company.VALUES[0] : Company.VALUES[index];
    }

    private static void post(ServerPlayer player, MachineBlockEntity machine, MarketPayload payload) {
        if (machine.marketData() == null) {
            return;
        }
        boolean barter = isBarterMode(payload.mode());
        Company company = barter ? machine.company() : companyOfMode(payload.mode());
        long price = Math.max(0L, Math.min(1_000_000_000L, payload.amount()));

        if (payload.isVehicle()) {
            postVehicle(player, machine, payload, barter, price, company);
            return;
        }

        // 物品单：从"待售格"拿货
        ItemStack goods = machine.sellSlot().copy();
        if (goods.isEmpty()) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.market_need_goods"), true);
            return;
        }
        ItemStack want = ItemStack.EMPTY;
        int wantCount = 0;
        if (barter) {
            want = machine.barterSlot().copy();
            if (want.isEmpty()) {
                player.displayClientMessage(
                        Component.translatable("msg.deepspacedeployer.market_need_barter"), true);
                return;
            }
            wantCount = Math.max(1, want.getCount());
            want.setCount(1); // 需求位只记"要哪一种"，数量单独算
        } else if (price <= 0L) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.market_need_price"), true);
            return;
        }

        goods.setCount(ONE);
        machine.setSellSlot(ItemStack.EMPTY); // 货搬进挂单，拿不回来了
        long id = machine.marketData().add(new Listing(0L, player.getUUID(),
                player.getGameProfile().getName(), company, Listing.Kind.ITEM,
                goods, "", price, want, wantCount));
        machine.bumpMarketVersion();
        player.displayClientMessage(Component.translatable("msg.deepspacedeployer.market_posted",
                goods.getHoverName(), describePrice(company, price, want, wantCount), id), false);
    }

    private static void postVehicle(ServerPlayer player, MachineBlockEntity machine, MarketPayload payload,
                                    boolean barter, long price, Company company) {
        var list = PhysicsBlueprintLibrary.all();
        int index = payload.index();
        if (index < 0 || index >= list.size()) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.market_pick_vehicle"), true);
            return;
        }
        String name = list.get(index).name();
        ItemStack want = ItemStack.EMPTY;
        int wantCount = 0;
        if (barter) {
            want = machine.barterSlot().copy();
            if (want.isEmpty()) {
                player.displayClientMessage(
                        Component.translatable("msg.deepspacedeployer.market_need_barter"), true);
                return;
            }
            wantCount = Math.max(1, want.getCount());
            want.setCount(1);
        } else if (price <= 0L) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.market_need_price"), true);
            return;
        }
        long id = machine.marketData().add(new Listing(0L, player.getUUID(),
                player.getGameProfile().getName(), company, Listing.Kind.VEHICLE,
                ItemStack.EMPTY, name, price, want, wantCount));
        machine.bumpMarketVersion();
        player.displayClientMessage(Component.translatable("msg.deepspacedeployer.market_posted_vehicle",
                name, describePrice(company, price, want, wantCount), id), false);
    }

    // ---------------- 购买 ----------------

    private static void buy(ServerPlayer player, MachineBlockEntity machine, MarketPayload payload) {
        MarketData data = machine.marketData();
        if (data == null) {
            return;
        }
        Listing listing = data.byId(payload.listing());
        if (listing == null) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.market_gone"), true);
            return;
        }
        if (listing.seller() != null && listing.seller().equals(player.getUUID())) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.market_own"), true);
            return;
        }
        // 先确认"取货格"放得下，不然钱付了货没处放
        if (!machine.collectSlot().isEmpty()) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.market_collect_busy"), true);
            return;
        }

        Company company = listing.company() == null ? machine.company() : listing.company();
        CompanyFunds funds = machine.fundsOrNull();
        if (funds == null) {
            return;
        }

        if (listing.isBarter()) {
            // 以物易物：先确认买方背包里有够数的东西
            if (!takeFromInventory(player, listing.barter(), listing.barterCount())) {
                player.displayClientMessage(Component.translatable("msg.deepspacedeployer.market_need_pay_items",
                        listing.barterCount(), listing.barter().getHoverName()), true);
                return;
            }
        } else {
            // 以币：买方要在**这张挂单的公司**里有余额（市场本身无归属，所以公司跟着挂单走）
            long price = listing.price();
            if (funds.balance(player.getUUID(), company) < price) {
                player.displayClientMessage(Component.translatable("msg.deepspacedeployer.market_no_money",
                        price, company.displayName()), true);
                return;
            }
            funds.add(player.getUUID(), company, -price);
            if (listing.seller() != null) {
                funds.add(listing.seller(), company, price);
            }
        }

        if (listing.kind() == Listing.Kind.VEHICLE) {
            // 用市场方块自己的位置当生成点（和"生成物理结构"那套一致）
            PhysicsBlueprintLibrary.Result result = com.deepspacedeployer.factory.PhysicsBlueprintLibrary.spawn(
                    (net.minecraft.server.level.ServerLevel) machine.getLevel(),
                    listing.vehicle(), machine.getBlockPos().getCenter());
            if (!result.success()) {
                // 生成失败：把付出去的东西退回来（钱或物），挂单保留
                refund(player, machine, listing, company, funds);
                player.displayClientMessage(Component.translatable("msg.deepspacedeployer.market_spawn_failed",
                        result.detail()), true);
                return;
            }
            machine.bumpMarketVersion();
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.market_bought_vehicle",
                    listing.vehicle(), result.placedSubLevels()), false);
        } else {
            machine.setCollectSlot(listing.item().copy());
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.market_bought",
                    listing.item().getHoverName()), false);
        }
        data.consume(listing.id());
        machine.bumpMarketVersion();
    }

    /** 生成失败时退款：钱或物按原路退回买家 */
    private static void refund(ServerPlayer player, MachineBlockEntity machine, Listing listing,
                               Company company, CompanyFunds funds) {
        if (listing.isBarter()) {
            if (!player.getInventory().add(listing.barter().copyWithCount(listing.barterCount()))) {
                player.drop(listing.barter().copyWithCount(listing.barterCount()), false);
            }
        } else {
            funds.add(player.getUUID(), company, listing.price());
        }
    }

    // ---------------- 撤单 ----------------

    private static void cancel(ServerPlayer player, MachineBlockEntity machine, MarketPayload payload) {
        MarketData data = machine.marketData();
        if (data == null) {
            return;
        }
        Listing listing = data.byId(payload.listing());
        if (listing == null || listing.seller() == null || !listing.seller().equals(player.getUUID())) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.market_not_yours"), true);
            return;
        }
        if (listing.kind() == Listing.Kind.VEHICLE) {
            // 载具单没有实体货，撤了就是撤了
            data.remove(listing.id(), player.getUUID());
            machine.bumpMarketVersion();
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.market_cancelled_vehicle",
                    listing.vehicle()), false);
            return;
        }
        if (!machine.collectSlot().isEmpty()) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.market_collect_busy"), true);
            return;
        }
        machine.setCollectSlot(listing.item().copy());
        data.remove(listing.id(), player.getUUID());
        machine.bumpMarketVersion();
        player.displayClientMessage(Component.translatable("msg.deepspacedeployer.market_cancelled",
                listing.item().getHoverName()), false);
    }

    // ---------------- 小工具 ----------------

    /** 从背包里扣掉指定数量的某种物品；不够就一个都不扣（先数再扣） */
    private static boolean takeFromInventory(ServerPlayer player, ItemStack wanted, int count) {
        if (wanted == null || wanted.isEmpty() || count <= 0) {
            return true;
        }
        int have = 0;
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, wanted)) {
                have += stack.getCount();
            }
        }
        if (have < count) {
            return false;
        }
        int left = count;
        for (int i = 0; i < inventory.getContainerSize() && left > 0; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, wanted)) {
                continue;
            }
            int take = Math.min(left, stack.getCount());
            stack.shrink(take);
            left -= take;
            if (stack.isEmpty()) {
                inventory.setItem(i, ItemStack.EMPTY);
            }
        }
        return true;
    }

    private static String describePrice(Company company, long price, ItemStack want, int wantCount) {
        if (want != null && !want.isEmpty()) {
            return wantCount + " × " + want.getHoverName().getString();
        }
        return price + " " + (company == null ? "" : company.cnName()) + "余额";
    }

    private MarketActions() {
    }
}
