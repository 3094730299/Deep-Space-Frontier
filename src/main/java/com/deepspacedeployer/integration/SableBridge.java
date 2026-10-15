package com.deepspacedeployer.integration;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 与 Sable（航空学的物理引擎）对接的反射桥：把一个方块结构变成"物理结构"（SubLevel）。
 *
 * <p><b>签名已经按 sable-neoforge-1.21.1-2.0.6.jar 的真实字节码校准过</b>（不是猜的）：</p>
 * <pre>
 * dev.ryanhcode.sable.api.SubLevelAssemblyHelper
 *   public static ServerSubLevel assembleBlocks(ServerLevel, BlockPos, Iterable&lt;BlockPos&gt;, BoundingBox3ic)
 * dev.ryanhcode.sable.companion.math.BoundingBox3i
 *   public static BoundingBox3i from(Iterable&lt;BlockPos&gt;)
 * dev.ryanhcode.sable.sublevel.SubLevel
 *   public UUID getUniqueId()
 * </pre>
 *
 * <p>用反射而不是编译期依赖，是为了：没装 Sable 时模组照常跑、装了就能用、
 * 找不到方法也不会崩（只在日志里写清楚想调用什么、为什么没成功）。</p>
 */
public final class SableBridge {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String HELPER_CLASS = "dev.ryanhcode.sable.api.SubLevelAssemblyHelper";
    private static final String BOX_CLASS = "dev.ryanhcode.sable.companion.math.BoundingBox3i";
    /** 官方"物理组装机"用的高层入口（Simulated 提供，航空学自带） */
    private static final String SIM_HELPER_CLASS = "dev.simulated_team.simulated.util.SimAssemblyHelper";

    private static Boolean helperPresent;
    private static Method assembleMethod;
    private static Method boxFromMethod;
    private static Method simAssembleMethod;
    private static Method simResultSubLevel;
    private static boolean probed;

    /** 一次组装的结果 */
    public record Result(boolean success, UUID subLevelId, String detail) {
    }

    public static boolean helperPresent() {
        probe();
        return Boolean.TRUE.equals(helperPresent);
    }

    /** 两条路任意一条可用（官方入口 或 低层接口）——没装相关模组时不要去尝试，免得刷失败提示 */
    public static boolean anyAvailable() {
        probe();
        return simAssembleMethod != null || assembleMethod != null;
    }

    public static String helperClassName() {
        return HELPER_CLASS;
    }

    public static Method foundAssembleMethod() {
        probe();
        return assembleMethod;
    }

    public static String describe() {
        probe();
        StringBuilder sb = new StringBuilder();
        if (simAssembleMethod != null) {
            sb.append("首选 Simulated 官方入口 assembleFromSingleBlock(Level, BlockPos控制器, BlockPos起始, boolean, boolean)");
        } else {
            sb.append("Simulated 官方入口不可用");
        }
        if (assembleMethod != null) {
            sb.append("；备用 ").append(HELPER_CLASS).append(".assembleBlocks + 包围盒");
        } else {
            sb.append("；备用低层接口也不可用（Sable 未装？）");
        }
        return sb.toString();
    }

    private static synchronized void probe() {
        if (probed) {
            return;
        }
        probed = true;
        try {
            Class<?> helper = Class.forName(HELPER_CLASS);
            helperPresent = Boolean.TRUE;

            // 精确形状：(Level 子类, BlockPos, Iterable<BlockPos>, BoundingBox3ic)
            for (Method method : helper.getMethods()) {
                if (!method.getName().equals("assembleBlocks") || !Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 4
                        && Level.class.isAssignableFrom(params[0])
                        && params[1] == BlockPos.class
                        && Iterable.class.isAssignableFrom(params[2])) {
                    assembleMethod = method;
                    LOGGER.info("[深空铸造体] 找到 Sable 组装方法：{}", method);
                    break;
                }
            }
            if (assembleMethod == null) {
                LOGGER.warn("[深空铸造体] 找到 {} 但没有匹配的 assembleBlocks（用 /dsd integration 看方法清单）",
                        HELPER_CLASS);
            }

            // 包围盒工厂：BoundingBox3i.from(Iterable<BlockPos>)
            Class<?> box = Class.forName(BOX_CLASS);
            boxFromMethod = box.getMethod("from", Iterable.class);
            LOGGER.info("[深空铸造体] 找到包围盒工厂：{}.from(Iterable)", BOX_CLASS);
        } catch (ClassNotFoundException e) {
            helperPresent = Boolean.FALSE;
            boxFromMethod = null;
        } catch (Throwable t) {
            helperPresent = Boolean.FALSE;
            LOGGER.warn("[深空铸造体] 探测 Sable 失败：{}", t.toString());
        }

        // 高层入口：Simulated 的 assembleFromSingleBlock（官方物理组装机走的就是这条路）
        try {
            Class<?> simHelper = Class.forName(SIM_HELPER_CLASS);
            simAssembleMethod = simHelper.getMethod("assembleFromSingleBlock",
                    Level.class, BlockPos.class, BlockPos.class, boolean.class, boolean.class);
            Class<?> resultClass = Class.forName(SIM_HELPER_CLASS + "$AssemblyResult");
            simResultSubLevel = resultClass.getMethod("subLevel");
            LOGGER.info("[深空铸造体] 找到官方组装入口：{}.assembleFromSingleBlock", SIM_HELPER_CLASS);
        } catch (ClassNotFoundException e) {
            simAssembleMethod = null;
        } catch (Throwable t) {
            simAssembleMethod = null;
            LOGGER.warn("[深空铸造体] 探测官方组装入口失败：{}", t.toString());
        }
    }

    /**
     * 首选路径：用官方"物理组装机"那套入口，把 start 处连通的方块组装成物理结构。
     *
     * <p>官方调用形状（从 PhysicsAssemblerBlockEntity 的字节码里读出来的）：
     * {@code SimAssemblyHelper.assembleFromSingleBlock(level, 控制器位置, 结构起始位置, true, true)}。
     * 它会自己收集连通方块，控制器方块本身不会被卷进物理结构。</p>
     *
     * @param controller 控制器（我们的工厂核心/焊接台）
     * @param start      结构里的一个实体方块位置（收集从这里开始）
     */
    public static Result assembleFrom(Level level, BlockPos controller, BlockPos start) {
        probe();
        if (simAssembleMethod == null) {
            return new Result(false, null, "Simulated 官方组装入口不可用");
        }
        try {
            Object result = simAssembleMethod.invoke(null, level, controller, start, true, true);
            if (result == null) {
                return new Result(false, null, "assembleFromSingleBlock 返回 null");
            }
            Object subLevel = simResultSubLevel == null ? null : simResultSubLevel.invoke(result);
            UUID id = subLevel == null ? null : extractUuid(subLevel);
            return new Result(true, id, "已组装为物理结构" + (id == null ? "" : " " + id));
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            // Create 用 AssemblyException 说明失败原因（比如结构太大/没对齐）
            String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            LOGGER.warn("[深空铸造体] 官方入口组装失败：{}", message);
            return new Result(false, null, message);
        }
    }

    private static String paramTypes(Method method) {
        StringBuilder sb = new StringBuilder();
        Class<?>[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(params[i].getSimpleName());
        }
        return sb.toString();
    }

    /**
     * 把 blocks 变成一个物理结构。
     *
     * @param anchor 结构的"锚点"（一般用其中一个方块的位置）
     */
    public static Result assemble(ServerLevel level, BlockPos anchor, List<BlockPos> blocks) {
        probe();
        if (assembleMethod == null) {
            return new Result(false, null, "Sable 的 assembleBlocks 不可用");
        }
        if (blocks == null || blocks.isEmpty()) {
            return new Result(false, null, "没有方块可以组装");
        }
        Object box = null;
        if (boxFromMethod != null) {
            try {
                box = boxFromMethod.invoke(null, blocks);
            } catch (Throwable t) {
                LOGGER.warn("[深空铸造体] 计算包围盒失败：{}", t.toString());
            }
        }
        if (box == null) {
            return new Result(false, null, "算不出结构包围盒（" + BOX_CLASS + ".from 不可用）");
        }
        try {
            Object result = assembleMethod.invoke(null, level, anchor, blocks, box);
            if (result == null) {
                return new Result(false, null, "assembleBlocks 返回 null（可能没构成有效结构）");
            }
            UUID id = extractUuid(result);
            return new Result(true, id, "已组装为物理结构" + (id == null ? "" : " " + id));
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            LOGGER.warn("[深空铸造体] 调用 Sable 组装失败：{}", cause.toString());
            return new Result(false, null, cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    /** 从返回对象里挖出 UUID（先按真实名字 getUniqueId，再兜底试别的叫法） */
    private static UUID extractUuid(Object subLevel) {
        for (String name : new String[]{"getUniqueId", "getUUID", "getUuid", "uuid", "getId"}) {
            try {
                Method m = subLevel.getClass().getMethod(name);
                Object value = m.invoke(subLevel);
                if (value instanceof UUID uuid) {
                    return uuid;
                }
            } catch (Throwable ignored) {
                // 换下一个名字
            }
        }
        // 有些版本把 id 放在字段里
        for (String name : new String[]{"uniqueId", "uuid"}) {
            try {
                Field f = subLevel.getClass().getDeclaredField(name);
                f.setAccessible(true);
                if (f.get(subLevel) instanceof UUID uuid) {
                    return uuid;
                }
            } catch (Throwable ignored) {
                // 换下一个名字
            }
        }
        return null;
    }

    /**
     * 自己做的连通块收集（从起点开始六向扩散，遇到空气停），避免依赖 Sable 的采集接口。
     *
     * @param maxBlocks 上限，超过就返回 null（说明这块太大了）
     */
    public static List<BlockPos> gatherConnected(Level level, BlockPos origin, int maxBlocks, int maxRadius) {
        Set<BlockPos> visited = new HashSet<>();
        List<BlockPos> result = new ArrayList<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(origin);
        visited.add(origin);
        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            if (!level.getBlockState(current).isAir() && !result.contains(current)) {
                result.add(current);
                if (result.size() > maxBlocks) {
                    return null;
                }
            }
            if (current.distManhattan(origin) > maxRadius * 2) {
                continue;
            }
            for (Direction direction : Direction.values()) {
                BlockPos next = current.relative(direction);
                if (visited.contains(next)) {
                    continue;
                }
                if (next.distSqr(origin) > (double) maxRadius * maxRadius) {
                    continue;
                }
                // 只穿过非空气方块扩散（结构和空气的交界就是边界）
                if (level.getBlockState(next).isAir()) {
                    visited.add(next);
                    continue;
                }
                visited.add(next);
                queue.add(next);
            }
        }
        return result;
    }

    private SableBridge() {
    }
}
