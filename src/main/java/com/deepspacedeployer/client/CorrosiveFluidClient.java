package com.deepspacedeployer.client;

import com.deepspacedeployer.DeepSpaceDeployer;
import com.deepspacedeployer.fluid.ModFluids;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

/**
 * 高腐蚀溶液的客户端表现：静止/流动贴图 + 不染色。
 *
 * <p>贴图就是 `桌面\腐蚀液体贴图\` 里那两张（16×16，已放进
 * {@code textures/block/corrosive_solution_still.png} 和 {@code _flow.png}）。
 * 颜色直接用贴图本身，所以 tint 给纯白，免得被再乘一遍绿色。</p>
 */
public final class CorrosiveFluidClient {

    private static final ResourceLocation STILL =
            ResourceLocation.fromNamespaceAndPath(DeepSpaceDeployer.MOD_ID, "block/corrosive_solution_still");

    private static final ResourceLocation FLOWING =
            ResourceLocation.fromNamespaceAndPath(DeepSpaceDeployer.MOD_ID, "block/corrosive_solution_flow");

    public static void register(RegisterClientExtensionsEvent event) {
        event.registerFluidType(new IClientFluidTypeExtensions() {
            @Override
            public ResourceLocation getStillTexture() {
                return STILL;
            }

            @Override
            public ResourceLocation getFlowingTexture() {
                return FLOWING;
            }

            @Override
            public int getTintColor() {
                return 0xFFFFFFFF;      // 贴图自带颜色，不再染色
            }
        }, ModFluids.CORROSIVE_TYPE);
    }

    private CorrosiveFluidClient() {
    }
}
