package com.deepspacedeployer.block;

import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.FlowingFluid;

/**
 * 高腐蚀溶液方块（流体方块）。
 *
 * <p>没有任何额外碰撞/伤害逻辑：老板 2026-10-24 定「<b>只腐蚀物理结构</b>」，
 * 所以它本身对玩家、生物、普通方块都是“一滩绿水”，危害来自
 * {@link com.deepspacedeployer.corrosion.CorrosiveErosion} 对 Sable 物理结构的溶蚀。</p>
 */
public class CorrosiveLiquidBlock extends LiquidBlock {

    public CorrosiveLiquidBlock(FlowingFluid fluid, BlockBehaviour.Properties properties) {
        super(fluid, properties);
    }
}
