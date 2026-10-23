package com.deepspacedeployer.factory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.deepspacedeployer.Company;
import com.deepspacedeployer.Tier;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 模块打印机预设库。
 *
 * <p>目录：{@code config/deepspacedeployer/modules/}</p>
 * <ul>
 *   <li><b>必填</b>：{@code <名字>.nbt} —— Sable 蓝图本体；</li>
 *   <li><b>可选</b>：{@code <名字>.json} —— 显示名、价格、最低等级、可用公司。</li>
 * </ul>
 *
 * <p>这个库和载具蓝图库完全分开：载具打印机不会列这里的模块，
 * 模块打印机也不会读玩家自己的 Sable 蓝图。</p>
 */
public final class ModulePresetLibrary {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile List<Preset> presets = List.of();

    /**
     * @param name    文件名（不含 .nbt），也是选择/打印用的 key
     * @param display 界面显示名
     * @param price   价格（模块打印机所属公司的货币）
     * @param tier    最低等级
     * @param company 限定公司 id；空字符串 = 所有公司都能打印
     * @param entry   Sable 蓝图数据（只在服务端持有）
     */
    public record Preset(String name, String display, long price, Tier tier, String company,
                         PhysicsBlueprintLibrary.Entry entry) {

        public boolean availableTo(Company printerCompany, Tier printerTier) {
            if (printerTier.rank() < tier.rank()) {
                return false;
            }
            return company == null || company.isEmpty() || company.equals(printerCompany.id());
        }

        public String sizeText() {
            return entry.sizeX() + "×" + entry.sizeY() + "×" + entry.sizeZ();
        }

        public int blocks() {
            return entry.blocks();
        }
    }

    public static Path folder() {
        return FMLPaths.CONFIGDIR.get().resolve("deepspacedeployer").resolve("modules");
    }

    /** 重读模块预设目录；返回成功加载的模块数量。 */
    public static int reload(MinecraftServer server) {
        Path dir = folder();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOGGER.warn("[深空铸造体] 建不了模块预设目录 {}：{}", dir, e.toString());
        }
        if (!PhysicsBlueprintLibrary.available()) {
            presets = List.of();
            return 0;
        }
        List<Preset> found = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.nbt")) {
            for (Path path : stream) {
                try {
                    String fileName = path.getFileName().toString();
                    String name = fileName.replaceFirst("(?i)\\.nbt$", "");
                    PhysicsBlueprintLibrary.Entry entry = PhysicsBlueprintLibrary.loadExternal(name, path);
                    if (entry == null) {
                        continue;
                    }
                    Preset preset = readMeta(name, entry, dir.resolve(name + ".json"));
                    found.add(preset);
                } catch (Throwable t) {
                    LOGGER.warn("[深空铸造体] 模块预设 {} 读取失败（已跳过）：{}",
                            path.getFileName(), t.toString());
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[深空铸造体] 扫描模块预设目录失败：{}", e.toString());
        }
        found.sort(Comparator.comparing(Preset::display, String.CASE_INSENSITIVE_ORDER));
        presets = List.copyOf(found);
        LOGGER.info("[深空铸造体] 模块预设已加载：{} 份（目录 {}）", found.size(), dir);
        return found.size();
    }

    private static Preset readMeta(String name, PhysicsBlueprintLibrary.Entry entry, Path jsonPath) {
        String display = name;
        long price = 5000L;
        Tier tier = Tier.LOW;
        String company = "";
        if (Files.isRegularFile(jsonPath)) {
            try {
                JsonObject json = JsonParser.parseString(Files.readString(jsonPath)).getAsJsonObject();
                if (json.has("display")) {
                    display = json.get("display").getAsString();
                }
                if (json.has("price")) {
                    price = Math.max(0L, json.get("price").getAsLong());
                }
                if (json.has("tier")) {
                    tier = Tier.byId(json.get("tier").getAsString());
                }
                if (json.has("company")) {
                    company = json.get("company").getAsString();
                }
            } catch (Throwable t) {
                LOGGER.warn("[深空铸造体] 模块预设 {} 的 json 读不了，用默认值：{}", name, t.toString());
            }
        }
        return new Preset(name, display, price, tier, company, entry);
    }

    public static List<Preset> all() {
        return presets;
    }

    public static Preset find(String name) {
        for (Preset preset : presets) {
            if (preset.name().equals(name)) {
                return preset;
            }
        }
        return null;
    }

    public static List<Preset> visible(Company company, Tier tier) {
        List<Preset> out = new ArrayList<>();
        for (Preset preset : presets) {
            if (preset.availableTo(company, tier)) {
                out.add(preset);
            }
        }
        return out;
    }

    private ModulePresetLibrary() {
    }
}
