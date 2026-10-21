package com.deepspacedeployer.ui.webview;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.deepspacedeployer.DeepSpaceDeployer;
import com.deepspacedeployer.ui.theme.Canvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * <b>游戏内画布</b>：把主题渲染到 {@link GuiGraphics} 上。
 *
 * <p>这是"离线预览"和"游戏内"唯一的第二份实现（另一份是 {@code PreviewCanvas}）。
 * 两边的<b>排版规则必须一致</b>，做法是：</p>
 *
 * <ul>
 *   <li><b>字宽</b>照抄官方点阵字体的度量（半角 6px / 全角 9px，字号 9 基准，按字号等比缩放），
 *       这样离线预览里的换行位置和游戏里一致；</li>
 *   <li>下划线、删除线之类的"花字"不受影响（主题里只写纯文本）；</li>
 *   <li>坐标一律取整：原版在非整数坐标上画字会发糊。</li>
 * </ul>
 *
 * <p><b>图片</b>走动态贴图：主题是一堆 PNG 字节，不是资源包里的资源，
 * 所以把字节解码成 {@link DynamicTexture} 再注册一个临时贴图名。
 * 缓存键是<b>PNG 字节的内容哈希</b>（不是路径、也不是 byte[] 的同一性）——
 * 因为渲染器每帧都会重新读一遍文件、每次都是新的 byte[]，
 * 用同一性当键会让缓存永远命中不了、每帧都新建贴图，最终把显存吃光。</p>
 *
 * <p>缓存有上限（{@value #MAX_TEXTURES} 张），超出后整批清空重建，避免无限增长。</p>
 */
public final class GuiCanvas implements Canvas {

    /** 同时缓存的贴图上限（界面里的图片数量远小于这个数） */
    private static final int MAX_TEXTURES = 128;

    /** 原版字号 9 时：半角一格 6px、全角一格 9px（和 PreviewCanvas 保持一致） */
    private static final float HALF_ADVANCE = 6f;
    private static final float FULL_ADVANCE = 9f;
    private static final float BASE_SIZE = 9f;

    private final GuiGraphics graphics;
    /** 整个主题画布在屏幕上的位置（主题坐标 → 屏幕坐标的平移量） */
    private final float originX;
    private final float originY;
    /**
     * <b>整体缩放</b>（1 = 原尺寸）。
     *
     * <p>主题永远按 288×300 的逻辑尺寸布局；屏幕装不下时宿主传一个 &lt;1 的比例，
     * 这里把所有坐标乘上去。这样界面永远完整可见，不用再"夹画布尺寸" ——
     * 夹尺寸会让玩家背包（按画布底边定位）顶到列表上。</p>
     */
    private final float scale;

    private final Deque<float[]> offsets = new ArrayDeque<>();
    private float offX, offY;
    /** 裁切栈深度：每次 pushClip 先 enableScissor，popClip 时用上一次的矩形恢复 */
    private final Deque<float[]> clipStack = new ArrayDeque<>();

    /** PNG 内容哈希 → 贴图（跨帧共享，见 {@link #sharedTextures()}） */
    private static final java.util.Map<Long, CachedTexture> SHARED_TEXTURES =
            new java.util.LinkedHashMap<>();
    /** 自增序号：给临时贴图起唯一名字（全局唯一，跨画布也是） */
    private static int textureSeq;

    public GuiCanvas(GuiGraphics graphics, float originX, float originY) {
        this(graphics, originX, originY, 1f);
    }

    public GuiCanvas(GuiGraphics graphics, float originX, float originY, float scale) {
        this.graphics = graphics;
        this.originX = originX;
        this.originY = originY;
        this.scale = scale <= 0f ? 1f : scale;
    }

    /** 逻辑坐标 → 屏幕坐标（含整体缩放） */
    private float sx(float v) {
        return v * scale + offX * scale + originX;
    }

    private float sy(float v) {
        return v * scale + offY * scale + originY;
    }

    /**
     * 跨帧共享的贴图表。
     *
     * <p><b>为什么是静态的</b>：宿主界面每帧都会 new 一个画布（为了重置偏移和裁切栈），
     * 如果贴图缓存跟着画布走，就等于每帧把同一批 PNG 重新解码 + 重新上传显存 ——
     * 60 次/秒，纯浪费。所以缓存放在静态表里，<b>按内容哈希命中</b>。</p>
     *
     * <p>表有上限（{@value #MAX_TEXTURES} 张）：满了就整批释放重建
     * （主题界面的图片总数远小于这个数，实际几乎不会触发）。</p>
     */
    private static java.util.Map<Long, CachedTexture> sharedTextures() {
        return SHARED_TEXTURES;
    }

    // ---------------------------------------------------------------- 量文字

    /**
     * 一段文字的宽度。
     *
     * <p>取"我们自己的度量"和"原版字体的真实宽度"里<b>较大</b>的那个：
     * 前者保证和离线预览一致，后者防止某些字符在原版字体里更宽而溢出容器。</p>
     */
    @Override
    public float textWidth(String text, float size, boolean bold) {
        if (text == null || text.isEmpty()) {
            return 0f;
        }
        float own = 0f;
        for (int i = 0; i < text.length(); i++) {
            own += isWide(text.charAt(i)) ? FULL_ADVANCE : HALF_ADVANCE;
        }
        own *= size / BASE_SIZE;
        int vanilla = font().width(text);
        return Math.max(own, vanilla);
    }

    @Override
    public float textHeight(float size) {
        // 和 PreviewCanvas 一致（size + 1），同时不小于原版行高，避免两行字叠在一起
        return Math.max(size + 1f, font().lineHeight);
    }

    // ---------------------------------------------------------------- 画

    @Override
    public void fillRect(float x, float y, float w, float h, int argb) {
        if (w <= 0f || h <= 0f || alphaOf(argb) == 0) {
            return;
        }
        int x0 = Math.round(sx(x));
        int y0 = Math.round(sy(y));
        int x1 = Math.round(sx(x + w));
        int y1 = Math.round(sy(y + h));
        graphics.fill(x0, y0, x1, y1, argb);
    }

    /**
     * 圆角矩形。
     *
     * <p>原版没有圆角原语，这里用"三条矩形"近似（上下各缩进 radius 的窄条 + 中间满宽条），
     * 圆角处出一格台阶。像素画界面里看不出来，比 Cairo/Java2D 的重实现安全得多。</p>
     */
    @Override
    public void fillRoundRect(float x, float y, float w, float h, float radius, int argb) {
        if (radius <= 0f || w <= 2f * radius || h <= 2f * radius) {
            fillRect(x, y, w, h, argb);
            return;
        }
        fillRect(x + radius, y, w - 2f * radius, radius, argb);                 // 上
        fillRect(x, y + radius, w, h - 2f * radius, argb);                      // 中
        fillRect(x + radius, y + h - radius, w - 2f * radius, radius, argb);     // 下
    }

    @Override
    public void strokeRect(float x, float y, float w, float h, float lineWidth, int argb) {
        float t = Math.max(1f, lineWidth);
        fillRect(x, y, w, t, argb);                     // 上
        fillRect(x, y + h - t, w, t, argb);             // 下
        fillRect(x, y + t, t, h - 2f * t, argb);        // 左
        fillRect(x + w - t, y + t, t, h - 2f * t, argb); // 右
    }

    @Override
    public void drawText(String text, float x, float y, float size, int argb, boolean bold) {
        if (text == null || text.isEmpty() || alphaOf(argb) == 0) {
            return;
        }
        PoseStack pose = graphics.pose();
        pose.pushPose();
        // 字号换算成原版字体缩放：size 9 → 1.0，9 的整数倍最清晰（18 → 2.0）
        float fontScale = (size * this.scale) / BASE_SIZE;
        if (Math.abs(fontScale - 1f) > 0.01f) {
            pose.scale(fontScale, fontScale, 1f);
        }
        // 缩放之后坐标要除回去，这样调用方仍然按"主题坐标"思考
        float px = sx(x) / fontScale;
        float py = sy(y) / fontScale;
        graphics.drawString(font(), text, px, py, argb, false);
        pose.popPose();
    }

    @Override
    public void drawImage(byte[] png, float x, float y, float w, float h, boolean pixelated) {
        if (png == null || png.length == 0 || w <= 0f || h <= 0f) {
            return;
        }
        CachedTexture tex = textureFor(png);
        if (tex == null) {
            return;
        }
        int x0 = Math.round(sx(x));
        int y0 = Math.round(sy(y));
        int x1 = Math.round(sx(x + w));
        int y1 = Math.round(sy(y + h));
        graphics.blit(tex.location(), x0, y0, 0f, 0f, x1 - x0, y1 - y0,
                tex.width(), tex.height());
    }

    /**
     * 画物品图标（{@code data-item} 用）。
     *
     * <p>用原版 {@code renderItem} 画 16×16 图标，再按框的大小缩放、按角度旋转 ——
     * 所以"框多大、转到哪、放哪儿"，物品就完全跟着走，不会出现槽位错位。</p>
     */
    @Override
    public void drawItem(String itemId, int count, float x, float y, float w, float h,
                         float rotateDeg) {
        if (itemId == null || itemId.isBlank() || w <= 0 || h <= 0) {
            return;
        }
        try {
            ResourceLocation rl = ResourceLocation.tryParse(itemId.trim());
            if (rl == null || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(rl)) {
                return;
            }
            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl);
            var stack = new net.minecraft.world.item.ItemStack(item, Math.max(1, count));

            PoseStack pose = graphics.pose();
            float cx = sx(x + w / 2f);
            float cy = sy(y + h / 2f);
            float iconScale = Math.min(w, h) * this.scale / 16f;

            pose.pushPose();
            pose.translate(cx, cy, 0);
            if (rotateDeg != 0f) {
                pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(rotateDeg));
            }
            pose.scale(iconScale, iconScale, 1f);
            pose.translate(-8, -8, 0);
            graphics.renderItem(stack, 0, 0);
            if (count > 1) {
                graphics.renderItemDecorations(font(), stack, 0, 0);
            }
            pose.popPose();
        } catch (Throwable t) {
            // 物品 id 写错 / 注册表还没起来：跳过，不影响界面
            DeepSpaceDeployer.LOGGER.debug("[深空铸造体][界面] 物品图标绘制失败（{}）：{}",
                    itemId, t.toString());
        }
    }

    @Override
    public List<String> wrap(String text, float size, boolean bold, float maxWidth) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        float line = 0f;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {                       // 主题里写 <br> 会变成换行符
                out.add(cur.toString());
                cur.setLength(0);
                line = 0f;
                continue;
            }
            float adv = (isWide(c) ? FULL_ADVANCE : HALF_ADVANCE) * (size / BASE_SIZE);
            if (line > 0f && line + adv > maxWidth) {
                out.add(cur.toString());
                cur.setLength(0);
                line = 0f;
            }
            cur.append(c);
            line += adv;
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    // ---------------------------------------------------------------- 裁切 / 偏移

    @Override
    public void pushClip(float x, float y, float w, float h) {
        int x0 = Math.round(sx(x));
        int y0 = Math.round(sy(y));
        int x1 = Math.round(sx(x + w));
        int y1 = Math.round(sy(y + h));
        clipStack.push(new float[]{x0, y0, x1, y1});
        graphics.enableScissor(x0, y0, x1, y1);
    }

    @Override
    public void popClip() {
        if (clipStack.isEmpty()) {
            return;
        }
        clipStack.pop();
        if (clipStack.isEmpty()) {
            // 回到"没有裁切"：原版没有 disableScissor 的"无限制"语义，
            // 用一个足够大的矩形代替（比残留上一次的裁切安全）
            graphics.disableScissor();
        } else {
            float[] top = clipStack.peek();
            graphics.enableScissor((int) top[0], (int) top[1], (int) top[2], (int) top[3]);
        }
    }

    @Override
    public void pushOffset(float dx, float dy) {
        offsets.push(new float[]{offX, offY});
        offX += dx;
        offY += dy;
    }

    @Override
    public void popOffset() {
        if (!offsets.isEmpty()) {
            float[] p = offsets.pop();
            offX = p[0];
            offY = p[1];
        }
    }

    /**
     * 一帧画完之后收尾：把裁切栈清干净。
     *
     * <p>主题里 {@code overflow} 的 push/pop 是配对的，但"渲染中途抛异常"会让栈失衡。
     * 宿主界面每帧渲染结束都调一次这个，保证不会把裁切状态泄漏给后面的原版界面。</p>
     */
    public void finishFrame() {
        clipStack.clear();
        offsets.clear();
        offX = 0f;
        offY = 0f;
    }

    // ---------------------------------------------------------------- 贴图缓存

    private record CachedTexture(ResourceLocation location, DynamicTexture texture,
                                 int width, int height) {
    }

    private CachedTexture textureFor(byte[] png) {
        java.util.Map<Long, CachedTexture> cache = sharedTextures();
        long key = hash(png);
        synchronized (cache) {
            CachedTexture hit = cache.get(key);
            if (hit != null) {
                return hit;
            }
            if (cache.size() >= MAX_TEXTURES) {
                releaseAll();
            }
            try {
                NativeImage image = NativeImage.read(new ByteArrayInputStream(png));
                if (image == null) {
                    return null;
                }
                DynamicTexture tex = new DynamicTexture(image);
                ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(
                        DeepSpaceDeployer.MOD_ID, "theme_dyn_" + (textureSeq++));
                Minecraft.getInstance().getTextureManager().register(loc, tex);
                CachedTexture entry =
                        new CachedTexture(loc, tex, image.getWidth(), image.getHeight());
                cache.put(key, entry);
                return entry;
            } catch (Throwable t) {
                // 图坏了就不画：主题出错绝不能让界面打不开（渲染失败回退，不崩）
                DeepSpaceDeployer.LOGGER.debug("[深空铸造体][界面] 主题图片解码失败：{}", t.toString());
                return null;
            }
        }
    }

    /** 内容哈希：长度 + 采样若干字节（够用且便宜，不用算完整 SHA） */
    private static long hash(byte[] b) {
        long h = 1125899906842597L ^ b.length;
        int step = Math.max(1, b.length / 64);
        for (int i = 0; i < b.length; i += step) {
            h = h * 31 + b[i];
        }
        h = h * 31 + b[b.length - 1];
        return h;
    }

    /**
     * 释放所有临时贴图。
     *
     * <p>宿主界面关闭时调一次；缓存满了也会自动调。</p>
     */
    public static void releaseAll() {
        java.util.Map<Long, CachedTexture> cache = sharedTextures();
        synchronized (cache) {
            for (CachedTexture t : cache.values()) {
                try {
                    Minecraft.getInstance().getTextureManager().release(t.location());
                } catch (Throwable ignored) {
                    // 释放失败不影响功能
                }
            }
            cache.clear();
        }
    }

    /** 现在缓存了几张贴图（诊断用） */
    public static int cachedTextureCount() {
        synchronized (sharedTextures()) {
            return sharedTextures().size();
        }
    }

    // ---------------------------------------------------------------- 小工具

    private static net.minecraft.client.gui.Font font() {
        return Minecraft.getInstance().font;
    }

    private static int alphaOf(int argb) {
        return (argb >>> 24) & 0xFF;
    }

    /** 全角判断：和 PreviewCanvas 用同一张表，保证两边折行一致 */
    private static boolean isWide(char c) {
        return (c >= 0x1100 && c <= 0x115F)
                || (c >= 0x2E80 && c <= 0xA4CF)
                || (c >= 0xAC00 && c <= 0xD7A3)
                || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0xFE30 && c <= 0xFE6F)
                || (c >= 0xFF00 && c <= 0xFF60)
                || (c >= 0xFFE0 && c <= 0xFFE6);
    }

    // ---------------------------------------------------------------- 只量不画

    /**
     * <b>只量不画的画布</b>：给"还没开始渲染时先算一遍布局"用。
     *
     * <p>为什么必须存在：布局要量文字宽度，而量出来的宽度决定<b>换行位置</b>。
     * 如果那一次用别的规则量、真正绘制时又用 {@link GuiCanvas} 的规则，
     * 两者不一致就会出现"预览里一行、游戏里两行"。所以这里复用<b>同一份</b>度量代码。</p>
     *
     * <p>客户端还没起来（font 拿不到）时退化成纯公式度量，不会抛异常。</p>
     */
    public static Canvas measureOnly() {
        return new MeasureOnlyCanvas();
    }

    /** 共用 {@link GuiCanvas} 的度量规则、什么都不画的画布 */
    private static final class MeasureOnlyCanvas implements Canvas {

        private static net.minecraft.client.gui.Font fontOrNull() {
            try {
                Minecraft mc = Minecraft.getInstance();
                return mc == null ? null : mc.font;
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        public float textWidth(String text, float size, boolean bold) {
            if (text == null || text.isEmpty()) {
                return 0f;
            }
            float own = 0f;
            for (int i = 0; i < text.length(); i++) {
                own += isWide(text.charAt(i)) ? FULL_ADVANCE : HALF_ADVANCE;
            }
            own *= size / BASE_SIZE;
            net.minecraft.client.gui.Font f = fontOrNull();
            return f == null ? own : Math.max(own, f.width(text));
        }

        @Override
        public float textHeight(float size) {
            net.minecraft.client.gui.Font f = fontOrNull();
            float vanilla = f == null ? size + 1f : f.lineHeight;
            return Math.max(size + 1f, vanilla);
        }

        @Override
        public void fillRect(float x, float y, float w, float h, int argb) {
        }

        @Override
        public void fillRoundRect(float x, float y, float w, float h, float radius, int argb) {
        }

        @Override
        public void strokeRect(float x, float y, float w, float h, float lineWidth, int argb) {
        }

        @Override
        public void drawText(String text, float x, float y, float size, int argb, boolean bold) {
        }

        @Override
        public void drawImage(byte[] png, float x, float y, float w, float h, boolean pixelated) {
        }

        @Override
        public List<String> wrap(String text, float size, boolean bold, float maxWidth) {
            List<String> out = new ArrayList<>();
            if (text == null || text.isEmpty()) {
                return out;
            }
            float line = 0f;
            StringBuilder cur = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\n') {
                    out.add(cur.toString());
                    cur.setLength(0);
                    line = 0f;
                    continue;
                }
                float adv = (isWide(c) ? FULL_ADVANCE : HALF_ADVANCE) * (size / BASE_SIZE);
                if (line > 0f && line + adv > maxWidth) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    line = 0f;
                }
                cur.append(c);
                line += adv;
            }
            if (cur.length() > 0) {
                out.add(cur.toString());
            }
            return out;
        }

        @Override
        public void pushClip(float x, float y, float w, float h) {
        }

        @Override
        public void popClip() {
        }

        @Override
        public void pushOffset(float dx, float dy) {
        }

        @Override
        public void popOffset() {
        }
    }
}
