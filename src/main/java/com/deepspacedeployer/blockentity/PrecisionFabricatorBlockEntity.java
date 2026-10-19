package com.deepspacedeployer.blockentity;

import com.deepspacedeployer.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animatable.instance.SingletonAnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;

/**
 * 精构仪的方块实体 —— <b>只负责"让 GeckoLib 有东西可画"</b>。
 *
 * <p>它不存物品、不做 tick 逻辑：老板 2026-10-19 的要求是"暂时只作为装饰方块"。</p>
 *
 * <p>⚠️ <b>动画暂时不播</b>：模型自带一条 5 秒的「制作动画」（{@code hold_on_last_frame}），
 * 文件已经放进 {@code animations/block/precision_fabricator.animation.json}，
 * 但这里<b>没有注册控制器</b> —— 要播的时候加一个控制器即可
 * （想"放下时播一次"就照 {@code TerminalShopAnim} 那套：服务端触发信号 + 客户端播 + 播完回执）。</p>
 */
public class PrecisionFabricatorBlockEntity extends BlockEntity implements GeoAnimatable {

    private final AnimatableInstanceCache cache = new SingletonAnimatableInstanceCache(this);

    public PrecisionFabricatorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PRECISION_FABRICATOR.get(), pos, state);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // 装饰方块：暂时没有动画控制器（见类注释）
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    /** 静态模型不需要时间轴；返回 0 即可（没有控制器会用到它） */
    @Override
    public double getTick(Object object) {
        return 0;
    }
}
