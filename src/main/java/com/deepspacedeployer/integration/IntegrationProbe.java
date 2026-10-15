package com.deepspacedeployer.integration;

import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * 集成探针：把"整合包里到底装了什么、能调到哪些方法"列出来。
 *
 * <p>用管理员指令 {@code /dsd integration} 查看，服务器启动时也会写进日志。
 * 这样如果某个联动没生效，直接把这段输出发我就能对上真实 API。</p>
 */
public final class IntegrationProbe {

    /** 相关模组的 id（已按整合包里各 jar 的 mods.toml 核对过真实 id） */
    private static final List<String> CANDIDATE_MODS = List.of(
            "create",                    // 机械动力
            "aeronautics",               // 航空学（本体内容）
            "aeronautics_bundled",       // 航空学整合包外壳
            "simulated",                 // Simulated（物理方块）
            "offroad",                   // 航空学附属：越野
            "sable",                     // Sable 物理引擎
            "sablecompanion",            // Sable 的伴生库
            "sable_schematic_api",       // Sable Photomancy（开关碰撞在这里）
            "synaxis",                   // 控制学（焊接约束）
            "create_flight_control",     // 星舰飞控
            "aeronautics_utility_objects", // 传动联动/控制学附加
            "vscontrolcraft");

    private static final List<String> CANDIDATE_CLASSES = List.of(
            // Sable：物理结构组装
            "dev.ryanhcode.sable.api.SubLevelAssemblyHelper",
            "dev.ryanhcode.sable.api.SubLevelContainer",
            "dev.ryanhcode.sable.api.SubLevelHelper",
            // 航空学 / Simulated：物理组装机
            "dev.simulated_team.simulated.content.blocks.physics_assembler.PhysicsAssemblerBlockEntity",
            "dev.simulated_team.simulated.api.PhysicsAssemblerApi",
            // Synaxis（控制学 2）：焊接
            "com.verr1.synaxis.compat.sableblueprint.weld.WeldFaceSnapper");

    public static List<String> describe() {
        List<String> lines = new ArrayList<>();
        lines.add("=== 已安装的相关模组 ===");
        for (String modId : CANDIDATE_MODS) {
            boolean loaded = ModList.get() != null && ModList.get().isLoaded(modId);
            lines.add((loaded ? "✔ " : "✘ ") + modId);
        }

        lines.add("=== 联动桥状态（装配 / 碰撞 / 解散）===");
        lines.add("组装物理结构：" + SableBridge.describe());
        lines.add("开关碰撞　　：" + StaticToggleBridge.describe());
        lines.add("解散结构　　：" + DissolveBridge.describe());
        lines.add("创造板条箱　：" + CreativeCrateBridge.describe() + "（工厂核心无限材料模式靠它）");

        lines.add("=== 能调到的类 ===");
        for (String className : CANDIDATE_CLASSES) {
            try {
                Class<?> clazz = Class.forName(className);
                lines.add("✔ " + className);
                int shown = 0;
                for (Method method : clazz.getMethods()) {
                    if (!Modifier.isStatic(method.getModifiers())) {
                        continue;
                    }
                    if (shown++ >= 12) {
                        lines.add("    ……（还有更多）");
                        break;
                    }
                    lines.add("    " + method.getName() + "(" + params(method) + ") -> "
                            + method.getReturnType().getSimpleName());
                }
            } catch (ClassNotFoundException e) {
                lines.add("✘ " + className + "（没找到）");
            } catch (Throwable t) {
                lines.add("⚠ " + className + " 探测异常：" + t);
            }
        }

        lines.add("=== 我们的桥接状态 ===");
        lines.add("Sable 组装：" + (SableBridge.helperPresent()
                ? "找到 " + SableBridge.foundAssembleMethod() : "不可用"));
        lines.add("无碰撞开关：" + StaticToggleBridge.describe());
        return lines;
    }

    private static String params(Method method) {
        StringBuilder sb = new StringBuilder();
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(types[i].getSimpleName());
        }
        return sb.toString();
    }

    private IntegrationProbe() {
    }
}
