package com.deepspacedeployer.ui.webview;

import com.deepspacedeployer.DeepSpaceDeployer;
import com.deepspacedeployer.ui.theme.ThemeManager;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * <b>主题图标缓存</b>：把 {@code index.json} 里 {@code icon} 指的 PNG 变成能画的贴图。
 *
 * <p>主题图标不是资源包资源（是游戏目录里的普通文件），所以只能解码成
 * {@link DynamicTexture} 再注册一个临时名字。按<b>主题名</b>缓存：
 * 一个主题只会解码一次，界面关了再统一释放（{@link #releaseAll()}）。</p>
 *
 * <p>找不到图标没关系 —— 调用方画占位方块即可（主题是玩家自己写的，不能因此崩）。</p>
 */
public final class ThemeIcons {

    private static final Map<String, ResourceLocation> CACHE = new HashMap<>();
    private static int seq;

    private ThemeIcons() {
    }

    /**
     * 某套主题的图标贴图。
     *
     * @return 贴图名；这套主题没有图标（或图片坏了）返回 null
     */
    public static ResourceLocation get(String themeName) {
        if (themeName == null) {
            return null;
        }
        synchronized (CACHE) {
            if (CACHE.containsKey(themeName)) {
                return CACHE.get(themeName);
            }
        }
        ResourceLocation loc = null;
        try {
            byte[] png = ThemeManager.iconBytes(themeName);
            if (png != null && png.length > 0) {
                NativeImage image = NativeImage.read(new ByteArrayInputStream(png));
                if (image != null) {
                    DynamicTexture tex = new DynamicTexture(image);
                    loc = ResourceLocation.fromNamespaceAndPath(
                            DeepSpaceDeployer.MOD_ID, "theme_icon_" + (seq++));
                    Minecraft.getInstance().getTextureManager().register(loc, tex);
                }
            }
        } catch (Throwable t) {
            DeepSpaceDeployer.LOGGER.debug("[深空铸造体][界面] 主题图标读取失败（{}）：{}",
                    themeName, t.toString());
            loc = null;
        }
        synchronized (CACHE) {
            CACHE.put(themeName, loc);
        }
        return loc;
    }

    /** 主题文件改过之后清缓存（下次重新解码） */
    public static void invalidate() {
        releaseAll();
    }

    /** 释放所有图标贴图（界面关闭时调一次） */
    public static void releaseAll() {
        synchronized (CACHE) {
            for (ResourceLocation loc : CACHE.values()) {
                if (loc == null) {
                    continue;
                }
                try {
                    Minecraft.getInstance().getTextureManager().release(loc);
                } catch (Throwable ignored) {
                    // 释放失败不影响功能
                }
            }
            CACHE.clear();
        }
    }
}
