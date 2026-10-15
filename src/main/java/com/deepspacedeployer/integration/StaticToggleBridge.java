package com.deepspacedeployer.integration;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 与「Sable Photomancy」（sable_schematic_api）对接：子层级 静态⇄动态（就是我们说的"开关碰撞"）。
 *
 * <p><b>签名已按 sable-photomancy-1.0.1.jar 的真实字节码校准：</b></p>
 * <pre>
 * dev.rew1nd.sableschematicapi.sublevel.RuntimeSubLevelStaticService
 *   public static SubLevelOperationResult toggleStatic(ServerLevel, UUID)                    // 首选：一步切换
 *   public static SubLevelOperationResult setStatic(MinecraftServer, ResourceKey&lt;Level&gt;, UUID, boolean)
 *   public static boolean isStatic(ResourceKey&lt;Level&gt;, UUID)
 * dev.rew1nd.sableschematicapi.sublevel.SubLevelOperationResult
 *   public boolean success(); public String message();
 * </pre>
 */
public final class StaticToggleBridge {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<String> CANDIDATE_CLASSES = List.of(
            "dev.rew1nd.sableschematicapi.sublevel.RuntimeSubLevelStaticService",
            "dev.rew1nd.sableschematicapi.survival.physicsmanager.RuntimeSubLevelStaticService",
            "dev.rew1nd.sableschematicapi.survival.physicsmanager.PhysicsManagerServerActions"
    );

    private static boolean probed;
    private static Class<?> serviceClass;
    private static Method toggleMethod;      // toggleStatic(ServerLevel, UUID)
    private static Method setStaticMethod;   // setStatic(MinecraftServer, ResourceKey, UUID, boolean)
    private static Method isStaticMethod;    // isStatic(ResourceKey, UUID) 或 isStatic(MinecraftServer, ResourceKey, UUID)

    /** 一次切换的结果（带上对方给的提示文本） */
    public record Result(boolean success, String detail) {
    }

    private static synchronized void probe() {
        if (probed) {
            return;
        }
        probed = true;
        for (String name : CANDIDATE_CLASSES) {
            try {
                Class<?> candidate = Class.forName(name);

                for (Method method : candidate.getMethods()) {
                    if (!Modifier.isStatic(method.getModifiers())) {
                        continue;
                    }
                    Class<?>[] params = method.getParameterTypes();
                    if (method.getName().equals("toggleStatic") && params.length == 2
                            && ServerLevel.class.isAssignableFrom(params[0]) && params[1] == UUID.class) {
                        toggleMethod = method;
                    } else if (method.getName().equals("setStatic") && params.length == 4
                            && MinecraftServer.class.isAssignableFrom(params[0])
                            && ResourceKey.class.isAssignableFrom(params[1]) && params[2] == UUID.class) {
                        setStaticMethod = method;
                    } else if (method.getName().equals("isStatic")) {
                        if (params.length == 2 && ResourceKey.class.isAssignableFrom(params[0])
                                && params[1] == UUID.class) {
                            isStaticMethod = method;
                        } else if (params.length == 3 && MinecraftServer.class.isAssignableFrom(params[0])
                                && ResourceKey.class.isAssignableFrom(params[1]) && params[2] == UUID.class) {
                            if (isStaticMethod == null) {
                                isStaticMethod = method;
                            }
                        }
                    }
                }

                if (toggleMethod != null || setStaticMethod != null) {
                    serviceClass = candidate;
                    LOGGER.info("[深空铸造体] 找到碰撞开关：{}（toggle={}, set={}, read={}）",
                            name, toggleMethod != null, setStaticMethod != null, isStaticMethod != null);
                    return;
                }
            } catch (ClassNotFoundException ignored) {
                // 试下一个类名
            } catch (Throwable t) {
                LOGGER.warn("[深空铸造体] 探测碰撞开关失败：{}", t.toString());
            }
        }
    }

    public static boolean available() {
        probe();
        return toggleMethod != null || setStaticMethod != null;
    }

    public static String describe() {
        probe();
        if (serviceClass == null) {
            return "未找到（Photomancy 未装或 API 变了）";
        }
        String used = toggleMethod != null ? "toggleStatic(ServerLevel, UUID)" : "setStatic(MinecraftServer, ...)";
        return serviceClass.getName() + "#" + used + (isStaticMethod == null ? "；读状态不可用" : "；可读状态");
    }

    /** 切换"开关碰撞"：静态 = 冻结（不参与物理），动态 = 正常物理 */
    public static Result toggle(ServerLevel level, UUID subLevelId) {
        probe();
        if (subLevelId == null) {
            return new Result(false, "这台焊接台还没有焊过的结构");
        }
        if (toggleMethod != null) {
            try {
                return readResult(toggleMethod.invoke(null, level, subLevelId));
            } catch (Throwable t) {
                Throwable cause = t.getCause() == null ? t : t.getCause();
                LOGGER.warn("[深空铸造体] 切换碰撞失败：{}", cause.toString());
                return new Result(false, cause.getClass().getSimpleName() + ": " + cause.getMessage());
            }
        }
        if (setStaticMethod != null) {
            // 没有 toggle 就自己读状态再取反
            Boolean current = isStatic(level, subLevelId);
            boolean target = !Boolean.TRUE.equals(current);
            return setStaticDetailed(level, subLevelId, target);
        }
        return new Result(false, "Photomancy 未安装");
    }

    /** 直接设置静态/动态（保留给"明确要某个状态"的场景） */
    public static Result setStaticDetailed(ServerLevel level, UUID subLevelId, boolean value) {
        probe();
        if (setStaticMethod == null || subLevelId == null) {
            return new Result(false, "接口不可用");
        }
        try {
            Object[] args = new Object[setStaticMethod.getParameterCount()];
            Class<?>[] params = setStaticMethod.getParameterTypes();
            for (int i = 0; i < params.length; i++) {
                if (MinecraftServer.class.isAssignableFrom(params[i])) {
                    args[i] = level.getServer();
                } else if (ResourceKey.class.isAssignableFrom(params[i])) {
                    args[i] = level.dimension();
                } else if (params[i] == UUID.class) {
                    args[i] = subLevelId;
                } else if (params[i] == boolean.class || params[i] == Boolean.class) {
                    args[i] = value;
                } else {
                    args[i] = null;
                }
            }
            return readResult(setStaticMethod.invoke(null, args));
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            LOGGER.warn("[深空铸造体] 设置碰撞状态失败：{}", cause.toString());
            return new Result(false, cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    /** 兼容旧调用：返回是否成功 */
    public static boolean setStatic(MinecraftServer server, ResourceKey<Level> dimension, UUID subLevelId,
                                    boolean value) {
        probe();
        if (setStaticMethod == null || subLevelId == null || server == null) {
            return false;
        }
        try {
            Object[] args = new Object[setStaticMethod.getParameterCount()];
            Class<?>[] params = setStaticMethod.getParameterTypes();
            for (int i = 0; i < params.length; i++) {
                if (MinecraftServer.class.isAssignableFrom(params[i])) {
                    args[i] = server;
                } else if (ResourceKey.class.isAssignableFrom(params[i])) {
                    args[i] = dimension;
                } else if (params[i] == UUID.class) {
                    args[i] = subLevelId;
                } else if (params[i] == boolean.class || params[i] == Boolean.class) {
                    args[i] = value;
                } else {
                    args[i] = null;
                }
            }
            return readResult(setStaticMethod.invoke(null, args)).success();
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            LOGGER.warn("[深空铸造体] 切换碰撞失败：{}", cause.toString());
            return false;
        }
    }

    /** 读当前是不是"静态"（冻结）；读不到返回 null */
    public static Boolean isStatic(Level level, UUID subLevelId) {
        probe();
        if (isStaticMethod == null || subLevelId == null || level == null) {
            return null;
        }
        try {
            Class<?>[] params = isStaticMethod.getParameterTypes();
            Object[] args = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                if (MinecraftServer.class.isAssignableFrom(params[i])) {
                    args[i] = level.getServer();
                } else if (ResourceKey.class.isAssignableFrom(params[i])) {
                    args[i] = level.dimension();
                } else if (params[i] == UUID.class) {
                    args[i] = subLevelId;
                } else {
                    args[i] = null;
                }
            }
            Object result = isStaticMethod.invoke(null, args);
            return result instanceof Boolean value ? value : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 把对方的 SubLevelOperationResult 转成我们的 Result（success()/message()） */
    private static Result readResult(Object operationResult) {
        if (operationResult == null) {
            return new Result(true, "已切换");
        }
        try {
            Method success = operationResult.getClass().getMethod("success");
            Method message = operationResult.getClass().getMethod("message");
            boolean ok = Boolean.TRUE.equals(success.invoke(operationResult));
            Object text = message.invoke(operationResult);
            return new Result(ok, text == null ? "" : text.toString());
        } catch (Throwable t) {
            return new Result(true, "已切换（对方返回 " + operationResult.getClass().getSimpleName() + "）");
        }
    }

    /** 给探针用：这个类上有哪些公开静态方法 */
    public static List<String> listMethods() {
        List<String> out = new ArrayList<>();
        for (String name : CANDIDATE_CLASSES) {
            try {
                Class<?> candidate = Class.forName(name);
                out.add("  ✔ " + name);
                for (Method method : candidate.getMethods()) {
                    if (Modifier.isStatic(method.getModifiers())) {
                        out.add("      " + method.getName() + "(" + paramTypes(method) + ")");
                    }
                }
            } catch (ClassNotFoundException e) {
                out.add("  ✘ " + name + "（未安装）");
            }
        }
        return out;
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

    private StaticToggleBridge() {
    }
}
