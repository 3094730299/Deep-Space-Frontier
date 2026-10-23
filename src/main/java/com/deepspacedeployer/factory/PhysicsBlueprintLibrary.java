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
import java.io.IOException;
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
    /**
     * 除了 Sable-Schematics（走 Sable 官方接口）之外，<b>还要扫这些"玩家自己的"文件夹</b>。
     *
     * <p>⚠️ 必须是<b>游戏目录（FMLPaths.GAMEDIR）下的一级目录名</b>。
     * 加新文件夹就往这个数组里塞名字 —— 扫描逻辑和容错都在 {@link #scanBlueprintFolder}。</p>
     *
     * <p>2026-10-17 老板要求"让玩家能看到自己存在文件夹里的载具"时补的：
     * {@code schematics} 是<b>机械动力</b>的图纸夹，玩家习惯把载具放那儿，
     * 但之前只有 {@code Sable-Schematics} 会被读 —— 于是"自己存的载具看不见"。</p>
     */
    private static final String[] USER_DIRS = {
            "enxv_aeronautics_structures",   // 别人给的航空结构包
            "schematics",                    // 机械动力（Create）的图纸夹 —— 玩家最常用的那个
    };

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
        return "Sable-Schematics / " + String.join(" / ", USER_DIRS) + "（都在游戏目录下）";
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
    /**
     * 扫一个"玩家自己的"蓝图文件夹：把里面的 {@code .nbt / .excraft / .schem} 逐个读出来，
     * 交给 Sable 的 {@code SableBlueprint.load(CompoundTag)} 解析。
     *
     * <p>⚠️ 读不了的文件<b>只打 WARN 跳过</b> —— 一个坏文件/一张不兼容的图纸，
     * 不该让整个蓝图列表消失（这个项目以前就踩过"一个失败拖垮一片"的坑）。</p>
     *
     * @param dirName 游戏目录下的一级文件夹名，例如 {@code schematics}
     */
    private static void scanBlueprintFolder(String dirName) {
        Path dir = FMLPaths.GAMEDIR.get().resolve(dirName);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path path : stream) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                String fileName = path.getFileName().toString();
                String name = fileName.replaceFirst("(?i)\\.(nbt|excraft|schem)$", "");
                if (ENTRIES.containsKey(name)) {
                    continue;                   // 前面已经有同名的了（Sable-Schematics 优先）
                }
                try {
                    CompoundTag tag = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
                    Object blueprint = loadFromTag.invoke(null, tag);
                    addEntry(name, blueprint);
                } catch (Throwable t) {
                    LOGGER.warn("[深空铸造体] {} 里的 {} 读不了（也许不是 Sable 蓝图）：{}",
                            dirName, fileName, rootOf(t));
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[深空铸造体] 扫描 {} 失败：{}", dirName, t.toString());
        }
    }

    /** 服务端预制载具目录：config/deepspacedeployer/vehicles/*.nbt */
    private static void scanVehiclePresetFolder() {
        Path dir = FMLPaths.CONFIGDIR.get().resolve("deepspacedeployer").resolve("vehicles");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOGGER.warn("[深空铸造体] 建不了预制载具目录 {}：{}", dir, e.toString());
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.nbt")) {
            for (Path path : stream) {
                String name = path.getFileName().toString().replaceFirst("(?i)\\.nbt$", "");
                if (ENTRIES.containsKey(name)) {
                    continue;
                }
                Entry entry = loadExternal(name, path);
                if (entry != null) {
                    ENTRIES.put(name, entry);
                    LOGGER.info("[深空铸造体] 预制载具 {} 已读取：{} 方块", name, entry.blocks());
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[深空铸造体] 扫描预制载具目录失败：{}", e.toString());
        }
    }

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

        // 2) 游戏目录下的其它蓝图文件夹（我们自己读 nbt，再交给 SableBlueprint.load）
        //
        // ★ 2026-10-17 老板要求："让玩家能看到自己存在文件夹里的载具，而不是只能打印服务端的。"
        //   实测他的实例里蓝图分两个地方放：
        //     versions\孢子危机\Sable-Schematics\   7 个 .nbt   ← 上面第 1 段（Sable 官方接口）读得到
        //     versions\孢子危机\schematics\         10 个 .nbt  ← 机械动力的图纸夹，原先【没人读】
        //   所以这里把 USER_DIRS 里每个文件夹都扫一遍（以前只扫 enxv_aeronautics_structures）。
        //   读不了的会打 WARN 跳过，不影响其它文件。
        for (String dirName : USER_DIRS) {
            scanBlueprintFolder(dirName);
        }

        // 3) 服务端预制载具：config/deepspacedeployer/vehicles/*.nbt
        //    模块打印机的预设走 modules/，两边从目录上就分开。
        scanVehiclePresetFolder();

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
        LOGGER.info("[深空铸造体] 物理蓝图已加载：{} 份（来源：Sable-Schematics + {}）",
                list.size(), String.join(" + ", USER_DIRS));
        return list.size();
    }

    @SuppressWarnings("unchecked")
    private static Entry buildEntry(String name, Object blueprint) throws Exception {
        if (blueprint == null) {
            return null;
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

        return new Entry(name, blueprint, blocks, sizeX, sizeY, sizeZ, materials);
    }

    @SuppressWarnings("unchecked")
    private static void addEntry(String name, Object blueprint) throws Exception {
        Entry entry = buildEntry(name, blueprint);
        if (entry == null) {
            return;
        }
        ENTRIES.put(name, entry);
        LOGGER.info("[深空铸造体] 物理蓝图 {} 已读取：{} 方块，尺寸 {}×{}×{}，{} 种材料",
                name, entry.blocks(), entry.sizeX(), entry.sizeY(), entry.sizeZ(), entry.materials().size());
    }

    /**
     * 读取一个外部 Sable {@code .nbt}，但不放进"载具蓝图"列表。
     * 模块打印机专用：这样玩家的载具蓝图和模块预设从数据层就分开了。
     */
    public static Entry loadExternal(String name, Path path) {
        probe();
        if (!available) {
            return null;
        }
        try {
            CompoundTag tag = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
            Object blueprint = loadFromTag.invoke(null, tag);
            return buildEntry(name, blueprint);
        } catch (Throwable t) {
            LOGGER.warn("[深空铸造体] 外部蓝图 {} 读取失败：{}", path, rootOf(t).toString());
            return null;
        }
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

    /** 把一份 Entry 直接生成成物理结构（不要求它在载具蓝图列表里）。 */
    public static Result spawnEntry(ServerLevel level, Entry entry, Vec3 center) {
        probe();
        if (!available || entry == null) {
            return new Result(false, 0, available ? "没有这份蓝图" : "缺少 Sable Photomancy");
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

    /** 某份 Entry 在某个方块位置能不能放下。 */
    @SuppressWarnings("unchecked")
    public static boolean areaClearEntry(ServerLevel level, Entry entry, BlockPos origin) {
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
            return true;
        }
    }

    /** 把蓝图直接生成成物理结构（位置是结构中心） */
    public static Result spawn(ServerLevel level, String name, Vec3 center) {
        probe();
        Entry entry = ENTRIES.get(name);
        if (!available || entry == null) {
            return new Result(false, 0, available ? "没有这份蓝图：" + name : "缺少 Sable Photomancy");
        }
        return spawnEntry(level, entry, center);
    }

    /** 某个方块位置能不能放下（按蓝图里的方块位置检查） */
    public static boolean areaClear(ServerLevel level, String name, BlockPos origin) {
        return areaClearEntry(level, ENTRIES.get(name), origin);
    }

    private PhysicsBlueprintLibrary() {
    }
}
