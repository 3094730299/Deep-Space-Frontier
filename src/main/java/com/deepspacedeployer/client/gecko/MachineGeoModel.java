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
 *   收购投送机（BUYER）       geo/block/buyer.geo.json          textures/block/buyer_&lt;公司&gt;.png
 *   载具打印机（CORE）        geo/block/factory_core.geo.json   textures/block/factory_core_&lt;公司&gt;.png
 *   星联动力联盟商店（TERMINAL）geo/block/terminal_astralink.geo.json
 *                                                              textures/block/terminal_astralink.png
 * </pre>
 *
 * <p>⚠️ 商店那套模型<b>只有星联动力联盟用</b>（老板 2026-10-16 定：三个等级共用一套模型），
 * 其余公司的商店继续用原版方块模型 —— 所以判断要带上公司，不能只看 {@code kind}。</p>
 */
public class MachineGeoModel extends GeoModel<MachineBlockEntity> {

    private static final String[] COMPANY_IDS = {"threshold", "whisperbelt", "brokenedge",
            "skimmer", "bedrock", "astralink"};

    /**
     * 这台机器用哪个几何文件。
     *
     * @return 模型名；<b>没有自定义模型的返回 null</b> → 该变体由原版方块模型渲染
     */
    public static String geoName(MachineBlockEntity be) {
        if (be == null) {
            return null;
        }
        return switch (be.kind()) {
            case BUYER -> "buyer";
            case CORE -> "factory_core";
            // 模块打印机先用普通方块模型，不走 GeckoLib
            case MODULE_PRINTER -> null;
            // 星联的商店（三个等级共用一套模型）
            case TERMINAL -> Company.ASTRALINK == be.company() ? "terminal_astralink" : null;
            default -> null;
        };
    }

    /** 这台机器是不是走 GeckoLib 渲染 */
    public static boolean usesGeo(MachineBlockEntity be) {
        return geoName(be) != null;
    }

    /**
     * 贴图文件名（不含 .png）。
     *
     * <p>收购机/打印机是「一个模型 + 六家公司各一张贴图」；
     * 商店那套是<b>独占贴图</b>（只有星联一家用，所以不带公司后缀）。</p>
     */
    public static String textureName(MachineBlockEntity be) {
        String geo = geoName(be);
        if (geo == null) {
            return null;
        }
        // 收购机不归公司：3 个等级各一张中立贴图（256x256，和 geo 的 UV 布局一致）
        if (be.kind() == MachineKind.BUYER) {
            return "buyer_neutral_" + be.tier().id();
        }
        return geo.startsWith("terminal_") ? geo : geo + "_" + companyId(be);
    }

    private static String companyId(MachineBlockEntity be) {
        Company company = be.company();
        return COMPANY_IDS[Math.max(0, Math.min(COMPANY_IDS.length - 1, company.ordinal()))];
    }

    @Override
    public ResourceLocation getModelResource(MachineBlockEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(DeepSpaceDeployer.MOD_ID,
                "geo/block/" + geoName(animatable) + ".geo.json");
    }

    @Override
    public ResourceLocation getAnimationResource(MachineBlockEntity animatable) {
        // 载具打印机是静止模型（没有动画文件也没关系，它的控制器不播任何东西）；
        // 商店那套有「展开 / 闭合」两个动画。
        return ResourceLocation.fromNamespaceAndPath(DeepSpaceDeployer.MOD_ID,
                "animations/block/" + geoName(animatable) + ".animation.json");
    }

    @Override
    public ResourceLocation getTextureResource(MachineBlockEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(DeepSpaceDeployer.MOD_ID,
                "textures/block/" + textureName(animatable) + ".png");
    }
}
