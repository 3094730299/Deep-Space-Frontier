package com.deepspacedeployer.fluid;

import com.deepspacedeployer.DeepSpaceDeployer;
import com.deepspacedeployer.registry.ModBlocks;
import com.deepspacedeployer.registry.ModItems;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * 高腐蚀溶液：流体类型、源/流动两个流体、桶、流体方块。
 *
 * <p>这是本模组的<b>第一个流体</b>，所以三件事要一起注册：</p>
 * <ol>
 *   <li>{@code FLUID_TYPES}：{@link FluidType}（NeoForge 自己的注册表）；</li>
 *   <li>{@code FLUIDS}：<b>源</b>和<b>流动</b>两个 {@link Fluid}（两个注册名：本名 + {@code flowing_}）；</li>
 *   <li>方块在 {@link ModBlocks}、桶物品在 {@link ModItems}，这里用 Supplier 反指，不提前 .get()。</li>
 * </ol>
 *
 * <p><b>流动性夹在水和岩浆之间</b>：滑距 3（水 4 / 岩浆 2）、每格掉 2 级（水 1 / 岩浆 2）、
 * tick 周期 10（水 5 / 岩浆 30）。</p>
 */
public final class ModFluids {

    public static final String NAME = "corrosive_solution";

    /** 流体类型：比水稠、比岩浆稀；有微光（贴图有光斑，静态光源也给一点） */
    public static final DeferredRegister<FluidType> FLUID_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.FLUID_TYPES, DeepSpaceDeployer.MOD_ID);

    public static final DeferredRegister<Fluid> FLUIDS =
            DeferredRegister.create(Registries.FLUID, DeepSpaceDeployer.MOD_ID);

    public static final DeferredHolder<FluidType, FluidType> CORROSIVE_TYPE = FLUID_TYPES.register(NAME, () ->
            new FluidType(FluidType.Properties.create()
                    .descriptionId("fluid_type." + DeepSpaceDeployer.MOD_ID + "." + NAME)
                    .density(2000)          // 水 1000，岩浆 3000 → 中间
                    .viscosity(3000)        // 水 1000，岩浆 6000 → 中间
                    .temperature(300)       // 常温偏凉，不是岩浆
                    .motionScale(0.006D)    // 水 0.014，岩浆 0.002 → 中间（游/走变慢）
                    .canConvertToSource(false)
                    .canDrown(false)
                    .canSwim(true)
                    .canPushEntity(true)
                    .canExtinguish(true)
                    .supportsBoating(false)
                    .lightLevel(4)          // 贴图有光斑，给一点自发光
                    .rarity(net.minecraft.world.item.Rarity.COMMON)));

    /** 源流体 / 流动流体（注册名必须是 "xxx" 与 "flowing_xxx" 的约定对） */
    public static final DeferredHolder<Fluid, BaseFlowingFluid.Source> CORROSIVE_STILL =
            FLUIDS.register(NAME, () -> new BaseFlowingFluid.Source(properties()));

    public static final DeferredHolder<Fluid, BaseFlowingFluid.Flowing> CORROSIVE_FLOWING =
            FLUIDS.register("flowing_" + NAME, () -> new BaseFlowingFluid.Flowing(properties()));

    /**
     * 流水参数（懒初始化：三个字段互相引用，提前建会造成"非法前向引用"）。
     *
     * <p>流动性 <b>夹在水和岩浆之间</b>：滑距 3（水 4 / 岩浆 2）、每格掉 2 级（水 1 / 岩浆 2）、
     * tick 周期 10（水 5 / 岩浆 30）。</p>
     */
    public static BaseFlowingFluid.Properties properties() {
        if (PROPERTIES == null) {
            PROPERTIES = new BaseFlowingFluid.Properties(CORROSIVE_TYPE, CORROSIVE_STILL, CORROSIVE_FLOWING)
                    .block(() -> ModBlocks.CORROSIVE_SOLUTION.get())
                    .bucket(() -> ModItems.CORROSIVE_BUCKET.get())
                    .slopeFindDistance(3)
                    .levelDecreasePerBlock(2)
                    .tickRate(10)
                    .explosionResistance(100.0F);
        }
        return PROPERTIES;
    }

    private static BaseFlowingFluid.Properties PROPERTIES;

    public static void register(IEventBus modEventBus) {
        FLUID_TYPES.register(modEventBus);
        FLUIDS.register(modEventBus);
    }

    private ModFluids() {
    }
}
