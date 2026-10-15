package com.deepspacedeployer.client.gecko;

import com.deepspacedeployer.blockentity.UniversalTerminalBlockEntity;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

/**
 * 通用终端的 GeckoLib 渲染器。
 *
 * <p>⚠️ 这里**刻意什么都不做**：</p>
 * <ul>
 *   <li>居中：{@link GeoBlockRenderer#preRender} 内部已经
 *       {@code translate(0.5, 0.0, 0.5)}（官方坐标是以原点为中心的）；</li>
 *   <li>朝向：GeckoLib 通过 {@code getFacing(T)} / {@code rotateBlock(...)} 自动按
 *       方块的 FACING 属性旋转。</li>
 * </ul>
 *
 * <p>早先我在这里又加了一次平移和旋转，结果偏移翻倍、朝向也重复，模型被推到方块边角。</p>
 */
public class UniversalTerminalGeoRenderer extends GeoBlockRenderer<UniversalTerminalBlockEntity> {

    public UniversalTerminalGeoRenderer(BlockEntityRendererProvider.Context context) {
        super(new UniversalTerminalGeoModel());
    }
}
