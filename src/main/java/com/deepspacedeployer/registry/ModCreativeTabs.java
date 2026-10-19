package com.deepspacedeployer.registry;

import com.deepspacedeployer.Company;
import com.deepspacedeployer.DeepSpaceDeployer;
import com.deepspacedeployer.Tier;
import com.deepspacedeployer.blockentity.MachineKind;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, DeepSpaceDeployer.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + DeepSpaceDeployer.MOD_ID + ".main"))
                    .icon(() -> new ItemStack(ModItems.BANK_CARD.get()))
                    .displayItems((parameters, output) -> {
                        // 卡：主卡 / 副卡（老板说"卡保留"）
                        output.accept(ModItems.BANK_CARD.get());
                        output.accept(ModItems.BANK_CARD_SUB.get());
                        output.accept(ModItems.BLUEPRINT_VOUCHER.get());
                        output.accept(ModItems.TIER_UPGRADE_KIT.get());
                        for (Company company : Company.VALUES) {
                            output.accept(ModItems.licenseItem(company));
                        }
                        // 通用终端：纯装饰方块
                        output.accept(ModItems.UNIVERSAL_TERMINAL.get());
                        // 精构仪：装饰方块（老板 2026-10-19 新建模，暂时只当摆设）
                        output.accept(ModItems.PRECISION_FABRICATOR.get());
                        // 功能方块：有归属的机器 6 家公司各一件、无归属的（寄售市场）就一件。
                        // 老板要求"无法切换"，所以这里列出来是什么就是什么。
                        for (MachineKind kind : MachineKind.values()) {
                            if (kind.isCompanyBound()) {
                                for (Company company : Company.VALUES) {
                                    ItemStack stack = ModItems.machineStack(kind, company);
                                    if (!stack.isEmpty()) {
                                        output.accept(stack);
                                    }
                                }
                            } else {
                                ItemStack stack = ModItems.machineStack(kind, Company.VALUES[0]);
                                if (!stack.isEmpty()) {
                                    output.accept(stack);
                                }
                            }
                        }
                    })
                    .build());

    public static void register(IEventBus modEventBus) {
        TABS.register(modEventBus);
    }

    private ModCreativeTabs() {
    }
}
