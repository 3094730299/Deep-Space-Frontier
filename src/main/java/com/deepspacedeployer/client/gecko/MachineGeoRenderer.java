package com.deepspacedeployer.client.gecko;

import com.deepspacedeployer.blockentity.MachineBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

/**
 * 机器类方块的统一渲染器。
 *
 * <p>它服务同一个方块实体类型（{@code MACHINE}）下的所有机器，
 * 靠 {@link MachineGeoModel} 按种类挑几何/贴图。</p>
 *
 * <p>⚠️ <b>没有自定义模型的机器（工厂终端 / 寄售市场）必须直接返回</b> ——
 * 它们由原版方块模型渲染。不返回的话，GeckoLib 会去找
 * {@code geo/block/null.geo.json} 并抛
 * {@code RuntimeException: ...null.geo.json: Unable to find model} 把游戏崩掉
 * （这个坑踩过一次：注册渲染器时忘了过滤种类）。</p>
 */
public class MachineGeoRenderer extends GeoBlockRenderer<MachineBlockEntity> {

    public MachineGeoRenderer(BlockEntityRendererProvider.Context context) {
        super(new MachineGeoModel());
    }

    @Override
    public void render(MachineBlockEntity animatable, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        // ① 这种机器没有自定义 Geo 模型 → 交给原版渲染，直接跳过
        //    （方块自身的 RenderShape 也只在 usesGeo 时才设成 INVISIBLE，两边是一致的）
        if (!MachineGeoModel.usesGeo(animatable.kind())) {
            return;
        }

        // ② 收购投送机：渲染方法每帧都会执行，是推动画阶段唯一可靠的地方
        if (animatable.isBuyer()) {
            animatable.clientAnimTick();
        }

        super.render(animatable, partialTick, poseStack, bufferSource, packedLight, packedOverlay);
    }
}
