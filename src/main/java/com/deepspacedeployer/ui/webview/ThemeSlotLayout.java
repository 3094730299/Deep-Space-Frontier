package com.deepspacedeployer.ui.webview;

import net.minecraft.world.inventory.Slot;

import java.util.List;
import java.util.Map;

/**
 * <b>把原生槽位摆到主题标记的位置上</b>。
 *
 * <p>原版物品格没法用 HTML 画（要拖拽、右键分堆、shift 移动），所以主题只写
 * {@code <div data-slot="input9">} 这样的<b>占位标记</b>，真正的 18×18 物品格由原版绘制。</p>
 *
 * <p>⚠️ <b>这个类以前根本不存在</b> —— 结果是主题画自己的框、原版槽位却按
 * {@code MachineMenu} 里写死的坐标摆，两边永远对不上：物品飘在框外面、
 * 3×3 格子和主题画的方框错开几十像素（老板 2026-10-19 实机截图里的"错位"）。
 * 现在每次主题重新布局后都调一次，把槽位搬过去。</p>
 *
 * <p>坐标约定：标记的坐标是<b>相对主题画布</b>的，画布左上角对齐到
 * {@code leftPos/topPos}；而 {@link Slot#x}/{@link Slot#y} 也是相对
 * {@code leftPos/topPos} 的 —— 所以直接照抄，不用再加偏移。</p>
 */
public final class ThemeSlotLayout {

    /** 槽位间距（原版 18×18 一格） */
    public static final int SLOT = 18;
    /** 玩家背包 3×9 与快捷栏之间的空隙（原版就是 58 = 3×18 + 4） */
    public static final int HOTBAR_GAP = 58;

    private ThemeSlotLayout() {
    }

    /** 单个槽位摆到某个标记的左上角 */
    public static boolean place(WebViewScreen webview, Slot slot, String marker) {
        return place(webview, slot, marker, 0, 0);
    }

    /** 单个槽位摆到标记 + (dx,dy) */
    public static boolean place(WebViewScreen webview, Slot slot, String marker, int dx, int dy) {
        return place(webview, slot, marker, dx, dy, 1f);
    }

    /** 带整体缩放的版本 */
    public static boolean place(WebViewScreen webview, Slot slot, String marker, int dx, int dy,
                                float scale) {
        if (webview == null || slot == null) {
            return false;
        }
        Map<String, ThemeContext.Marker> markers = webview.markers();
        ThemeContext.Marker m = markers.get("slot:" + marker);
        if (m == null) {
            return false;
        }
        slot.x = Math.round(m.x() * scale) + dx;
        slot.y = Math.round(m.y() * scale) + dy;
        return true;
    }

    /**
     * 一组格子（3×3、3×2……）摆到某个标记的位置。
     *
     * @param slots 按"从左到右、从上到下"排好的槽位
     * @param cols  每行几个
     */
    public static boolean grid(WebViewScreen webview, List<Slot> slots, String marker, int cols) {
        return grid(webview, slots, marker, cols, 1f);
    }

    /** 带整体缩放的版本 */
    public static boolean grid(WebViewScreen webview, List<Slot> slots, String marker, int cols,
                               float scale) {
        if (webview == null || slots == null || slots.isEmpty()) {
            return false;
        }
        Map<String, ThemeContext.Marker> markers = webview.markers();
        ThemeContext.Marker m = markers.get("slot:" + marker);
        if (m == null) {
            return false;
        }
        int startX = Math.round(m.x() * scale);
        int startY = Math.round(m.y() * scale);
        int step = Math.max(1, Math.round(SLOT * scale));
        for (int i = 0; i < slots.size(); i++) {
            Slot s = slots.get(i);
            s.x = startX + (i % cols) * step;
            s.y = startY + (i / cols) * step;
        }
        return true;
    }

    /** 横排（按钮那种一行几个的格子） */
    public static boolean row(WebViewScreen webview, List<Slot> slots, String marker) {
        return grid(webview, slots, marker, Math.max(1, slots == null ? 1 : slots.size()));
    }

    /**
     * 某个"原生输入框"标记（{@code data-input="价格"}）的位置。
     *
     * <p>输入框是原版 {@code EditBox}，塞不进 HTML，只能按标记挪过去。
     * 返回的坐标<b>相对主题画布</b>，调用方自己加 {@code leftPos/topPos}。</p>
     *
     * @return 标记；没有返回 null
     */
    public static ThemeContext.Marker input(WebViewScreen webview, String name) {
        if (webview == null || name == null) {
            return null;
        }
        return webview.markers().get("input:" + name);
    }

    /**
     * 玩家背包（3×9 + 快捷栏）摆到 {@code data-slot="player"} 的位置。
     *
     * @param mainInv 36 格里的前 27 格（3×9）
     * @param hotbar  快捷栏 9 格
     */
    public static boolean player(WebViewScreen webview, List<Slot> mainInv, List<Slot> hotbar) {
        return player(webview, mainInv, hotbar, 1f);
    }

    /** 带整体缩放的版本 */
    public static boolean player(WebViewScreen webview, List<Slot> mainInv, List<Slot> hotbar,
                                 float scale) {
        if (webview == null) {
            return false;
        }
        ThemeContext.Marker m = webview.markers().get("slot:player");
        if (m == null) {
            return false;
        }
        int x0 = Math.round(m.x() * scale);
        int y0 = Math.round(m.y() * scale);
        int step = Math.max(1, Math.round(SLOT * scale));
        for (int i = 0; i < mainInv.size(); i++) {
            Slot s = mainInv.get(i);
            s.x = x0 + (i % 9) * step;
            s.y = y0 + (i / 9) * step;
        }
        int gap = Math.max(1, Math.round(HOTBAR_GAP * scale));
        for (int i = 0; i < hotbar.size(); i++) {
            Slot s = hotbar.get(i);
            s.x = x0 + i * step;
            s.y = y0 + gap;
        }
        return true;
    }
}
