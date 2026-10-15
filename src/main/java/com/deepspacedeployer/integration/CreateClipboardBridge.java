package com.deepspacedeployer.integration;

import com.mojang.logging.LogUtils;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 机械动力「剪贴板」联动桥（反射实现，Create 不是编译期依赖）。
 *
 * <p>老板要求："在上面加个机械动力剪贴板的格子用于填写所缺材料导出给玩家看" ——
 * 玩家往核心的剪贴板格里放一块剪贴板，我们就把<b>当前蓝图缺的材料清单</b>写进这块剪贴板：
 * 每一条是「物品图标 + 数量 + 物品名」，跟机械动力自己写的剪贴板格式完全一致，
 * 拿在手上右键就能看，也能放进剪贴板方块给别人看。</p>
 *
 * <p>成员名都靠反射取，<b>不硬编码 NBT 键</b>：
 * 组件类型用 {@code ClipboardBlockItem#getComponentType()} 运行时拿，
 * 条目用公开构造函数 {@code ClipboardEntry(boolean, Component)} + {@code displayItem(ItemStack, int)}，
 * 内容用 {@code ClipboardContent(ClipboardType, List, boolean)}。
 * 任何一个环节拿不到就整体降级成"写不进去"，绝不抛异常影响组装。</p>
 */
public final class CreateClipboardBridge {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String BLOCK_ITEM = "com.simibubi.create.content.equipment.clipboard.ClipboardBlockItem";
    private static final String ENTRY = "com.simibubi.create.content.equipment.clipboard.ClipboardEntry";
    private static final String CONTENT = "com.simibubi.create.content.equipment.clipboard.ClipboardContent";
    private static final String TYPE = "com.simibubi.create.content.equipment.clipboard.ClipboardOverrides$ClipboardType";

    private static boolean probed;
    private static boolean available;
    /** 剪贴板物品的注册名，用来判断"这个格子放的是不是剪贴板" */
    private static String clipboardItemId = "create:clipboard";

    private static Constructor<?> entryCtor;
    private static Method displayItem;
    private static Method readAll;
    private static Constructor<?> contentCtor;
    private static Object typeWritten;
    private static Method contentComponent;   // ClipboardContent -> DataComponentType
    private static Method getComponentType;   // ClipboardBlockItem#getComponentType

    private static synchronized void probe() {
        if (probed) {
            return;
        }
        probed = true;
        try {
            Class<?> blockItem = Class.forName(BLOCK_ITEM);
            Class<?> entry = Class.forName(ENTRY);
            Class<?> content = Class.forName(CONTENT);
            Class<?> type = Class.forName(TYPE);

            entryCtor = entry.getConstructor(boolean.class, Component.class);
            displayItem = entry.getMethod("displayItem", ItemStack.class, int.class);
            readAll = entry.getMethod("readAll", ItemStack.class);
            contentCtor = content.getConstructor(type, List.class, boolean.class);
            typeWritten = type.getField("WRITTEN").get(null);
            contentComponent = content.getMethod("getComponentType");
            getComponentType = blockItem.getMethod("getComponentType");
            available = true;
            LOGGER.info("[深空铸造体] 机械动力剪贴板接口就绪（{}）", contentComponent.getName());
        } catch (ClassNotFoundException e) {
            available = false;
            LOGGER.info("[深空铸造体] 没装机械动力（或版本没有剪贴板），剪贴板导出功能不可用");
        } catch (Throwable t) {
            available = false;
            LOGGER.warn("[深空铸造体] 探测剪贴板接口失败：{}", t.toString());
        }
    }

    public static boolean available() {
        probe();
        return available;
    }

    public static String describe() {
        probe();
        return available
                ? "剪贴板：可用（" + clipboardItemId + "）"
                : "剪贴板：不可用（没装 Create 或不兼容）";
    }

    /** 这个物品是不是机械动力的剪贴板 */
    public static boolean isClipboard(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        var key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key != null && clipboardItemId.equals(key.toString());
    }

    /**
     * 把材料清单写进这块剪贴板（会覆盖原有内容）。
     *
     * @param stack  剪贴板物品
     * @param title  第一行标题
     * @param lines  每一行：{物品、数量、文字}
     * @return 写成功了没有
     */
    public static boolean write(ItemStack stack, Component title, List<Line> lines) {
        probe();
        if (!available || !isClipboard(stack)) {
            return false;
        }
        try {
            List<Object> page = new ArrayList<>();
            page.add(entryCtor.newInstance(false, title));
            for (Line line : lines) {
                Object entry = entryCtor.newInstance(false, line.text());
                if (line.icon() != null && !line.icon().isEmpty()) {
                    displayItem.invoke(entry, line.icon(), Math.max(1, line.count()));
                }
                page.add(entry);
            }
            List<List<Object>> pages = new ArrayList<>();
            pages.add(page);
            Object content = contentCtor.newInstance(typeWritten, pages, true);

            DataComponentType<?> componentType = componentTypeOf(stack);
            if (componentType == null) {
                return false;
            }
            setComponent(stack, componentType, content);
            return true;
        } catch (Throwable t) {
            LOGGER.warn("[深空铸造体] 写入剪贴板失败：{}", t.toString());
            return false;
        }
    }

    /** 这块剪贴板当前有几页内容（0 = 空的） */
    public static int pageCount(ItemStack stack) {
        probe();
        if (!available || !isClipboard(stack)) {
            return 0;
        }
        try {
            Object pages = readAll.invoke(null, stack);
            return pages instanceof List<?> list ? list.size() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 拿剪贴板的组件类型（用物品自己的 getComponentType()，不硬编码） */
    @SuppressWarnings("unchecked")
    private static DataComponentType<?> componentTypeOf(ItemStack stack) {
        try {
            Object type = getComponentType.invoke(stack.getItem());
            return type instanceof DataComponentType<?> dt ? dt : null;
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> void setComponent(ItemStack stack, DataComponentType<T> type, Object value) {
        stack.set(type, (T) value);
    }

    /** 剪贴板上的一行：图标 + 数量 + 文字 */
    public record Line(ItemStack icon, int count, Component text) {
    }

    private CreateClipboardBridge() {
    }
}
