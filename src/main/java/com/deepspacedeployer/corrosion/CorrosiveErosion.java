package com.deepspacedeployer.corrosion;

import com.deepspacedeployer.Config;
import com.deepspacedeployer.DeepSpaceDeployer;
import com.deepspacedeployer.fluid.CorrosiveFluid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 高腐蚀溶液的<b>溶蚀系统</b>（老板 2026-10-24 定的规则）。
 *
 * <h2>规则</h2>
 * <ol>
 *   <li><b>只腐蚀物理结构</b>（Sable 的 SubLevel）；普通方块、玩家、生物都不受影响；</li>
 *   <li>接触判定是<b>贴面</b>，不需要完全浸入；</li>
 *   <li>每个方块单独计时：非本模组方块固定 8 秒；本模组方块按 Sable 质量 2~8 秒；</li>
 *   <li>打了 {@code #deepspacedeployer:corrosion_immune} 标签的方块永不腐蚀；</li>
 *   <li>到点后先"断裂"（不落地的方块实体），{@code corrosion.detach_ticks} 后消失。</li>
 * </ol>
 *
 * <h2>坐标空间（连翻两次车，务必看清）</h2>
 * <p>Sable 把结构方块存在宿主世界远处的「plot 坐标区」（坐标能到两千万级）。三种坐标：</p>
 * <ol>
 *   <li><b>世界坐标</b>：玩家看到的；</li>
 *   <li><b>pose 逆变换坐标</b>：{@code transformPositionInverse(世界坐标)}；</li>
 *   <li><b>plot 坐标</b>：真正能拿去读方块的（访问器里 level 的坐标）。</li>
 * </ol>
 * <p>实测：<b>2 和 3 之间差一个巨大的平移</b>（plot 区原点，量级 2×10^7）。所以：</p>
 * <pre>
 * shift   = plot盒中心 − pose盒中心
 * plotPos = pose逆变换(世界坐标) + shift
 * arg     = plotPos − 访问器center     // 访问器自己会加 center
 * </pre>
 * <p>shift 按结构算一次并缓存；标定时用世界 AABB 采样打分验证。
 * 读不到就<b>完全不腐蚀</b>并告警（宁可不生效，也不能乱删方块）。</p>
 *
 * <h2>为什么不"分裂成新的物理结构"</h2>
 * <p>Sable 没有公开的分裂/拆分子结构接口（只有整片 {@code removeSubLevel} 和
 * "把世界方块装配成一个结构"）。要真分裂，必须先把那块放进世界再装配，
 * 那一定会占掉那一格（把腐蚀液挤掉）——正好是老板不想要的效果。
 * 所以断裂用「不落地的落沙实体」表现：不落地、不会变回方块、不碰世界。</p>
 */
public final class CorrosiveErosion {

    /** 打上这个标签的方块不会被腐蚀（以后的「耐腐蚀材料」进这个标签就行） */
    public static final TagKey<Block> CORROSION_IMMUNE = TagKey.create(
            Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(DeepSpaceDeployer.MOD_ID, "corrosion_immune"));

    private record Key(UUID subLevel, BlockPos plotPos) {
    }

    private record Rate(int seconds, long computedAt) {
    }

    /** 坐标换算缓存：shift + 访问器 center + 标定命中数 */
    private record Xform(BlockPos shift, BlockPos accCenter, int hits, long at) {
        boolean usable() {
            return hits > 0;
        }
    }

    private record SolidCount(long count, long at) {
    }

    /** 断裂中的落沙实体 */
    private record Detached(FallingBlockEntity entity, long expireAt) {
    }

    private static final Map<Key, Integer> PROGRESS = new HashMap<>();
    private static final Map<UUID, Rate> RATES = new HashMap<>();
    private static final Map<UUID, Xform> XFORMS = new HashMap<>();
    private static final Map<UUID, SolidCount> SOLIDS = new HashMap<>();
    private static final Map<UUID, Long> LAST_WARN = new HashMap<>();
    private static final List<Detached> DETACHED = new ArrayList<>();

    private static final int RATE_TTL = 200;
    private static final int XFORM_TTL_OK = 6000;
    private static final int XFORM_TTL_BAD = 200;
    private static final int SOLID_TTL = 200;
    private static final int MAX_PROGRESS_ENTRIES = 40000;
    private static final int SAMPLES = 64;
    private static boolean warnedNoSable;

    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        tickDetached(server);
        if (!Config.CORROSION_ENABLED.get()) {
            return;
        }
        int interval = Math.max(1, Config.CORROSION_SCAN_INTERVAL.get());
        if (server.getTickCount() % interval != 0) {
            return;
        }
        if (!CorrosionBridge.available()) {
            if (!warnedNoSable) {
                warnedNoSable = true;
                DeepSpaceDeployer.LOGGER.info("[深空铸造体] 没有可用的 Sable 逐方块接口，腐蚀系统跳过");
            }
            return;
        }
        if (PROGRESS.size() > MAX_PROGRESS_ENTRIES) {
            PROGRESS.clear();
        }
        int budget = Math.max(1, Config.CORROSION_MAX_BLOCKS_PER_SCAN.get());
        long now = server.getTickCount();
        for (ServerLevel level : server.getAllLevels()) {
            budget = scanLevel(level, now, interval, budget);
            if (budget <= 0) {
                return;
            }
        }
    }

    private static int scanLevel(ServerLevel level, long now, int interval, int budget) {
        Object container = CorrosionBridge.container(level);
        if (container == null) {
            return budget;
        }
        for (Object sub : CorrosionBridge.subLevels(container)) {
            UUID id = CorrosionBridge.id(sub);
            if (id == null) {
                continue;
            }
            AABB box = CorrosionBridge.worldBounds(sub);
            if (box == null) {
                continue;
            }
            double volume = (box.maxX - box.minX) * (box.maxY - box.minY) * (box.maxZ - box.minZ);
            if (volume > Config.CORROSION_MAX_SCAN_VOLUME.get()) {
                continue;
            }
            Xform xform = xform(sub, id, box, now);
            if (xform == null || !xform.usable()) {
                continue;
            }
            long solid = solidCount(sub, id, xform, now);
            int minBlocks = Config.CORROSION_MIN_STRUCTURE_BLOCKS.get();
            if (solid >= 0 && solid < minBlocks) {
                warnOnce(id, now, "[深空铸造体][腐蚀] 结构 " + id + " 只有 " + solid + " 块（< "
                        + minBlocks + "）→ 跳过（corrosion.min_structure_blocks 可调）");
                continue;
            }
            budget = scanStructure(level, sub, id, box, xform, now, interval, budget);
            if (budget <= 0) {
                return 0;
            }
        }
        return budget;
    }

    private static int scanStructure(ServerLevel level, Object sub, UUID id, AABB box, Xform xform,
                                     long now, int interval, int budget) {
        int minFaces = Math.max(1, Config.CORROSION_MIN_CONTACT_FACES.get());

        // 先统计"每个结构方块被几格溶液贴着"，再决定腐蚀谁
        Map<BlockPos, Integer> faces = new HashMap<>();
        int fluids = 0;
        int x0 = (int) Math.floor(box.minX) - 1;
        int y0 = Math.max(level.getMinBuildHeight(), (int) Math.floor(box.minY) - 1);
        int z0 = (int) Math.floor(box.minZ) - 1;
        int x1 = (int) Math.floor(box.maxX) + 1;
        int y1 = Math.min(level.getMaxBuildHeight() - 1, (int) Math.floor(box.maxY) + 1);
        int z1 = (int) Math.floor(box.maxZ) + 1;

        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    BlockPos fluid = new BlockPos(x, y, z);
                    if (!level.isLoaded(fluid) || !CorrosiveFluid.isCorrosive(level, fluid)) {
                        continue;
                    }
                    fluids++;
                    for (Direction dir : Direction.values()) {
                        BlockPos plotPos = toPlotPos(sub, fluid.relative(dir), xform);
                        if (plotPos == null) {
                            continue;
                        }
                        BlockState state = CorrosionBridge.stateAt(sub, plotPos.subtract(xform.accCenter()));
                        if (state != null && !state.isAir() && !state.is(CORROSION_IMMUNE)) {
                            faces.merge(plotPos.immutable(), 1, Integer::sum);
                        }
                    }
                }
            }
        }

        if (fluids > 0 && faces.isEmpty()) {
            warnOnce(id, now, "[深空铸造体][腐蚀] 结构 " + id + " 有 " + fluids
                    + " 格溶液贴着，但坐标换算读不到方块 → 不腐蚀（/dsd corrosion 看详情）");
            return budget;
        }

        for (Map.Entry<BlockPos, Integer> entry : faces.entrySet()) {
            if (entry.getValue() < minFaces) {
                continue;                       // 只擦到一两个面：按配置忽略
            }
            BlockPos plotPos = entry.getKey();
            BlockState state = CorrosionBridge.stateAt(sub, plotPos.subtract(xform.accCenter()));
            if (state == null || state.isAir() || state.is(CORROSION_IMMUNE)) {
                continue;
            }
            Key key = new Key(id, plotPos);
            int need = needTicks(sub, id, state, now);
            int elapsed = PROGRESS.merge(key, interval, Integer::sum);
            if (elapsed < need) {
                continue;
            }
            PROGRESS.remove(key);
            if (Config.CORROSION_DETACH_ENABLED.get()) {
                detach(level, sub, plotPos, state, now);
            }
            if (!CorrosionBridge.removeAt(sub, plotPos.subtract(xform.accCenter()))) {
                continue;
            }
            SOLIDS.remove(id);                  // 实心数变了，下次重新数

            Vec3 world = CorrosionBridge.toWorld(sub, plotPos);
            double wx = world == null ? plotPos.getX() + 0.5D : world.x;
            double wy = world == null ? plotPos.getY() + 0.5D : world.y;
            double wz = world == null ? plotPos.getZ() + 0.5D : world.z;
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
                    wx, wy, wz, 3, 0.3D, 0.3D, 0.3D, 0.0D);

            if (--budget <= 0) {
                return 0;
            }
        }
        // 注意：这里不主动 rebuildMass —— 之前手动重算疑似让 Sable 判定结构异常、整片散架
        return budget;
    }

    /** 世界坐标 → plot 坐标（加上缓存的平移修正） */
    private static BlockPos toPlotPos(Object sub, BlockPos world, Xform xform) {
        BlockPos raw = CorrosionBridge.toLocal(sub, Vec3.atCenterOf(world));
        return raw == null ? null : raw.offset(xform.shift());
    }

    // ---------------- 坐标换算 ----------------

    private static Xform xform(Object sub, UUID id, AABB box, long now) {
        Xform cached = XFORMS.get(id);
        if (cached != null) {
            int ttl = cached.usable() ? XFORM_TTL_OK : XFORM_TTL_BAD;
            if (now - cached.at() < ttl) {
                return cached;
            }
        }
        BlockPos accCenter = CorrosionBridge.plotCenter(sub);
        if (accCenter == null) {
            return null;
        }
        BlockPos negCenter = new BlockPos(-accCenter.getX(), -accCenter.getY(), -accCenter.getZ());
        BlockPos rawCenter = CorrosionBridge.toLocal(sub,
                new Vec3((box.minX + box.maxX) / 2.0D, (box.minY + box.maxY) / 2.0D, (box.minZ + box.maxZ) / 2.0D));
        int[] bounds = CorrosionBridge.localBounds(sub);

        BlockPos alignShift = negCenter;
        if (rawCenter != null && bounds != null) {
            BlockPos plotCenter = new BlockPos(
                    (bounds[0] + bounds[3]) / 2, (bounds[1] + bounds[4]) / 2, (bounds[2] + bounds[5]) / 2);
            alignShift = plotCenter.subtract(rawCenter).offset(negCenter);
        }

        int alignHits = score(sub, box, alignShift);
        int zeroHits = score(sub, box, negCenter);
        BlockPos best = alignHits >= zeroHits ? alignShift : negCenter;
        int hits = Math.max(alignHits, zeroHits);
        Xform xform = new Xform(best, accCenter, hits, now);
        XFORMS.put(id, xform);
        if (hits == 0) {
            warnOnce(id, now, "[深空铸造体][腐蚀] 结构 " + id
                    + " 的坐标换算两种候选都读不到方块（Sable 版本变了？）→ 该结构不会被腐蚀");
        } else {
            DeepSpaceDeployer.LOGGER.info("[深空铸造体][腐蚀] 结构 {} 坐标换算 OK：命中 {}/{}，shift={}",
                    id, hits, SAMPLES, best);
        }
        return xform;
    }

    private static int score(Object sub, AABB box, BlockPos shift) {
        int side = Math.max(2, (int) Math.round(Math.cbrt(SAMPLES)));
        int hits = 0;
        double dx = Math.max(1.0D, box.maxX - box.minX);
        double dy = Math.max(1.0D, box.maxY - box.minY);
        double dz = Math.max(1.0D, box.maxZ - box.minZ);
        for (int i = 0; i < side; i++) {
            for (int j = 0; j < side; j++) {
                for (int k = 0; k < side; k++) {
                    BlockPos raw = CorrosionBridge.toLocal(sub, new Vec3(
                            box.minX + dx * (i + 0.5D) / side,
                            box.minY + dy * (j + 0.5D) / side,
                            box.minZ + dz * (k + 0.5D) / side));
                    if (raw == null) {
                        continue;
                    }
                    BlockState state = CorrosionBridge.stateAt(sub, raw.offset(shift));
                    if (state != null && !state.isAir()) {
                        hits++;
                    }
                }
            }
        }
        return hits;
    }

    private static long solidCount(Object sub, UUID id, Xform xform, long now) {
        SolidCount cached = SOLIDS.get(id);
        if (cached != null && now - cached.at() < SOLID_TTL) {
            return cached.count();
        }
        int[] bounds = CorrosionBridge.localBounds(sub);
        long count = -1L;
        if (bounds != null) {
            long volume = (long) (bounds[3] - bounds[0] + 1) * (bounds[4] - bounds[1] + 1) * (bounds[5] - bounds[2] + 1);
            if (volume > 0 && volume <= Config.CORROSION_MAX_STRUCTURE_BLOCKS.get()) {
                count = 0L;
                for (int x = bounds[0]; x <= bounds[3]; x++) {
                    for (int y = bounds[1]; y <= bounds[4]; y++) {
                        for (int z = bounds[2]; z <= bounds[5]; z++) {
                            BlockState state = CorrosionBridge.stateAt(sub,
                                    new BlockPos(x, y, z).subtract(xform.accCenter()));
                            if (state != null && !state.isAir()) {
                                count++;
                            }
                        }
                    }
                }
            }
        }
        SOLIDS.put(id, new SolidCount(count, now));
        return count;
    }

    // ---------------- 时间规则 ----------------

    private static int needTicks(Object sub, UUID id, BlockState state, long now) {
        int seconds = isOurBlock(state) ? secondsForStructure(sub, id, now)
                : Config.CORROSION_NON_MOD_SECONDS.get();
        return Math.max(1, seconds) * 20;
    }

    private static int secondsForStructure(Object sub, UUID id, long now) {
        int fastest = Config.CORROSION_SECONDS_FASTEST.get();
        int slowest = Config.CORROSION_SECONDS_SLOWEST.get();
        Rate rate = RATES.get(id);
        if (rate != null && now - rate.computedAt() < RATE_TTL) {
            return rate.seconds();
        }
        int seconds = slowest;
        double mass = CorrosionBridge.mass(sub);
        Xform xform = XFORMS.get(id);
        long solid = xform == null ? -1L : solidCount(sub, id, xform, now);
        if (mass > 0.0D && solid > 0) {
            double perBlock = mass / solid;
            double min = Config.CORROSION_MASS_PER_BLOCK_MIN.get();
            double max = Config.CORROSION_MASS_PER_BLOCK_MAX.get();
            double t = max > min ? (perBlock - min) / (max - min) : 0.5D;
            t = Math.max(0.0D, Math.min(1.0D, t));
            seconds = (int) Math.round(fastest + (slowest - fastest) * t);
        }
        RATES.put(id, new Rate(seconds, now));
        return seconds;
    }

    private static boolean isOurBlock(BlockState state) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return key != null && DeepSpaceDeployer.MOD_ID.equals(key.getNamespace());
    }

    // ---------------- 断裂表现 ----------------

    /**
     * "断裂"：生成一个<b>不会落地</b>的方块实体，稍后消失。
     *
     * <p>Sable 没有公开的分裂接口，做不到"变成独立物理结构"；这里保证它
     * <b>永远不落地</b> → 不会把方块放回去、不会挤掉腐蚀液。</p>
     */
    private static void detach(ServerLevel level, Object sub, BlockPos plotPos, BlockState state, long now) {
        try {
            Vec3 world = CorrosionBridge.toWorld(sub, plotPos);
            if (world == null) {
                return;
            }
            FallingBlockEntity entity = FallingBlockEntity.fall(level, BlockPos.containing(world), state);
            if (entity == null) {
                return;
            }
            entity.dropItem = false;
            entity.time = 0;
            entity.setNoGravity(true);
            entity.setDeltaMovement(
                    (level.random.nextDouble() - 0.5D) * 0.03D, 0.0D, (level.random.nextDouble() - 0.5D) * 0.03D);
            DETACHED.add(new Detached(entity, now + Math.max(1, Config.CORROSION_DETACH_TICKS.get())));
        } catch (Throwable ignored) {
            // 断裂只是表现层
        }
    }

    private static void tickDetached(MinecraftServer server) {
        if (DETACHED.isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        Iterator<Detached> it = DETACHED.iterator();
        while (it.hasNext()) {
            Detached d = it.next();
            FallingBlockEntity entity = d.entity();
            if (entity.isRemoved()) {
                it.remove();
                continue;
            }
            if (now < d.expireAt()) {
                continue;
            }
            if (entity.level() instanceof ServerLevel serverLevel) {
                serverLevel.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
                        entity.getX(), entity.getY() + 0.3D, entity.getZ(), 8, 0.25D, 0.25D, 0.25D, 0.0D);
            }
            entity.discard();
            it.remove();
        }
    }

    private static void warnOnce(UUID id, long now, String message) {
        Long last = LAST_WARN.get(id);
        if (last != null && now - last < 200L) {
            return;
        }
        LAST_WARN.put(id, now);
        DeepSpaceDeployer.LOGGER.warn(message);
    }

    public static void invalidate() {
        PROGRESS.clear();
        RATES.clear();
        XFORMS.clear();
        SOLIDS.clear();
        LAST_WARN.clear();
        for (Detached d : DETACHED) {
            d.entity().discard();
        }
        DETACHED.clear();
    }

    // ---------------- /dsd corrosion 诊断 ----------------

    public static List<String> diagnose(MinecraftServer server) {
        List<String> out = new ArrayList<>();
        out.add("=== 高腐蚀溶液 · 溶蚀诊断 ===");
        out.add("Sable 接口：" + CorrosionBridge.describe());
        out.add("配置：enabled=" + Config.CORROSION_ENABLED.get()
                + "，间隔=" + Config.CORROSION_SCAN_INTERVAL.get() + "t"
                + "，上限=" + Config.CORROSION_MAX_BLOCKS_PER_SCAN.get() + " 块/次"
                + "，最小贴面=" + Config.CORROSION_MIN_CONTACT_FACES.get()
                + "，本模组 " + Config.CORROSION_SECONDS_FASTEST.get() + "~" + Config.CORROSION_SECONDS_SLOWEST.get() + "s/块"
                + "，普通 " + Config.CORROSION_NON_MOD_SECONDS.get() + "s/块");
        if (!CorrosionBridge.available()) {
            out.add("→ 桥不可用：没装 Sable 或 API 变了，腐蚀全部停用");
            return out;
        }
        out.add("→ 只腐蚀【Sable 物理结构】；世界里普通摆放的方块不会被腐蚀（否则腐蚀群系会把自己吃掉）");
        long now = server.getTickCount();
        for (ServerLevel level : server.getAllLevels()) {
            Object container = CorrosionBridge.container(level);
            List<Object> subs = CorrosionBridge.subLevels(container);
            out.add("【" + level.dimension().location() + "】物理结构 " + subs.size() + " 个");
            for (Object sub : subs) {
                UUID id = CorrosionBridge.id(sub);
                AABB box = CorrosionBridge.worldBounds(sub);
                if (box == null) {
                    out.add("  · " + id + "：拿不到世界 AABB");
                    continue;
                }
                Xform xform = xform(sub, id, box, now);
                if (xform == null) {
                    out.add("  · " + id + "：拿不到访问器 center");
                    continue;
                }
                long solid = solidCount(sub, id, xform, now);
                int[] bounds = CorrosionBridge.localBounds(sub);
                int[] probe = probeContacts(level, sub, box, xform);
                out.add(String.format(Locale.ROOT,
                        "  · %s  质量=%.1f 实心=%d | 坐标=%s（命中 %d/%d）| shift=%s | center=%s"
                                + " | 溶液=%d 格 → 贴面=%d 处、涉及方块=%d 块",
                        id, CorrosionBridge.mass(sub), solid,
                        xform.usable() ? "OK" : "失败", xform.hits(), SAMPLES, xform.shift(), xform.accCenter(),
                        probe[0], probe[1], probe[2]));
                if (bounds != null) {
                    out.add(String.format(Locale.ROOT, "      plot 本地盒=(%d,%d,%d)~(%d,%d,%d)",
                            bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]));
                }
                if (probe[0] == 0) {
                    out.add("      ↳ 结构外扩 1 格内没有腐蚀液（或那个区块没加载），什么都不会发生");
                } else if (probe[1] == 0) {
                    out.add("      ↳ 有溶液贴着却映射不到方块 → 坐标换算还有问题");
                }
            }
        }
        return out;
    }

    /** 诊断：{溶液格数, 贴面命中数, 涉及方块数} */
    private static int[] probeContacts(ServerLevel level, Object sub, AABB box, Xform xform) {
        Set<BlockPos> blocks = new HashSet<>();
        int fluids = 0;
        int faces = 0;
        int x0 = (int) Math.floor(box.minX) - 1;
        int y0 = Math.max(level.getMinBuildHeight(), (int) Math.floor(box.minY) - 1);
        int z0 = (int) Math.floor(box.minZ) - 1;
        int x1 = (int) Math.floor(box.maxX) + 1;
        int y1 = Math.min(level.getMaxBuildHeight() - 1, (int) Math.floor(box.maxY) + 1);
        int z1 = (int) Math.floor(box.maxZ) + 1;
        int scanned = 0;
        outer:
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    if (++scanned > 60000) {
                        break outer;
                    }
                    BlockPos fluid = new BlockPos(x, y, z);
                    if (!level.isLoaded(fluid) || !CorrosiveFluid.isCorrosive(level, fluid)) {
                        continue;
                    }
                    fluids++;
                    for (Direction dir : Direction.values()) {
                        BlockPos plotPos = toPlotPos(sub, fluid.relative(dir), xform);
                        if (plotPos == null) {
                            continue;
                        }
                        BlockState state = CorrosionBridge.stateAt(sub, plotPos.subtract(xform.accCenter()));
                        if (state != null && !state.isAir() && !state.is(CORROSION_IMMUNE)) {
                            faces++;
                            blocks.add(plotPos.immutable());
                        }
                    }
                }
            }
        }
        return new int[]{fluids, faces, blocks.size()};
    }

    private CorrosiveErosion() {
    }
}
