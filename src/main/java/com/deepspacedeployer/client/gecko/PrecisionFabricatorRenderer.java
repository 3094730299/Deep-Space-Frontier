package com.deepspacedeployer.client.gecko;

import com.deepspacedeployer.DeepSpaceDeployer;
import com.deepspacedeployer.blockentity.PrecisionFabricatorBlockEntity;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

/**
 * 精构仪的渲染器（装饰方块，静止）。
 *
 * <p>模型 / 动画 / 贴图三个文件同名：{@code precision_fabricator}。</p>
 *
 * <ul>
 *   <li>geo：{@code geo/block/precision_fabricator.geo.json}（声明 256×256，UV 上限 124）</li>
 *   <li>动画：{@code animations/block/precision_fabricator.animation.json}（「制作动画」，暂不播）</li>
 *   <li>贴图：{@code textures/block/precision_fabricator.png}（512×512，由
 *       {@code tools/gen_precision_fabricator_texture.py} 按 .bbmodel 骨骼分组生成；
 *       geo 声明 256、PNG 取 2 倍，见 README 铁律 13）</li>
 * </ul>
 */
public class PrecisionFabricatorRenderer extends GeoBlockRenderer<PrecisionFabricatorBlockEntity> {

    public PrecisionFabricatorRenderer(BlockEntityRendererProvider.Context context) {
        super(new FabricatorModel());
    }

    private static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath(DeepSpaceDeployer.MOD_ID, path);
    }

    private static final class FabricatorModel extends GeoModel<PrecisionFabricatorBlockEntity> {
        @Override
        public ResourceLocation getModelResource(PrecisionFabricatorBlockEntity animatable) {
            return rl("geo/block/precision_fabricator.geo.json");
        }

        @Override
        public ResourceLocation getAnimationResource(PrecisionFabricatorBlockEntity animatable) {
            return rl("animations/block/precision_fabricator.animation.json");
        }

        @Override
        public ResourceLocation getTextureResource(PrecisionFabricatorBlockEntity animatable) {
            return rl("textures/block/precision_fabricator.png");
        }
    }
}
