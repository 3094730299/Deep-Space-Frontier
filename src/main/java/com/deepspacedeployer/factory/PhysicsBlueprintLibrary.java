package com.deepspacedeployer.factory;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 物理结构蓝图库：读 <b>Sable-Schematics</b> 与 <b>enxv_aeronautics_structures</b> 里的物理蓝图，
 * 并且可以<b>直接把蓝图生成成物理结构</b>（不再是"先放方块再物理化"）。
 *
 * <p>用到的 Sable Photomancy API（按真实字节码校准）：</p>
 * <pre>
 * dev.rew1nd.sableschematicapi.blueprint.SableBlueprintRootFiles
 *   public static Set&lt;String&gt; list(MinecraftServer)          // Sable-Schematics/*.nbt
 *   public static SableBlueprint load(MinecraftServer, String)
 * dev.rew1nd.sableschematicapi.blueprint.SableBlueprint
 *   public static SableBlueprint load(CompoundTag)             // 自己读 nbt 后走这个（给 enxv 文件夹用）
 *   public List&lt;SubLevelData&gt; subLevels(); public int blockCount()
 *   public BoundingBox3d canonicalBounds() / rootBounds()
 *   SubLevelData: blockPalette() / blocks() / localBounds() / name()
 *   BlockData: localPos() / paletteId()
 * dev.rew1nd.sableschematicapi.blueprint.SableBlueprintPlacer
 *   public static Result place(ServerLevel, SableBlueprint, Vec3)
 *   Result: placedSubLevels() / subLevelUuidMap() / diagnostics()
 * </pre>
 */
public final class PhysicsBlueprintLibrary {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ROOT_FILES = "dev.rew1nd.sableschematicapi.blueprint.SableBlueprintRootFiles";
    private static final String BLUEPRINT = "dev.rew1nd.sableschematicapi.blueprint.SableBlueprint";
    private static final String PLACER = "dev.rew1nd.sableschematicapi.blueprint.SableBlueprintPlacer";
    private static final String SUB_LEVEL_DATA = BLUEPRINT + "$SubLevelData";
    private static final String BLOCK_DATA = BLUEPRINT + "$BlockData";
    private static final String PREVIEW = "dev.rew1nd.sableschematicapi.blueprint.preview.SableBlueprintPreview";
    private static final String PREVIEW_VIEW = PREVIEW + "$View";
    private static final String PREVIEW_GENERATOR =
            "dev.rew1nd.sableschematicapi.blueprint.preview.SableBlueprintPreviewGenerator";
    /** 除了 Sable-Schematics，还要读这个文件夹 */
    private static final String EXTRA_DIR = "enxv_aeronautics_structures";

    private static boolean probed;
    private static boolean available;
    private static Method rootList;
    private static Method rootLoad;
    private static Method loadFromTag;
    private static Method placerPlace;
    private static Method resultPlaced;
    private static Method subLevels;
    private static Method blockPalette;
    private static Method blocksMethod;
    private static Method paletteId;
    private static Method localPos;
    /** 预览相关（Sable 的 blueprint.preview 包） */
    private static Method previewMethod;
    private static Method pixelsMethod;
    private static Method resolutionMethod;
    private static Method subLevelsMethod;
    private static Method generatorMethod;
    private static Object ISO_VIEW;

    /** 一份读好的蓝图 */
    public record Entry(String name, Object blueprint, int blocks, int sizeX, int sizeY, int sizeZ,
                        Map<String, Integer> materials) {
    }

    /** 生成结果 */
    public record Result(boolean success, int placedSubLevels, String detail) {
    }

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
    private static volatile List<BlueprintInfo> infos = List.of();

    // ---------------------------------------------------------------- 反射探测

    private static synchronized void probe() {
        if (probed) {
            return;
        }
        probed = true;
        try {
            Class<?> rootFiles = Class.forName(ROOT_FILES);
            rootList = rootFiles.getMethod("list", MinecraftServer.class);
            rootLoad = rootFiles.getMethod("load", MinecraftServer.class, String.class);

            Class<?> blueprint = Class.forName(BLUEPRINT);
            loadFromTag = blueprint.getMethod("load", CompoundTag.class);
            subLevels = blueprint.getMethod("subLevels");

            Class<?> subLevelData = Class.forName(SUB_LEVEL_DATA);
            blockPalette = subLevelData.getMethod("blockPalette");
            blocksMethod = subLevelData.getMethod("blocks");

            Class<?> blockData = Class.forName(BLOCK_DATA);
            paletteId = blockData.getMethod("paletteId");
            localPos = blockData.getMethod("localPos");

            Class<?> placer = Class.forName(PLACER);
            placerPlace = placer.getMethod("place", ServerLevel.class, blueprint, Vec3.class);
            resultPlaced = Class.forName(PLACER + "$Result").getMethod("placedSubLevels");

            // 预览缩略图（可选：拿不到就只是没图，蓝图功能照常）
            try {
                previewMethod = blueprint.getMethod("preview");
                Class<?> preview = Class.forName(PREVIEW);
                Class<?> view = Class.forName(PREVIEW_VIEW);
                pixelsMethod = preview.getMethod("pixels", view);
                resolutionMethod = preview.getMethod("resolution");
                subLevelsMethod = subLevels;
                ISO_VIEW = view.getField("ISO_XP_ZP").get(null);
                generatorMethod = Class.forName(PREVIEW_GENERATOR).getMethod("generate", java.util.List.class);
            } catch (Throwable t) {
                previewMethod = null;
                LOGGER.warn("[深空铸造体] 图纸缩略图接口不可用（不影响组装）：{}", rootOf(t));
            }

            available = true;
            LOGGER.info("[深空铸造体] 物理蓝图接口就绪：{}.list/load + {}.place", ROOT_FILES, PLACER);
        } catch (ClassNotFoundException e) {
            available = false;
            LOGGER.warn("[深空铸造体] 没找到 Sable Photomancy 的物理蓝图接口（蓝图功能会不可用）");
        } catch (Throwable t) {
            available = false;
            LOGGER.warn("[深空铸造体] 探测物理蓝图接口失败：{}", t.toString());
        }
    }

    public static boolean available() {
        probe();
        return available;
    }

    public static String describe() {
        probe();
        if (!available) {
            return "未找到（需要 Sable Photomancy）";
        }
        return ROOT_FILES + ".list + " + PLACER + ".place(ServerLevel, SableBlueprint, Vec3)";
    }

    // ---------------------------------------------------------------- 读取蓝图

    /** 给玩家看的"图纸放哪"提示（游戏目录下的两个文件夹） */
    public static String folderHint() {
        return "Sable-Schematics / " + EXTRA_DIR + "（都在游戏目录下）";
    }

    // ---------------- 预览缩略图 ----------------

    /** 缩略图边长（像素）。原图是 512×512，缩小后每张 PNG 只有几 KB */
    private static final int THUMB_SIZE = 64;
    /** 名字 → PNG 字节；用线程安全的 map，生成在后台线程做 */
    private static final Map<String, byte[]> THUMBS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> THUMBS_BUSY = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.atomic.AtomicInteger THUMBS_DONE =
            new java.util.concurrent.atomic.AtomicInteger();
    /** "图纸刷新代"：每次重读 +1，各工厂核心靠它决定要不要重发缩略图 */
    private static volatile int thumbnailGeneration;

    /** 当前图纸刷新代（重读一次 +1） */
    public static int thumbnailGeneration() {
        return thumbnailGeneration;
    }

    /** 缩略图是否都生成完了（没生成完就先别发，免得空列表又被缓存住） */
    public static boolean thumbnailsReady() {
        return THUMBS_BUSY.isEmpty();
    }

    /** 名字 → PNG 字节（可能还没生成完，调用方按需取） */
    public static Map<String, byte[]> thumbnails() {
        return THUMBS;
    }

    /**
     * 后台给缺缩略图的蓝图生成小图。
     *
     * <p>活从 Sable 的预览接口拿：{@code SableBlueprint.preview().pixels(View.ISO_XP_ZP)} 是 512×512 的 ARGB
     * 数组；没有预览的（比如几份老图纸）用 {@code SableBlueprintPreviewGenerator.generate(subLevels())} 现生成。
     * 整个流程要在后台线程跑：解压一张是 1MB 的像素，十几张就是几十毫秒到几百毫秒，
     * 放服务器线程上会卡 tick。</p>
     */
    public static void warmThumbnailsAsync() {
        probe();
        if (!available) {
            return;
        }
        List<Entry> todo = new ArrayList<>();
        for (Entry entry : ENTRIES.values()) {
            if (!THUMBS.containsKey(entry.name()) && THUMBS_BUSY.add(entry.name())) {
                todo.add(entry);
            }
        }
        if (todo.isEmpty()) {
            return;
        }
        Thread worker = new Thread(() -> {
            for (Entry entry : todo) {
                try {
                    byte[] png = renderThumbnail(entry.blueprint());
                    if (png != null) {
                        THUMBS.put(entry.name(), png);
                    }
                } catch (Throwable t) {
                    LOGGER.warn("[深空铸造体] 图纸 {} 的缩略图生成失败：{}", entry.name(), rootOf(t));
                } finally {
                    THUMBS_BUSY.remove(entry.name());
                    THUMBS_DONE.incrementAndGet();
                }
            }
            LOGGER.info("[深空铸造体] 图纸缩略图生成完成（{} 张）", THUMBS.size());
        }, "dsd-blueprint-thumbs");
        worker.setDaemon(true);
        worker.start();
    }

    /** 一张蓝图 → 64×64 PNG 字节；拿不到预览就返回 null */
    private static byte[] renderThumbnail(Object blueprint) throws Exception {
        if (previewMethod == null) {
            return null;
        }
        Object preview = previewMethod.invoke(blueprint);
        if (preview == null && subLevelsMethod != null && generatorMethod != null) {
            // 老图纸没有预览：让 Sable 自己按方块数据生成一份
            Object subs = subLevelsMethod.invoke(blueprint);
            preview = generatorMethod.invoke(null, subs);
        }
        if (preview == null) {
            return null;
        }
        int[] pixels = (int[]) pixelsMethod.invoke(preview, ISO_VIEW);
        int res = (int) resolutionMethod.invoke(preview);
        if (pixels == null || res <= 0 || pixels.length < res * res) {
            return null;
        }
        java.awt.image.BufferedImage full = new java.awt.image.BufferedImage(res, res,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        full.setRGB(0, 0, res, res, pixels, 0, res);
        java.awt.image.BufferedImage small = new java.awt.image.BufferedImage(THUMB_SIZE, THUMB_SIZE,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = small.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(full, 0, 0, THUMB_SIZE, THUMB_SIZE, null);
        g.dispose();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(small, "png", out);
        return out.toByteArray();
    }

    /** 重新扫描两个文件夹，返回读到的蓝图数量 */
    @SuppressWarnings("unchecked")
    public static int refresh(MinecraftServer server) {        probe();
        ENTRIES.clear();
        if (!available) {
            infos = List.of();
            return 0;
        }

        // 1) Sable-Schematics（模组自己的接口，名字就是文件名）
        try {
            Object listed = rootList.invoke(null, server);
            if (listed instanceof Iterable<?> names) {
                for (Object raw : names) {
                    String name = String.valueOf(raw);
                    try {
                        Object blueprint = rootLoad.invoke(null, server, name);
                        addEntry(name, blueprint);
                    } catch (Throwable t) {
                        LOGGER.warn("[深空铸造体] 蓝图 {} 读取失败：{}", name, rootOf(t));
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[深空铸造体] 读 Sable-Schematics 失败：{}", rootOf(t));
        }

        // 2) enxv_aeronautics_structures（我们自己读 nbt，再交给 SableBlueprint.load）
        Path extra = FMLPaths.GAMEDIR.get().resolve(EXTRA_DIR);
        if (Files.isDirectory(extra)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(extra)) {
                for (Path path : stream) {
                    String fileName = path.getFileName().toString();
                    if (!Files.isRegularFile(path)) {
                        continue;
                    }
                    String name = fileName.replaceFirst("(?i)\\.(nbt|excraft|schem)$", "");
                    if (ENTRIES.containsKey(name)) {
                        continue;
                    }
                    try {
                        CompoundTag tag = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
                        Object blueprint = loadFromTag.invoke(null, tag);
                        addEntry(name, blueprint);
                    } catch (Throwable t) {
                        LOGGER.warn("[深空铸造体] {} 里的 {} 读不了（也许不是 Sable 蓝图）：{}",
                                EXTRA_DIR, fileName, rootOf(t));
                    }
                }
            } catch (Throwable t) {
                LOGGER.warn("[深空铸造体] 扫描 {} 失败：{}", EXTRA_DIR, t.toString());
            }
        }

        List<BlueprintInfo> list = new ArrayList<>();
        for (Entry entry : ENTRIES.values()) {
            list.add(new BlueprintInfo(entry.name(), entry.sizeX(), entry.sizeY(), entry.sizeZ(),
                    entry.materials()));
        }
        list.sort(Comparator.comparing(BlueprintInfo::name));
        infos = List.copyOf(list);
        // 图纸集合变了：缩略图缓存作废，后台重新生成
        THUMBS.clear();
        THUMBS_BUSY.clear();
        thumbnailGeneration++;
        warmThumbnailsAsync();
        LOGGER.info("[深空铸造体] 物理蓝图已加载：{} 份（{}+{}）", list.size(), "Sable-Schematics", EXTRA_DIR);
        return list.size();
    }

    @SuppressWarnings("unchecked")
    private static void addEntry(String name, Object blueprint) throws Exception {
        if (blueprint == null) {
            return;
        }
        Map<String, Integer> materials = new LinkedHashMap<>();
        int blocks = 0;
        int sizeX = 1;
        int sizeY = 1;
        int sizeZ = 1;

        for (Object sub : (List<Object>) subLevels.invoke(blueprint)) {
            List<BlockState> palette = (List<BlockState>) blockPalette.invoke(sub);
            List<Object> blockList = (List<Object>) blocksMethod.invoke(sub);
            for (Object blockData : blockList) {
                blocks++;
                int id = (int) paletteId.invoke(blockData);
                if (id < 0 || id >= palette.size()) {
                    continue;
                }
                BlockState state = palette.get(id);
                if (state.isAir()) {
                    continue;
                }
                Item item = state.getBlock().asItem();
                if (item == Items.AIR) {
                    continue;
                }
                materials.merge(BuiltInRegistries.ITEM.getKey(item).toString(), 1, Integer::sum);
            }
        }

        // 尺寸：优先用蓝图的总体包围盒
        try {
            Method bounds = blueprint.getClass().getMethod(hasCanonical(blueprint) ? "canonicalBounds" : "rootBounds");
            Object box = bounds.invoke(blueprint);
            if (box != null) {
                sizeX = (int) Math.max(1L, Math.round(num(box, "maxX") - num(box, "minX") + 1));
                sizeY = (int) Math.max(1L, Math.round(num(box, "maxY") - num(box, "minY") + 1));
                sizeZ = (int) Math.max(1L, Math.round(num(box, "maxZ") - num(box, "minZ") + 1));
            }
        } catch (Throwable ignored) {
            // 拿不到就用默认尺寸，不影响功能
        }

        ENTRIES.put(name, new Entry(name, blueprint, blocks, sizeX, sizeY, sizeZ, materials));
        LOGGER.info("[深空铸造体] 物理蓝图 {} 已读取：{} 方块，尺寸 {}×{}×{}，{} 种材料",
                name, blocks, sizeX, sizeY, sizeZ, materials.size());
    }

    private static boolean hasCanonical(Object blueprint) {
        try {
            Object value = blueprint.getClass().getMethod("hasCanonicalBounds").invoke(blueprint);
            return Boolean.TRUE.equals(value);
        } catch (Throwable t) {
            return false;
        }
    }

    private static double num(Object box, String field) throws Exception {
        Field f = box.getClass().getField(field);
        return f.getDouble(box);
    }

    private static Throwable rootOf(Throwable t) {
        return t.getCause() == null ? t : t.getCause();
    }

    // ---------------------------------------------------------------- 对外

    public static List<BlueprintInfo> all() {
        return infos;
    }

    public static BlueprintInfo get(String name) {
        for (BlueprintInfo info : infos) {
            if (info.name().equals(name)) {
                return info;
            }
        }
        return null;
    }

    public static Entry entry(String name) {
        return ENTRIES.get(name);
    }

    /** 把蓝图直接生成成物理结构（位置是结构中心） */
    public static Result spawn(ServerLevel level, String name, Vec3 center) {
        probe();
        Entry entry = ENTRIES.get(name);
        if (!available || entry == null) {
            return new Result(false, 0, available ? "没有这份蓝图：" + name : "缺少 Sable Photomancy");
        }
        try {
            Object result = placerPlace.invoke(null, level, entry.blueprint(), center);
            int placed = result == null ? 0 : (int) resultPlaced.invoke(result);
            if (placed <= 0) {
                return new Result(false, 0, "生成失败（蓝图可能没有有效内容）");
            }
            return new Result(true, placed, "已生成 " + placed + " 个物理结构");
        } catch (Throwable t) {
            Throwable cause = rootOf(t);
            String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            LOGGER.warn("[深空铸造体] 生成物理结构失败：{}", message);
            return new Result(false, 0, message);
        }
    }

    /** 某个方块位置能不能放下（按蓝图里的方块位置检查） */
    @SuppressWarnings("unchecked")
    public static boolean areaClear(ServerLevel level, String name, BlockPos origin) {
        Entry entry = ENTRIES.get(name);
        if (entry == null) {
            return false;
        }
        try {
            for (Object sub : (List<Object>) subLevels.invoke(entry.blueprint())) {
                for (Object blockData : (List<Object>) blocksMethod.invoke(sub)) {
                    BlockPos local = (BlockPos) localPos.invoke(blockData);
                    if (!level.getBlockState(origin.offset(local)).canBeReplaced()) {
                        return false;
                    }
                }
            }
            return true;
        } catch (Throwable t) {
            return true;   // 检查本身出错就不拦着玩家
        }
    }

    private PhysicsBlueprintLibrary() {
    }
}
