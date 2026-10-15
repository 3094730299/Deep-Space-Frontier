package com.deepspacedeployer.factory;

import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 蓝图库：读服务器文件夹里的机械动力蓝图（.nbt）。
 *
 * <p>文件夹：{@code config/deepspacedeployer/blueprints/*.nbt}
 * —— 服务器管理员放文件，玩家在工厂核心界面里选。</p>
 *
 * <p>服务器启动时自动读取；改完文件可以用管理员指令 {@code /dsd reloadblueprints} 重新读。</p>
 */
public final class BlueprintLibrary {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FOLDER = "deepspacedeployer";
    private static final String SUB_FOLDER = "blueprints";

    private static volatile List<BlueprintInfo> blueprints = List.of();
    private static final Map<String, ParsedBlueprint> PARSED = new ConcurrentHashMap<>();

    public static Path folder() {
        return FMLPaths.CONFIGDIR.get().resolve(FOLDER).resolve(SUB_FOLDER);
    }

    /** 重新扫描文件夹，返回读到的蓝图数量 */
    public static int loadAll(MinecraftServer server) {
        Path dir = folder();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOGGER.warn("[深空铸造体] 建不了蓝图文件夹 {}：{}", dir, e.toString());
        }

        HolderGetter<Block> blocks = server.registryAccess().lookupOrThrow(Registries.BLOCK);
        List<BlueprintInfo> found = new ArrayList<>();
        Map<String, ParsedBlueprint> parsed = new HashMap<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.nbt")) {
            for (Path path : stream) {
                try {
                    CompoundTag tag = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
                    ParsedBlueprint blueprint = BlueprintParser.parse(tag, blocks);
                    if (blueprint == null) {
                        LOGGER.warn("[深空铸造体] 蓝图 {} 解析失败（不是合法的结构 NBT？），已跳过", path.getFileName());
                        continue;
                    }
                    String name = path.getFileName().toString().replaceFirst("(?i)\\.nbt$", "");
                    found.add(blueprint.toInfo(name));
                    parsed.put(name, blueprint);
                    LOGGER.info("[深空铸造体] 蓝图 {} 已读取：{}×{}×{}，{} 个方块，{} 种材料",
                            name, blueprint.sizeX(), blueprint.sizeY(), blueprint.sizeZ(),
                            blueprint.totalBlocks(), blueprint.materials().size());
                } catch (Exception e) {
                    LOGGER.warn("[深空铸造体] 蓝图 {} 读取失败（已跳过）：{}", path.getFileName(), e.toString());
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[深空铸造体] 读蓝图文件夹失败：{}", e.toString());
        }

        found.sort(Comparator.comparing(BlueprintInfo::name));
        blueprints = List.copyOf(found);
        PARSED.clear();
        PARSED.putAll(parsed);
        return found.size();
    }

    public static List<BlueprintInfo> all() {
        return blueprints;
    }

    public static BlueprintInfo get(String name) {
        for (BlueprintInfo info : blueprints) {
            if (info.name().equals(name)) {
                return info;
            }
        }
        return null;
    }

    /** 解析好的蓝图（服务端专有，含方块坐标与原始 NBT） */
    public static ParsedBlueprint parsed(String name) {
        return PARSED.get(name);
    }

    private BlueprintLibrary() {
    }
}
