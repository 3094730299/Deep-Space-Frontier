package com.deepspacedeployer.ui.webview;

import com.deepspacedeployer.DeepSpaceDeployer;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * <b>任意图片文件的贴图缓存</b>（给 UI 编辑器预览用）。
 *
 * <p>和 {@link ThemeIcons} 的区别：那边按键是"主题名"、走 {@code index.json}；
 * 这边直接按<b>磁盘路径</b>，任何 png/jpg 都能预览。</p>
 *
 * <p>缓存键是 {@code 路径 + 修改时间 + 大小}：文件一改（重命名、覆盖）就自动重新解码，
 * 不用手动清缓存。界面关掉时调 {@link #releaseAll()}。</p>
 */
public final class ThemeImages {

    private static final Map<String, Entry> CACHE = new HashMap<>();
    private static int seq;

    private record Entry(ResourceLocation loc, long mtime, long size) {
    }

    private ThemeImages() {
    }

    /** 图片贴图；格式不支持 / 文件读不了返回 null（调用方画占位） */
    public static ResourceLocation get(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            long mtime = Files.getLastModifiedTime(file).toMillis();
            long size = Files.size(file);
            String key = file.toAbsolutePath().normalize().toString();
            synchronized (CACHE) {
                Entry hit = CACHE.get(key);
                if (hit != null && hit.mtime() == mtime && hit.size() == size) {
                    return hit.loc();
                }
            }
            NativeImage image;
            try (InputStream in = Files.newInputStream(file)) {
                image = NativeImage.read(in);
            }
            if (image == null) {
                return null;
            }
            DynamicTexture tex = new DynamicTexture(image);
            ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(
                    DeepSpaceDeployer.MOD_ID, "editor_img_" + (seq++));
            Minecraft.getInstance().getTextureManager().register(loc, tex);
            synchronized (CACHE) {
                CACHE.put(key, new Entry(loc, mtime, size));
            }
            return loc;
        } catch (Throwable t) {
            DeepSpaceDeployer.LOGGER.debug("[深空铸造体][编辑器] 图片读取失败（{}）：{}",
                    file.getFileName(), t.toString());
            return null;
        }
    }

    /** 释放所有预览贴图（编辑器关闭时调） */
    public static void releaseAll() {
        synchronized (CACHE) {
            for (Entry e : CACHE.values()) {
                try {
                    Minecraft.getInstance().getTextureManager().release(e.loc());
                } catch (Throwable ignored) {
                    // 释放失败不影响功能
                }
            }
            CACHE.clear();
        }
    }
}
