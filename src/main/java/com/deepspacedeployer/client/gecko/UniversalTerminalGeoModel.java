package com.deepspacedeployer.client.gecko;

import com.deepspacedeployer.DeepSpaceDeployer;
import com.deepspacedeployer.blockentity.UniversalTerminalBlockEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.DefaultedBlockGeoModel;

/**
 * GeckoLib 的模型定义。
 *
 * <p>{@link DefaultedBlockGeoModel} 按固定规则拼路径，所以文件放在：</p>
 * <pre>
 *   geo/block/universal_terminal.geo.json
 *   animations/block/universal_terminal.animation.json
 *   textures/block/universal_terminal.png
 * </pre>
 *
 * <p>这两份 json 都是 Blockbench 官方导出器的产物，没有经过任何自写转换。</p>
 */
public class UniversalTerminalGeoModel extends DefaultedBlockGeoModel<UniversalTerminalBlockEntity> {

    public UniversalTerminalGeoModel() {
        super(ResourceLocation.fromNamespaceAndPath(DeepSpaceDeployer.MOD_ID, "universal_terminal"));
    }
}
