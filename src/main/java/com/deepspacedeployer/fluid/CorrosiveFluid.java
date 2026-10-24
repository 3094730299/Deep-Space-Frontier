package com.deepspacedeployer.fluid;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.material.FluidState;

/**
 * 高腐蚀溶液的小工具（流体本体直接用 NeoForge 的 {@code BaseFlowingFluid.Source/Flowing}）。
 *
 * <p><b>流动性：水和岩浆之间</b>（老板 2026-10-24 定）——</p>
 * <ul>
 *   <li>水：slopeFindDistance 4 / levelDecreasePerBlock 1 / tickRate 5；</li>
 *   <li>岩浆：slopeFindDistance 2 / levelDecreasePerBlock 2 / tickRate 30；</li>
 *   <li>腐蚀液取中间：<b>3 / 2 / 10</b>（见 {@link ModFluids#properties()}）。</li>
 * </ul>
 *
 * <p>⚠️ 2026-10-24 翻车记录：本文件原来自己写了 {@code extends BaseFlowingFluid.Flowing}
 * 的子类，并在 {@code createFluidStateDefinition} 里再 {@code builder.add(LEVEL)} 一次 ——
 * NeoForge 的 {@code BaseFlowingFluid.Flowing} <b>已经加过 LEVEL</b>，
 * 于是注册时抛 {@code IllegalArgumentException: [unregistered] has duplicate property: level}，
 * 整个 deepspacedeployer 变成 broken mod state，连带 FTB Quests 的资源重载崩掉。
 * <b>结论：不要自己写这两个子类，直接用基类。</b></p>
 */
public final class CorrosiveFluid {

    /** 工具：这个位置是不是本模组的腐蚀液（源头/流动都算） */
    public static boolean isCorrosive(BlockGetter level, BlockPos pos) {
        return isCorrosive(level.getFluidState(pos));
    }

    /** 工具：这个流体状态是不是本模组的腐蚀液 */
    public static boolean isCorrosive(FluidState state) {
        return state.getType().isSame(ModFluids.CORROSIVE_STILL.get());
    }

    private CorrosiveFluid() {
    }
}
