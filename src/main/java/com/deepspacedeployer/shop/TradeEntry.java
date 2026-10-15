package com.deepspacedeployer.shop;

import com.deepspacedeployer.Tier;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 一条交易条目（数据包 JSON 里的一条）。
 *
 * @param itemId     物品 id，例如 {@code create:precision_mechanism}
 * @param count      一次交易的数量
 * @param price      价格（本公司票据数量）
 * @param tier       需要的最低工厂等级
 * @param reputation 需要的声望（累计消费 / 1000）
 * @param blueprint  可选：给「蓝图凭证」指定它代表哪份蓝图（普通物品留空）
 */
public record TradeEntry(String itemId, int count, int price, Tier tier, int reputation, String blueprint) {

    public static final StreamCodec<ByteBuf, TradeEntry> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, TradeEntry::itemId,
            ByteBufCodecs.VAR_INT, TradeEntry::count,
            ByteBufCodecs.VAR_INT, TradeEntry::price,
            ByteBufCodecs.VAR_INT, entry -> entry.tier().ordinal(),
            ByteBufCodecs.VAR_INT, TradeEntry::reputation,
            ByteBufCodecs.STRING_UTF8, TradeEntry::blueprint,
            (itemId, count, price, tier, reputation, blueprint) ->
                    new TradeEntry(itemId, count, price, Tier.byIndex(tier), reputation, blueprint));

    /** 老写法（没有蓝图字段） */
    public TradeEntry(String itemId, int count, int price, Tier tier, int reputation) {
        this(itemId, count, price, tier, reputation, "");
    }

    /** 物品 id 是否真实存在（数据包写错时不会崩，只是被跳过） */
    public boolean isValid() {
        return resolveItem() != Items.AIR;
    }

    public Item resolveItem() {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null) {
            return Items.AIR;
        }
        return BuiltInRegistries.ITEM.get(id);
    }

    /** 这条卖的是不是"指定某份蓝图"的凭证 */
    public boolean hasBlueprint() {
        return blueprint != null && !blueprint.isEmpty();
    }

    /** 买到手的东西（凭证会带上蓝图数据） */
    public ItemStack createStack() {
        ItemStack stack = new ItemStack(resolveItem(), Math.max(1, count));
        if (hasBlueprint()) {
            com.deepspacedeployer.economy.VoucherData.setBlueprint(stack, blueprint);
        }
        return stack;
    }

    public TradeEntry withCount(int newCount) {
        return new TradeEntry(itemId, newCount, price, tier, reputation, blueprint);
    }

    public TradeEntry withPrice(int newPrice) {
        return new TradeEntry(itemId, count, newPrice, tier, reputation, blueprint);
    }
}
