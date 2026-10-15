package com.deepspacedeployer.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端：图纸缩略图（64×64）缓存。
 *
 * <p>服务端发来的是 PNG 字节，这里解成纹理并登记到纹理管理器，界面按名字取。
 * 每次收到新的一包就整包替换（换图纸/重读之后不会残留旧图）。</p>
 */
public final class ClientBlueprintThumbs {

    /** 名字 → 纹理 id */
    private static Map<String, ResourceLocation> thumbs = Map.of();
    /** 已经上传来但还是当前这版的纹理，收新包时释放 */
    private static final Map<String, ResourceLocation> LOADED = new HashMap<>();

    public static void accept(List<String> names, List<byte[]> pngs) {
        if (names == null || pngs == null || names.size() != pngs.size()) {
            return;
        }
        releaseAll();
        Map<String, ResourceLocation> next = new HashMap<>();
        for (int i = 0; i < names.size(); i++) {
            byte[] data = pngs.get(i);
            if (data == null || data.length == 0) {
                continue;
            }
            ResourceLocation id = load(names.get(i), data);
            if (id != null) {
                next.put(names.get(i), id);
            }
        }
        thumbs = Map.copyOf(next);
    }

    private static ResourceLocation load(String name, byte[] png) {
        try (ByteArrayInputStream in = new ByteArrayInputStream(png)) {
            NativeImage image = NativeImage.read(in);
            DynamicTexture texture = new DynamicTexture(image);
            String safe = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                    "deepspacedeployer", "blueprint_thumb/" + safe + "_" + Integer.toHexString(png.length));
            Minecraft.getInstance().getTextureManager().register(id, texture);
            LOADED.put(name, id);
            return id;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 这张图纸的缩略图；没有就返回 null（界面画个占位） */
    public static ResourceLocation get(String blueprintName) {
        return thumbs.get(blueprintName);
    }

    public static void releaseAll() {
        for (ResourceLocation id : LOADED.values()) {
            Minecraft.getInstance().getTextureManager().release(id);
        }
        LOADED.clear();
        thumbs = Map.of();
    }

    private ClientBlueprintThumbs() {
    }
}
