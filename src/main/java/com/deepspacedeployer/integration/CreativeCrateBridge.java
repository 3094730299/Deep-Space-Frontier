package com.deepspacedeployer.integration;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 机械动力「创造板条箱」探测桥（反射，不编译期依赖 Create）。
 *
 * <p>老板要求：<i>"工厂装配核心旁边有创造板条箱时视为无限材料，不消耗金额、无组装时间立刻放置"</i>。
 * 要做到这件事只需要两样东西：</p>
 * <ol>
 *   <li><b>认出板条箱是不是"创造"的</b>：Create 的 {@code CreativeCrateBlockEntity} 与普通板条箱
 *       {@code CrateBlockEntity} 是两个类，比对全限定类名最稳（不需要 Create 在编译期存在）。</li>
 *   <li><b>读出它当前"无限供应"的那件物品</b>：创造板条箱里有一个
 *       {@code FilteringBehaviour filtering} 字段，它的 {@code getFilter()} 就是被设定的那件物品。
 *       创造板条箱对任何物品都"无限供应该 Filter 指定的那一种"。</li>
 * </ol>
 *
 * <p>没装 Create、或者 Create 改了内部结构，这里都会安静地返回 false / 空 —— 模组照常跑，
 * 只是没有"创造板条箱 = 无限材料"这个便利。</p>
 */
public final class CreativeCrateBridge {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Create 的创造板条箱方块实体（普通板条箱是 CrateBlockEntity，两者互为父子） */
    private static final String CREATIVE_CRATE_CLASS =
            "com.simibubi.create.content.logistics.crate.CreativeCrateBlockEntity";

    private static boolean probed;
    private static boolean available;
    /** 类的全限定名（比对字符串比 isInstance 更省事，也不依赖 Class 对象） */
    private static final String CREATIVE_CRATE_NAME = CREATIVE_CRATE_CLASS;
    private static Field filteringField;
    private static Method getFilterMethod;

    /** 探测一次并缓存结果 */
    private static synchronized void probe() {
        if (probed) {
            return;
        }
        probed = true;
        try {
            Class<?> crateClass = Class.forName(CREATIVE_CRATE_CLASS);
            // FilteringBehaviour filtering —— 字段名和类型都可能是改版的重灾区，找不到就整体放弃
            filteringField = crateClass.getDeclaredField("filtering");
            filteringField.setAccessible(true);
            getFilterMethod = filteringField.getType().getMethod("getFilter");
            available = true;
            LOGGER.info("[深空铸造体] 找到机械动力创造板条箱：{}（创造板条箱 = 无限材料模式可用）",
                    CREATIVE_CRATE_CLASS);
        } catch (ClassNotFoundException e) {
            // 没装 Create：正常情况，不刷日志
            available = false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // LinkageError 也要接住：字段类型所在的类缺失时抛的是 NoClassDefFoundError（Error，不是 Exception）
            LOGGER.warn("[深空铸造体] 创造板条箱结构对不上（Create 可能改版了）：{}", e.toString());
            available = false;
        }
    }

    /** 这个模组环境里有没有创造板条箱可用 */
    public static boolean available() {
        probe();
        return available;
    }

    /**
     * 这个方块实体是不是创造板条箱。
     *
     * <p>先比类名（便宜、不可能报错），命中了再确认反射句柄拿到了，避免"名字对上但读不出物品"。</p>
     */
    public static boolean isCreativeCrate(Object blockEntity) {
        if (blockEntity == null || !blockEntity.getClass().getName().equals(CREATIVE_CRATE_NAME)) {
            return false;
        }
        probe();
        return available;
    }

    /**
     * 这个创造板条箱"无限供应"的那件物品；没设定 / 读不到就是空栈。
     *
     * <p>注意：创造板条箱只无限供应 Filter 里设定的那一种物品，
     * 所以一张蓝图要多材料时，就多摆几个板条箱（各设一种）。</p>
     */
    public static ItemStack suppliedItem(Object blockEntity) {
        if (!isCreativeCrate(blockEntity)) {
            return ItemStack.EMPTY;
        }
        try {
            Object filtering = filteringField.get(blockEntity);
            if (filtering == null) {
                return ItemStack.EMPTY;
            }
            Object filter = getFilterMethod.invoke(filtering);
            return filter instanceof ItemStack stack ? stack : ItemStack.EMPTY;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * 在方块周围（正方体半径 {@code range}）找创造板条箱，并把它们"无限供应"的物品收集起来。
     *
     * @return 收集到的物品 id 列表（可能为空）
     */
    public static java.util.List<ItemStack> findNearby(Level level, BlockPos center, int range,
                                                       java.util.List<ItemStack> out) {
        if (!available() || level == null) {
            return out;
        }
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-range, -range, -range), center.offset(range, range, range))) {
            Object be = level.getBlockEntity(pos);
            if (!isCreativeCrate(be)) {
                continue;
            }
            ItemStack supplied = suppliedItem(be);
            if (!supplied.isEmpty()) {
                out.add(supplied.copy());
            }
        }
        return out;
    }

    /** 给 /dsd integration 探针用的一行说明 */
    public static String describe() {
        probe();
        if (available) {
            return "可用（" + CREATIVE_CRATE_CLASS + "）";
        }
        return "不可用（没装机械动力，或创造板条箱结构对不上）";
    }

    private CreativeCrateBridge() {
    }
}
