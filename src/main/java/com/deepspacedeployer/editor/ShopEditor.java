package com.deepspacedeployer.editor;

import com.deepspacedeployer.Company;
import com.deepspacedeployer.Tier;
import com.deepspacedeployer.net.ShopDataPayload;
import com.deepspacedeployer.net.ShopEditPayload;
import com.deepspacedeployer.shop.ShopData;
import com.deepspacedeployer.shop.TradeEntry;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 游戏内价目表编辑器（服务端）。
 *
 * <p>工作方式：把当前价目表复制一份到内存 → 界面上改 → 立刻对所有商店生效 →
 * 点"保存"时写成**世界数据包**（{@code <存档>/datapacks/deepspacedeployer_editor/}）并重载，
 * 这样重启游戏也不会丢。</p>
 *
 * <p>只有 OP（权限等级 2）能用；客户端那个入口按钮也只对 OP 显示。</p>
 */
public final class ShopEditor {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String PACK_NAME = "deepspacedeployer_editor";

    public static final int ACTION_REQUEST = 0;
    public static final int ACTION_ADD_HELD = 1;
    public static final int ACTION_PRICE = 2;
    public static final int ACTION_TIER = 3;
    public static final int ACTION_REMOVE = 4;
    public static final int ACTION_SAVE = 5;
    /** 直接设成某个价格（客户端输入框 + 确定按钮） */
    public static final int ACTION_SET_PRICE = 6;

    private static final Map<Company, List<TradeEntry>> BUY = new EnumMap<>(Company.class);
    private static final Map<Company, List<TradeEntry>> SELL = new EnumMap<>(Company.class);
    private static boolean loaded;
    private static boolean dirty;

    public static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        ShopData data = ShopData.get();
        for (Company company : Company.VALUES) {
            BUY.put(company, new ArrayList<>(data.buyOffers(company)));
            SELL.put(company, new ArrayList<>(data.sellOffers(company)));
        }
        loaded = true;
    }

    public static synchronized void handle(net.minecraft.world.entity.player.Player rawPlayer, ShopEditPayload payload) {
        if (!(rawPlayer instanceof ServerPlayer player)) {
            return;
        }
        if (!player.hasPermissions(2)) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.editor_no_permission"), true);
            return;
        }
        ensureLoaded();
        Company company = Company.byId(payload.companyId());
        List<TradeEntry> list = payload.listKind() == 1 ? SELL.get(company) : BUY.get(company);

        switch (payload.action()) {
            case ACTION_REQUEST -> {
                // 只是要数据
            }
            case ACTION_ADD_HELD -> addHeldItem(player, payload, company, list);
            case ACTION_PRICE -> adjustPrice(player, list, payload.index(), payload.value());
            case ACTION_SET_PRICE -> setPrice(player, list, payload.index(), payload.value());
            case ACTION_TIER -> cycleTier(player, list, payload.index());
            case ACTION_REMOVE -> removeEntry(player, list, payload.index());
            case ACTION_SAVE -> {
                // 注意：只在这一条分支里落盘 + 重载数据包（下面那两句原来是对所有动作都执行的）
                save(player.server, player);
                return;
            }
            default -> {
            }
        }

        // 改动立刻对"已经开着的商店"生效（只换一个引用，很便宜）
        publish();
        // ⚠️ 但只有"行的增删"才需要把整张表发回编辑器客户端：
        //    改价格/改等级是"在原值上改一下"，客户端那个输入框本来就是新的值 ——
        //    每点一下都发一次会让编辑器界面的控件整套重建（正在输入的价格框会掉焦点，看着就是"卡一下"）。
        if (structural(payload.action())) {
            sendTo(player, company);
        }
    }

    /** 这个动作会不会改变"列表的行数/内容结构"（决定要不要把整张表发回客户端） */
    private static boolean structural(int action) {
        return action == ACTION_ADD_HELD || action == ACTION_REMOVE || action == ACTION_REQUEST;
    }

    private static void addHeldItem(ServerPlayer player, ShopEditPayload payload, Company company,
                                    List<TradeEntry> list) {
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.editor_need_item"), true);
            return;
        }
        String id = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
        // 手里拿的是"指定某份蓝图"的凭证时，把蓝图一起记下来
        String blueprint = com.deepspacedeployer.economy.VoucherData.blueprint(held);
        for (TradeEntry entry : list) {
            if (entry.itemId().equals(id) && entry.blueprint().equals(blueprint)) {
                player.displayClientMessage(Component.translatable("msg.deepspacedeployer.editor_already",
                        blueprint.isEmpty() ? id : id + " (" + blueprint + ")"), true);
                return;
            }
        }
        int price = Math.max(1, payload.value());
        list.add(new TradeEntry(id, 1, price, Tier.LOW, 0, blueprint));
        dirty = true;
        player.displayClientMessage(Component.translatable("msg.deepspacedeployer.editor_added",
                held.getHoverName(), price, company.displayName()), false);
    }

    /** 直接把价格设成输入框里的数字（1 ~ 100 万之间） */
    private static void setPrice(ServerPlayer player, List<TradeEntry> list, int index, int price) {
        TradeEntry entry = get(list, index);
        if (entry == null) {
            return;
        }
        int safe = Math.max(1, Math.min(1_000_000, price));
        if (safe == entry.price()) {
            return;
        }
        list.set(index, entry.withPrice(safe));
        dirty = true;
        player.displayClientMessage(Component.translatable("msg.deepspacedeployer.editor_price_set",
                safe), true);
    }

    private static void adjustPrice(ServerPlayer player, List<TradeEntry> list, int index, int delta) {        TradeEntry entry = get(list, index);
        if (entry == null || delta == 0) {
            return;
        }
        int price = Math.max(1, Math.min(1_000_000, entry.price() + delta));
        list.set(index, entry.withPrice(price));
        dirty = true;
    }

    private static void cycleTier(ServerPlayer player, List<TradeEntry> list, int index) {
        TradeEntry entry = get(list, index);
        if (entry == null) {
            return;
        }
        Tier next = Tier.byIndex((entry.tier().rank() + 1) % Tier.COUNT);
        list.set(index, new TradeEntry(entry.itemId(), entry.count(), entry.price(), next, entry.reputation()));
        dirty = true;
    }

    private static void removeEntry(ServerPlayer player, List<TradeEntry> list, int index) {
        if (get(list, index) == null) {
            return;
        }
        list.remove(index);
        dirty = true;
    }

    private static TradeEntry get(List<TradeEntry> list, int index) {
        return index >= 0 && index < list.size() ? list.get(index) : null;
    }

    /** 让改动立刻对所有商店生效（不用等保存） */
    private static void publish() {
        Map<Company, List<TradeEntry>> buy = new EnumMap<>(Company.class);
        Map<Company, List<TradeEntry>> sell = new EnumMap<>(Company.class);
        for (Company company : Company.VALUES) {
            buy.put(company, BUY.get(company));
            sell.put(company, SELL.get(company));
        }
        ShopData.set(new ShopData(buy, sell));
    }

    /** 把某家公司的价目表发回编辑器界面 */
    public static void sendTo(ServerPlayer player, Company company) {
        ShopData data = ShopData.get();
        PacketDistributor.sendToPlayer(player, new ShopDataPayload(company.id(), 2,
                data.buyOffers(company), data.sellOffers(company)));
    }

    // ---------------- 保存到世界数据包 ----------------

    public static synchronized boolean save(MinecraftServer server, ServerPlayer player) {
        Path root = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.DATAPACK_DIR)
                .resolve(PACK_NAME);
        Path shopDir = root.resolve("data").resolve("deepspacedeployer").resolve("shop");
        try {
            Files.createDirectories(shopDir);
            Files.writeString(root.resolve("pack.mcmeta"), GSON.toJson(Map.of("pack", Map.of(
                    "pack_format", 48,
                    "description", "深空铸造体 · 价目表编辑器（游戏内生成）"))), StandardCharsets.UTF_8);

            for (Company company : Company.VALUES) {
                Map<String, Object> json = new java.util.LinkedHashMap<>();
                json.put("company", company.id());
                json.put("_说明", "由游戏内编辑器生成；tier=low/mid/high 是需要的工厂等级，reputation 是需要的声望");
                List<Map<String, Object>> buy = new ArrayList<>();
                for (TradeEntry entry : BUY.get(company)) {
                    buy.add(entryToJson(entry, true));
                }
                List<Map<String, Object>> sell = new ArrayList<>();
                for (TradeEntry entry : SELL.get(company)) {
                    sell.add(entryToJson(entry, false));
                }
                json.put("buy", buy);
                json.put("sell", sell);
                Files.writeString(shopDir.resolve(company.id() + ".json"), GSON.toJson(json),
                        StandardCharsets.UTF_8);
            }
            dirty = false;
        } catch (IOException e) {
            LOGGER.warn("[深空铸造体] 保存价目表失败：{}", e.toString());
            player.displayClientMessage(Component.translatable("msg.deepspacedeployer.editor_save_failed",
                    e.toString()), false);
            return false;
        }

        // 落盘之后不重载数据包也已经在用新价格了（改的是内存里那份，publish 过就生效）。
        // 重载只是为了"重启之后读回来的和现在一致"，而它要重新解析整合包里所有数据包文件，
        // 会明显卡一下（整合包越大越久）—— 所以这里明确告诉玩家，并记一条耗时日志。
        player.displayClientMessage(Component.translatable("msg.deepspacedeployer.editor_saving"), false);
        long start = System.nanoTime();
        server.execute(() -> server.reloadResources(server.getPackRepository().getSelectedIds())
                .thenRun(() -> LOGGER.info("[深空铸造体] 价目表数据包重载完成，耗时 {} ms",
                        (System.nanoTime() - start) / 1_000_000L))
                .exceptionally(error -> {
                    LOGGER.warn("[深空铸造体] 重载数据包失败：{}", error.toString());
                    return null;
                }));
        player.displayClientMessage(Component.translatable("msg.deepspacedeployer.editor_saved",
                root.toString()), false);
        LOGGER.info("[深空铸造体] 价目表已保存到 {}（重载已排队）", shopDir);
        return true;
    }

    private static Map<String, Object> entryToJson(TradeEntry entry, boolean isBuy) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("item", entry.itemId());
        map.put("count", entry.count());
        map.put("price", entry.price());
        if (entry.hasBlueprint()) {
            map.put("blueprint", entry.blueprint());
        }
        if (isBuy) {
            map.put("tier", entry.tier().id());
            map.put("reputation", entry.reputation());
        }
        return map;
    }

    public static boolean isDirty() {
        return dirty;
    }

    private ShopEditor() {
    }
}
