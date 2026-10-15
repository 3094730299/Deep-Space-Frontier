package com.deepspacedeployer.client.gecko;

import com.deepspacedeployer.DeepSpaceDeployer;
import com.deepspacedeployer.Company;
import com.deepspacedeployer.blockentity.MachineKind;
import com.deepspacedeployer.blockentity.MachineBlockEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 机器类方块的统一 GeckoLib 模型 —— <b>按机器种类挑几何与贴图</b>。
 *
 * <p>为什么必须统一：收购站和工厂核心<b>共用同一个方块实体类型</b>（{@code MACHINE}），
 * 而原版一个方块实体类型<b>只能有一个渲染器</b> ——
 * 分别注册两个渲染器时，后注册的会把先注册的覆盖掉，
 * 现象就是"两个方块都变成后注册的那个模型"。</p>
 *
 * <pre>
 *   收购投送机（BUYER）  geo/block/buyer.geo.json         textures/block/buyer_&lt;公司&gt;.png
 *   载具打印机（CORE）   geo/block/factory_core.geo.json  textures/block/factory_core_&lt;公司&gt;.png
 * </pre>
 */
public class MachineGeoModel extends GeoModel<MachineBlockEntity> {

    private static final String[] COMPANY_IDS = {"threshold", "whisperbelt", "brokenedge",
            "skimmer", "bedrock", "astralink"};

    /** 该机器用哪个几何文件（没有自定义模型的种类返回 null → 用原版方块模型） */
    public static String geoName(MachineKind kind) {
        return switch (kind) {
            case BUYER -> "buyer";
            case CORE -> "factory_core";
            default -> null;
        };
    }

    /** 这种机器是否走 GeckoLib 渲染 */
    public static boolean usesGeo(MachineKind kind) {
        return geoName(kind) != null;
    }

    private static String companyId(MachineBlockEntity be) {
        Company company = be.company();
        return COMPANY_IDS[Math.max(0, Math.min(COMPANY_IDS.length - 1, company.ordinal()))];
    }

    @Override
    public ResourceLocation getModelResource(MachineBlockEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(DeepSpaceDeployer.MOD_ID,
                "geo/block/" + geoName(animatable.kind()) + ".geo.json");
    }

    @Override
    public ResourceLocation getAnimationResource(MachineBlockEntity animatable) {
        // 只有收购投送机有动画；载具打印机是静止模型（没有这个文件也没关系，
        // 因为它的控制器不会去播任何东西）。
        return ResourceLocation.fromNamespaceAndPath(DeepSpaceDeployer.MOD_ID,
                "animations/block/" + geoName(animatable.kind()) + ".animation.json");
    }

    @Override
    public ResourceLocation getTextureResource(MachineBlockEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(DeepSpaceDeployer.MOD_ID,
                "textures/block/" + geoName(animatable.kind()) + "_" + companyId(animatable) + ".png");
    }
}
