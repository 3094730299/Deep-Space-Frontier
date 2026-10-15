package com.deepspacedeployer.integration;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.UUID;

/**
 * 「解散物理结构」的反射桥 —— 按 sable-neoforge-1.21.1-2.0.6.jar 的真实签名校准过：
 *
 * <pre>
 * dev.ryanhcode.sable.api.sublevel.SubLevelContainer
 *   public static ServerSubLevelContainer getContainer(ServerLevel)
 * dev.ryanhcode.sable.api.sublevel.SubLevelContainer
 *   public SubLevel getSubLevel(UUID)                       // 按 id 直接取
 *   public void removeSubLevel(int, int, SubLevelRemovalReason)   // 参数是地块(plot)坐标
 * dev.ryanhcode.sable.sublevel.plot.LevelPlot
 *   public final ChunkPos plotPos                            // 地块坐标就从这来
 * dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason
 *   REMOVED  （我们解散用这个；另一个是 UNLOADED）
 * </pre>
 */
public final class DissolveBridge {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String CONTAINER_CLASS = "dev.ryanhcode.sable.api.sublevel.SubLevelContainer";
    private static final String REASON_CLASS = "dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason";

    private static boolean probed;
    private static Method getContainerMethod;
    private static Method getSubLevelMethod;
    private static Method removeSubLevelMethod;
    private static Method getPlotMethod;
    private static Field plotPosField;
    private static Object reasonRemoved;

    /** 解散结果 */
    public record Result(boolean success, String detail) {
    }

    private static synchronized void probe() {
        if (probed) {
            return;
        }
        probed = true;
        try {
            Class<?> container = Class.forName(CONTAINER_CLASS);

            // getContainer(ServerLevel)
            for (Method method : container.getMethods()) {
                if (method.getName().equals("getContainer") && Modifier.isStatic(method.getModifiers())
                        && method.getParameterCount() == 1
                        && method.getParameterTypes()[0] == ServerLevel.class) {
                    getContainerMethod = method;
                    break;
                }
            }
            getSubLevelMethod = container.getMethod("getSubLevel", UUID.class);
            removeSubLevelMethod = container.getMethod("removeSubLevel", int.class, int.class,
                    Class.forName(REASON_CLASS));

            Class<?> plot = Class.forName("dev.ryanhcode.sable.sublevel.plot.LevelPlot");
            plotPosField = plot.getField("plotPos");
            getPlotMethod = Class.forName("dev.ryanhcode.sable.sublevel.SubLevel").getMethod("getPlot");

            @SuppressWarnings({"unchecked", "rawtypes"})
            Object removed = Enum.valueOf((Class<? extends Enum>) Class.forName(REASON_CLASS), "REMOVED");
            reasonRemoved = removed;

            LOGGER.info("[深空铸造体] 找到解散结构的方法：{}.removeSubLevel(int, int, {})",
                    CONTAINER_CLASS, REASON_CLASS);
        } catch (ClassNotFoundException e) {
            LOGGER.warn("[深空铸造体] 没找到解散结构的接口（Sable 未装或 API 变了）");
        } catch (Throwable t) {
            LOGGER.warn("[深空铸造体] 探测解散接口失败：{}", t.toString());
        }
    }

    public static boolean available() {
        probe();
        return removeSubLevelMethod != null && getContainerMethod != null && getSubLevelMethod != null;
    }

    public static String describe() {
        probe();
        if (!available()) {
            return "未找到（Sable 未装或 API 变了）";
        }
        String plot = plotPosField == null ? "（地块坐标字段不可见）" : "plotPos ✓";
        return CONTAINER_CLASS + "#removeSubLevel(int, int, REMOVED)；" + plot;
    }

    /** 解散一个物理结构（把它还原成普通方块） */
    public static Result dissolve(ServerLevel level, UUID subLevelId) {
        probe();
        if (!available() || subLevelId == null) {
            return new Result(false, "接口不可用");
        }
        try {
            Object container = getContainerMethod.invoke(null, level);
            if (container == null) {
                return new Result(false, "拿不到该世界的子层级容器");
            }
            Object subLevel = getSubLevelMethod.invoke(container, subLevelId);
            if (subLevel == null) {
                return new Result(false, "这个物理结构已经不在了");
            }
            if (plotPosField == null || getPlotMethod == null) {
                return new Result(false, "拿不到地块坐标（API 变了）");
            }
            Object plot = getPlotMethod.invoke(subLevel);
            Object plotPos = plotPosField.get(plot);
            if (!(plotPos instanceof net.minecraft.world.level.ChunkPos chunkPos)) {
                return new Result(false, "地块坐标类型不符（API 变了）");
            }
            removeSubLevelMethod.invoke(container, chunkPos.x, chunkPos.z, reasonRemoved);
            return new Result(true, "已解散地块 (" + chunkPos.x + ", " + chunkPos.z + ") 上的物理结构");
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            LOGGER.warn("[深空铸造体] 解散结构失败：{}", cause.toString());
            return new Result(false, cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    private DissolveBridge() {
    }
}
