package com.deepspacedeployer.client.gecko;

import com.deepspacedeployer.DeepSpaceDeployer;
import com.deepspacedeployer.blockentity.MachineBlockEntity;
import software.bernie.geckolib.animation.Animation;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;

/**
 * 「星联动力联盟商店」的<b>展开 / 闭合</b>动画状态机。
 *
 * <p>老板的要求（2026-10-16）：</p>
 * <ol>
 *   <li>玩家进到 <b>水平 2 格</b>内 → 播「展开」，停在最后一帧；</li>
 *   <li>玩家离开 2 格 → 播「闭合」，停在最后一帧；</li>
 *   <li><b>一个动画播完才允许播另一个</b>，不许冲突、不许频繁触发。</li>
 * </ol>
 *
 * <p><b>为什么单独一个类</b>：收购投送机那套相位机（{@code MachineBlockEntity} 里的
 * {@code AnimPhase}）已经很复杂了，两套混在一起必然互相踩。
 * 这里自成一体，只通过 {@link MachineBlockEntity#anyoneNear()}（服务端算好的状态）
 * 和 {@link MachineBlockEntity#restartAnimation}（重启动画）跟外面打交道。</p>
 *
 * <p><b>为什么"停在最后一帧"能成立</b>：动画本身是 {@code hold_on_last_frame}
 * （见 terminal_astralink.animation.json），而播完之后状态处理器<b>继续返回同一个动画</b> ——
 * GeckoLib 的 {@code setAnimation} 在 {@code !needsAnimationReload && equals(当前动画)}
 * 时会<b>早退</b>，所以不会重播，姿势就停在最后一帧。</p>
 *
 * <p>⚠️ <b>重启动画只能用 {@code forceAnimationReset}</b>（见 README 铁律 12）：
 * 用触发式动画（{@code tryTriggerAnimation}）会让动画播完时被"再请求一次"，
 * 出现播两遍／播到一半重置。</p>
 */
public final class TerminalShopAnim {

    /** 控制器名（和 registerControllers 里注册的一致） */
    public static final String CTRL = "shop";
    public static final String ANIM_OPEN = "展开";
    public static final String ANIM_CLOSE = "闭合";

    private static final RawAnimation OPEN =
            RawAnimation.begin().then(ANIM_OPEN, Animation.LoopType.HOLD_ON_LAST_FRAME);
    private static final RawAnimation CLOSE =
            RawAnimation.begin().then(ANIM_CLOSE, Animation.LoopType.HOLD_ON_LAST_FRAME);

    /**
     * 一个动画按多久算"播完"。
     *
     * <p>动画文件里是 0.5 秒；这里给 520ms 留一点余量 ——
     * 宁可多等 20ms 再接受下一次切换，也不要"上一动画其实还在最后几帧"就切下一个。</p>
     */
    private static final long ANIM_MS = 520L;

    private final MachineBlockEntity be;

    /** 已经生效的状态（只在动画播完时更新） */
    private boolean open;
    /** 正在播的那个动画的终点 */
    private boolean target;
    /** 是否正在播展开/闭合 */
    private boolean moving;
    /** 本次动画的开始时刻（毫秒，相对动画时间原点） */
    private long moveStartedMs;
    /** 时间原点（墙上时钟 —— 渲染帧率远高于 20 tick，不能用游戏 tick 计时） */
    private long originNs;
    /** 同帧去重 */
    private long lastNs;
    /**
     * 是否已经播过至少一个动画。
     *
     * <p>用来避开"刚进游戏时商店自己关一次"的尴尬：模型的静止姿势本来就是<b>闭合</b>姿势，
     * 所以在还没播过任何动画、且状态是闭合时，状态处理器直接返回 {@code STOP}
     * （保持默认姿势），而不是去播一遍「闭合」。</p>
     */
    private boolean everShown;

    public TerminalShopAnim(MachineBlockEntity be) {
        this.be = be;
    }

    /** 当前是不是展开状态（已生效的那个） */
    public boolean isOpen() {
        return open;
    }

    public boolean isMoving() {
        return moving;
    }

    /**
     * 每帧推进一次。
     *
     * <p>挂在 GeckoLib 控制器的状态处理器里调用 —— 控制器回调是<b>每帧必到</b>的心跳，
     * 比方块 ticker 可靠（收购投送机那套就是这么做的，见 §7.1）。</p>
     */
    public void tick() {
        if (be.getLevel() == null || !be.getLevel().isClientSide) {
            return;
        }
        if (be.kind() != com.deepspacedeployer.blockentity.MachineKind.TERMINAL) {
            return;                                  // 只有商店用这套
        }
        // 只有"这台真的在用 GeckoLib 模型"才推（星联的商店才有模型；其余公司走原版方块模型）
        if (!MachineGeoModel.usesGeo(be)) {
            return;
        }

        // ★ 必须先把 GeckoLib 的时间轴推起来！
        //   否则动画永远停在第 0 帧 —— 状态机日志一切正常，画面上一点不动
        //   （「展开」的第 0 帧正好是闭合姿势，看起来就像"根本没播"）。
        //   见 MachineBlockEntity.advanceAnimClockOnly 的说明。
        be.advanceAnimClockOnly();

        long now = System.nanoTime();
        if (now - lastNs < 1_000_000L) {
            return;                                  // 同一帧被调多次只算一次
        }
        lastNs = now;
        if (originNs == 0L) {
            originNs = now;
        }
        long nowMs = (now - originNs) / 1_000_000L;

        boolean want = be.anyoneNear();

        if (moving) {
            if (nowMs - moveStartedMs < ANIM_MS) {
                return;                              // ★ 正在播：绝不打断（老板的硬要求）
            }
            // 播完了：落实状态，然后继续往下走 ——
            // 如果这期间玩家又进出过（want 变了），这里会立刻接着播反向动画，
            // 也就是"排队"，而不是把切换丢掉、也不是打断当前动画。
            moving = false;
            open = target;
            DeepSpaceDeployer.LOGGER.info(
                    "[深空铸造体][商店动画] 播完「{}」→ 当前={}（{}ms）",
                    open ? ANIM_OPEN : ANIM_CLOSE, open ? "展开" : "闭合", ANIM_MS);
        }

        if (want != open) {
            target = want;
            moving = true;
            everShown = true;
            moveStartedMs = nowMs;
            be.restartAnimation(CTRL, want ? ANIM_OPEN : ANIM_CLOSE);
            DeepSpaceDeployer.LOGGER.info(
                    "[深空铸造体][商店动画] 开始播「{}」（位置={} 有人靠近={}）",
                    want ? ANIM_OPEN : ANIM_CLOSE, be.getBlockPos(), want ? "是" : "否");
        }
    }

    /**
     * 控制器状态处理器：返回"这一刻该播哪个动画"。
     *
     * <p>三条规则：</p>
     * <ol>
     *   <li>不是商店（或这家没用自定义模型）→ {@code STOP}，完全不参与；</li>
     *   <li><b>还没播过任何动画、且状态是闭合</b> → {@code STOP}
     *       （模型静止姿势就是闭合姿势，别在进游戏时平白播一遍「闭合」）；</li>
     *   <li>其余情况返回对应动画并一直保持 —— 配合 {@code hold_on_last_frame}
     *       就是老板要的"停在最后一帧"。切换中返回<b>目标</b>动画那个。</li>
     * </ol>
     */
    public PlayState playState(AnimationState<MachineBlockEntity> state) {
        if (be.kind() != com.deepspacedeployer.blockentity.MachineKind.TERMINAL
                || !MachineGeoModel.usesGeo(be)) {
            return PlayState.STOP;
        }
        boolean shown = moving ? target : open;
        if (!everShown && !shown) {
            return PlayState.STOP;                   // 保持默认（闭合）姿势，不播任何动画
        }
        return state.setAndContinue(shown ? OPEN : CLOSE);
    }
}
