package com.deepspacedeployer.market;

import com.deepspacedeployer.Company;
import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * 寄售市场上的一条挂单。
 *
 * <p>老板定的规则：可以挂售<b>载具</b>（Sable-Schematics 里的图纸，兼容多物理结构）或<b>物品</b>，
 * 定价两种 —— <b>以币</b>（账户余额）或<b>以物易物</b>（买方要交出指定物品 + 数量）。</p>
 *
 * @param id        挂单编号（自增，取消/购买都按它找）
 * @param seller    卖家账户 UUID（钱和货都记在这个人头上）
 * @param sellerName 卖家名（只用来显示）
 * @param company   这笔交易用哪家公司的钱（以币模式才用；以物易物不看）
 * @param kind      挂的是物品还是载具
 * @param item      挂的物品（kind = ITEM 时有意义）
 * @param vehicle   图纸名（kind = VEHICLE 时有意义）
 * @param price     以币模式的价钱（0 = 不卖钱）
 * @param barter    以物易物模式要求的东西（空 = 不收物）
 * @param barterCount 要多少个（barter 非空时有意义）
 */
public record Listing(long id, UUID seller, String sellerName, Company company, Kind kind,
                      ItemStack item, String vehicle, long price, ItemStack barter, int barterCount) {

    private static final Logger LOGGER = LogUtils.getLogger();

    public enum Kind {
        ITEM,
        VEHICLE
    }

    /** 这条挂单是不是以物易物 */
    public boolean isBarter() {
        return barter != null && !barter.isEmpty();
    }

    /** 展示用的标题 */
    public String title() {
        return kind == Kind.VEHICLE ? vehicle : item.getHoverName().getString();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Id", id);
        tag.putString("Seller", seller == null ? "" : seller.toString());
        tag.putString("SellerName", sellerName == null ? "" : sellerName);
        tag.putString("Company", company == null ? Company.VALUES[0].id() : company.id());
        tag.putString("Kind", kind.name());
        putStack(tag, "Item", item);
        tag.putString("Vehicle", vehicle == null ? "" : vehicle);
        tag.putLong("Price", price);
        putStack(tag, "Barter", barter);
        tag.putInt("BarterCount", barterCount);
        return tag;
    }

    /** 物品 → NBT：走原版 codec，自动带上数量与全部数据组件（附魔、名字、卡里的余额都在） */
    private static void putStack(CompoundTag tag, String key, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        ItemStack.CODEC.encodeStart(NbtOps.INSTANCE, stack)
                .resultOrPartial(error -> LOGGER.warn("[深空铸造体] 挂单物品序列化失败：{}", error))
                .ifPresent(encoded -> tag.put(key, encoded));
    }

    private static ItemStack getStack(CompoundTag tag, String key) {
        if (!tag.contains(key)) {
            return ItemStack.EMPTY;
        }
        return ItemStack.CODEC.parse(NbtOps.INSTANCE, tag.get(key))
                .resultOrPartial(error -> LOGGER.warn("[深空铸造体] 挂单物品读取失败：{}", error))
                .orElse(ItemStack.EMPTY);
    }

    public static Listing load(CompoundTag tag) {
        UUID seller = null;
        String raw = tag.getString("Seller");
        if (!raw.isEmpty()) {
            try {
                seller = UUID.fromString(raw);
            } catch (IllegalArgumentException ignored) {
                // 存档坏了就当无主，购买时会被拒绝
            }
        }
        Kind kind;
        try {
            kind = Kind.valueOf(tag.getString("Kind"));
        } catch (IllegalArgumentException e) {
            kind = Kind.ITEM;
        }
        return new Listing(tag.getLong("Id"), seller, tag.getString("SellerName"),
                Company.byId(tag.getString("Company")), kind, getStack(tag, "Item"),
                tag.getString("Vehicle"), tag.getLong("Price"),
                getStack(tag, "Barter"), tag.getInt("BarterCount"));
    }
}
