package com.deepspacedeployer.corrosion;

import com.deepspacedeployer.DeepSpaceDeployer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

/**
 * 读写 Sable 物理结构的反射桥（<b>腐溶液溶蚀用</b>）。
 *
 * <p>和 {@code SableBridge} / {@code DissolveBridge} 一样，Sable 不在编译类路径里
 * （build.gradle 里那行 compileOnly 是注释掉的），所以全部靠反射。
 * 下面的签名是拿 {@code sable-neoforge-1.21.1-2.0.6.jar} 里的真实字节码校准的：</p>
 *
 * <pre>
 * dev.ryanhcode.sable.api.sublevel.SubLevelContainer
 *   public static ServerSubLevelContainer getContainer(ServerLevel)
 *   public java.util.List&lt;? extends SubLevel&gt; getAllSubLevels()
 * dev.ryanhcode.sable.sublevel.SubLevel
 *   public java.util.UUID getUniqueId()
 *   public dev.ryanhcode.sable.companion.math.BoundingBox3dc boundingBox()   // 世界坐标 AABB
 *   public dev.ryanhcode.sable.companion.math.Pose3dc logicalPose()
 *   public dev.ryanhcode.sable.sublevel.plot.LevelPlot getPlot()
 * dev.ryanhcode.sable.companion.math.BoundingBox3dc
 *   public net.minecraft.world.phys.AABB toMojang()
 * dev.ryanhcode.sable.companion.math.Pose3dc
 *   public net.minecraft.world.phys.Vec3 transformPositionInverse(Vec3)      // 世界 → 结构本地
 * dev.ryanhcode.sable.sublevel.plot.LevelPlot
 *   public dev.ryanhcode.sable.sublevel.plot.EmbeddedPlotLevelAccessor getEmbeddedLevelAccessor()
 *   public dev.ryanhcode.sable.companion.math.BoundingBox3ic getBoundingBox()  // 本地整数盒
 * dev.ryanhcode.sable.sublevel.plot.EmbeddedPlotLevelAccessor
 *   public net.minecraft.world.level.block.state.BlockState getBlockState(BlockPos)
 *   public boolean removeBlock(BlockPos, boolean)
 * dev.ryanhcode.sable.sublevel.ServerSubLevel
 *   public dev.ryanhcode.sable.api.physics.mass.MassData getMassTracker()
 *   public void buildMassTracker()
 * dev.ryanhcode.sable.api.physics.mass.MassData
 *   public double getMass()
 * </pre>
 *
 * <p>⚠️ 所有方法都可能因为 Sable 换版本而失效，所以每个入口都做了 try/catch，
 * 失败只打日志、不抛异常 —— 溶蚀系统整体是可选的（没装 Sable 就什么都不做）。</p>
 */
public final class CorrosionBridge {

    private static final String CONTAINER_CLASS = "dev.ryanhcode.sable.api.sublevel.SubLevelContainer";
    private static final String SUB_LEVEL_CLASS = "dev.ryanhcode.sable.sublevel.SubLevel";
    private static final String SERVER_SUB_LEVEL_CLASS = "dev.ryanhcode.sable.sublevel.ServerSubLevel";
    private static final String PLOT_CLASS = "dev.ryanhcode.sable.sublevel.plot.LevelPlot";
    private static final String ACCESSOR_CLASS = "dev.ryanhcode.sable.sublevel.plot.EmbeddedPlotLevelAccessor";
    private static final String MASS_DATA_CLASS = "dev.ryanhcode.sable.api.physics.mass.MassData";

    private static boolean probed;
    private static boolean ok;

    private static Method getContainer;
    private static Method getAllSubLevels;
    private static Method getUniqueId;
    private static Method boundingBox;
    private static Method logicalPose;
    private static Method getPlot;
    private static Method bbToMojang;
    private static Method poseToLocal;
    private static Method poseToWorld;
    private static Method plotAccessor;
    private static Method plotBounds;
    private static Method plotCenter;
    private static Method accessorGetState;
    private static Method accessorRemoveBlock;
    private static Method getMassTracker;
    private static Method buildMassTracker;
    private static Method massGet;
    private static Method bbMinX;
    private static Method bbMinY;
    private static Method bbMinZ;
    private static Method bbMaxX;
    private static Method bbMaxY;
    private static Method bbMaxZ;
    private static Method massInvalid;

    private static synchronized void probe() {
        if (probed) {
            return;
        }
        probed = true;
        try {
            Class<?> container = Class.forName(CONTAINER_CLASS);
            getContainer = container.getMethod("getContainer", ServerLevel.class);
            getAllSubLevels = container.getMethod("getAllSubLevels");

            Class<?> subLevel = Class.forName(SUB_LEVEL_CLASS);
            getUniqueId = subLevel.getMethod("getUniqueId");
            boundingBox = subLevel.getMethod("boundingBox");
            logicalPose = subLevel.getMethod("logicalPose");
            getPlot = subLevel.getMethod("getPlot");

            Class<?> bb3d = Class.forName("dev.ryanhcode.sable.companion.math.BoundingBox3dc");
            bbToMojang = bb3d.getMethod("toMojang");

            Class<?> pose = Class.forName("dev.ryanhcode.sable.companion.math.Pose3dc");
            poseToLocal = pose.getMethod("transformPositionInverse", Vec3.class);
            poseToWorld = pose.getMethod("transformPosition", Vec3.class);

            Class<?> plot = Class.forName(PLOT_CLASS);
            plotAccessor = plot.getMethod("getEmbeddedLevelAccessor");
            plotBounds = plot.getMethod("getBoundingBox");
            plotCenter = plot.getMethod("getCenterBlock");

            Class<?> bb3i = Class.forName("dev.ryanhcode.sable.companion.math.BoundingBox3ic");
            bbMinX = bb3i.getMethod("minX");
            bbMinY = bb3i.getMethod("minY");
            bbMinZ = bb3i.getMethod("minZ");
            bbMaxX = bb3i.getMethod("maxX");
            bbMaxY = bb3i.getMethod("maxY");
            bbMaxZ = bb3i.getMethod("maxZ");

            Class<?> accessor = Class.forName(ACCESSOR_CLASS);
            accessorGetState = accessor.getMethod("getBlockState", BlockPos.class);
            accessorRemoveBlock = accessor.getMethod("removeBlock", BlockPos.class, boolean.class);

            Class<?> serverSubLevel = Class.forName(SERVER_SUB_LEVEL_CLASS);
            getMassTracker = serverSubLevel.getMethod("getMassTracker");
            buildMassTracker = serverSubLevel.getMethod("buildMassTracker");

            Class<?> massData = Class.forName(MASS_DATA_CLASS);
            massGet = massData.getMethod("getMass");
            massInvalid = massData.getMethod("isInvalid");

            ok = true;
            DeepSpaceDeployer.LOGGER.info("[深空铸造体] 腐蚀系统已接上 Sable：可逐方块读写物理结构");
        } catch (Throwable t) {
            ok = false;
            DeepSpaceDeployer.LOGGER.warn("[深空铸造体] 没找到 Sable 的逐方块接口，腐蚀系统停用：{}", t.toString());
        }
    }

    public static boolean available() {
        probe();
        return ok;
    }

    public static String describe() {
        probe();
        return ok ? "Sable 逐方块读写 ✓（SubLevelContainer + EmbeddedPlotLevelAccessor）" : "不可用";
    }

    // ---------------- 枚举 ----------------

    public static Object container(ServerLevel level) {
        if (!available()) {
            return null;
        }
        try {
            return getContainer.invoke(null, level);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static List<Object> subLevels(Object container) {
        if (!available() || container == null) {
            return List.of();
        }
        try {
            Object list = getAllSubLevels.invoke(container);
            return list instanceof List<?> l ? (List<Object>) l : List.of();
        } catch (Throwable t) {
            return List.of();
        }
    }

    public static UUID id(Object subLevel) {
        try {
            Object v = getUniqueId.invoke(subLevel);
            return v instanceof UUID uuid ? uuid : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 世界坐标下的 AABB；拿不到返回 null */
    public static AABB worldBounds(Object subLevel) {
        try {
            Object box = boundingBox.invoke(subLevel);
            if (box == null) {
                return null;
            }
            Object aabb = bbToMojang.invoke(box);
            return aabb instanceof AABB a ? a : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 世界坐标 → 结构本地坐标（方块空间） */
    public static BlockPos toLocal(Object subLevel, Vec3 world) {
        try {
            Object pose = logicalPose.invoke(subLevel);
            if (pose == null) {
                return null;
            }
            Object local = poseToLocal.invoke(pose, world);
            if (!(local instanceof Vec3 v)) {
                return null;
            }
            return BlockPos.containing(v);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 结构本地（plot）坐标 → 世界坐标（取方块中心）；拿不到返回 null */
    public static Vec3 toWorld(Object subLevel, BlockPos plotPos) {
        try {
            Object pose = logicalPose.invoke(subLevel);
            if (pose == null) {
                return null;
            }
            Object world = poseToWorld.invoke(pose, Vec3.atCenterOf(plotPos));
            return world instanceof Vec3 v ? v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------- 逐方块 ----------------

    public static BlockState stateAt(Object subLevel, BlockPos local) {
        try {
            Object accessor = plotAccessor.invoke(getPlot.invoke(subLevel));
            if (accessor == null) {
                return null;
            }
            Object state = accessorGetState.invoke(accessor, local);
            return state instanceof BlockState s ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 删掉结构里的一块；返回是否真的删了 */
    public static boolean removeAt(Object subLevel, BlockPos local) {
        try {
            Object accessor = plotAccessor.invoke(getPlot.invoke(subLevel));
            if (accessor == null) {
                return false;
            }
            Object result = accessorRemoveBlock.invoke(accessor, local, false);
            return result instanceof Boolean b && b;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 结构本地整数盒 {minX,minY,minZ,maxX,maxY,maxZ}；拿不到返回 null */
    public static int[] localBounds(Object subLevel) {
        try {
            Object plot = getPlot.invoke(subLevel);
            if (plot == null) {
                return null;
            }
            Object box = plotBounds.invoke(plot);
            if (box == null) {
                return null;
            }
            return new int[]{
                    (int) bbMinX.invoke(box), (int) bbMinY.invoke(box), (int) bbMinZ.invoke(box),
                    (int) bbMaxX.invoke(box), (int) bbMaxY.invoke(box), (int) bbMaxZ.invoke(box)};
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 访问器的「中心」—— 关键！
     *
     * <p>{@code EmbeddedPlotLevelAccessor} 的所有方块读写都是
     * <code>level.getBlockState(pos.offset(center))</code>（<b>它自己会加 center</b>），
     * 所以调用方必须传「相对 center 的坐标」。传 plot 绝对坐标就会读偏 center 那么远 ——
     * 2026-10-24 老板的橡木原木"检测不到"就是这个原因。</p>
     *
     * <p>对应字节码：{@code LevelPlot.getCenterBlock()} =
     * BlockPos(centerChunk.minBlockX + 8, (minBuildHeight+maxBuildHeight)/2, centerChunk.minBlockZ + 8)。</p>
     */
    public static BlockPos plotCenter(Object subLevel) {
        try {
            Object plot = getPlot.invoke(subLevel);
            if (plot == null) {
                return null;
            }
            Object center = plotCenter.invoke(plot);
            return center instanceof BlockPos pos ? pos : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 结构总质量（Sable 自己算的）；拿不到返回 -1 */
    public static double mass(Object subLevel) {
        try {
            Object tracker = getMassTracker.invoke(subLevel);
            if (tracker == null) {
                return -1.0D;
            }
            Object invalid = massInvalid.invoke(tracker);
            if (invalid instanceof Boolean b && b) {
                return -1.0D;
            }
            Object v = massGet.invoke(tracker);
            return v instanceof Double d ? d : -1.0D;
        } catch (Throwable t) {
            return -1.0D;
        }
    }

    /** 方块变动后让 Sable 重算质量（不然物理/拆解数据是旧的） */
    public static void rebuildMass(Object subLevel) {
        try {
            buildMassTracker.invoke(subLevel);
        } catch (Throwable ignored) {
            // 老版本没有这个方法就跳过，不是致命问题
        }
    }

    private CorrosionBridge() {
    }
}
