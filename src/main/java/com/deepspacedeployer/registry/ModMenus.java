package com.deepspacedeployer.registry;

import com.deepspacedeployer.DeepSpaceDeployer;
import com.deepspacedeployer.blockentity.MachineKind;
import com.deepspacedeployer.menu.MachineMenu;
import com.deepspacedeployer.menu.TerminalMenu;
import com.deepspacedeployer.menu.TransferMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 菜单注册表。
 *
 * <p>四种功能方块各注册一个菜单类型——客户端在"打开界面"的那一刻就能知道自己开的是哪种机器，
 * 不用等数据同步，界面布局一次就对。</p>
 *
 * <p>通用终端另有一个 {@code terminal_ui}：6 个卡槽（= 6 家公司）+ 该公司的商店列表。</p>
 */
public final class ModMenus {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, DeepSpaceDeployer.MOD_ID);

    public static final DeferredHolder<MenuType<?>, MenuType<MachineMenu>> TERMINAL = register(MachineKind.TERMINAL);
    public static final DeferredHolder<MenuType<?>, MenuType<MachineMenu>> CORE = register(MachineKind.CORE);
    public static final DeferredHolder<MenuType<?>, MenuType<MachineMenu>> BUYER = register(MachineKind.BUYER);
    public static final DeferredHolder<MenuType<?>, MenuType<MachineMenu>> MARKET = register(MachineKind.MARKET);

    /**
     * 资金转化界面（商店里的「资金转化」按钮打开）。
     *
     * <p>它不是方块自己的菜单，有<b>两张</b>银行卡槽，所以单独注册一个类型；</p>
     */
    public static final DeferredHolder<MenuType<?>, MenuType<TransferMenu>> TRANSFER =
            MENUS.register("transfer", () -> IMenuTypeExtension.create(
                    (windowId, inventory, data) -> new TransferMenu(windowId, inventory)));

    /**
     * 通用终端的界面：6 个卡槽（= 6 家公司）+ 该公司的商店。
     *
     * <p>服务端把方块坐标写进菜单包，客户端据此找到方块实体。</p>
     */
    public static final DeferredHolder<MenuType<?>, MenuType<TerminalMenu>> TERMINAL_UI =
            MENUS.register("terminal_ui", () -> IMenuTypeExtension.create(
                    (windowId, inventory, data) -> new TerminalMenu(windowId, inventory, data)));

    private static DeferredHolder<MenuType<?>, MenuType<MachineMenu>> register(MachineKind kind) {
        return MENUS.register(kind.id(), () -> IMenuTypeExtension.create(
                (windowId, inventory, data) -> MachineMenu.forClient(windowId, inventory, kind)));
    }

    public static MenuType<MachineMenu> get(MachineKind kind) {
        return switch (kind) {
            case TERMINAL -> TERMINAL.get();
            case CORE -> CORE.get();
            case BUYER -> BUYER.get();
            case MARKET -> MARKET.get();
        };
    }

    public static void register(IEventBus modEventBus) {
        MENUS.register(modEventBus);
    }

    private ModMenus() {
    }
}
