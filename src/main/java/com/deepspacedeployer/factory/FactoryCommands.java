package com.deepspacedeployer.factory;

import com.deepspacedeployer.diagnostics.SelfTest;
import com.deepspacedeployer.integration.IntegrationProbe;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 指令：
 * <ul>
 *   <li>{@code /dsd guide [主题]} —— 游戏内快速上手（任何玩家都能用；主题：card/sub/buy/factory/weld）</li>
 *   <li>{@code /dsd reloadblueprints} —— 重新读蓝图文件夹（OP）</li>
 *   <li>{@code /dsd integration} —— 打印联动探测报告（哪些模组/方法可用）</li>
 *   <li>{@code /dsd selftest} —— 一键自检</li>
 * </ul>
 */
public final class FactoryCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("dsd")
                .then(Commands.literal("guide")
                        .executes(context -> guide(context.getSource(), "start"))
                        .then(Commands.literal("card")
                                .executes(context -> guide(context.getSource(), "card")))
                        .then(Commands.literal("buy")
                                .executes(context -> guide(context.getSource(), "buy")))
                        .then(Commands.literal("factory")
                                .executes(context -> guide(context.getSource(), "factory")))
                        .then(Commands.literal("weld")
                                .executes(context -> guide(context.getSource(), "weld"))))
                .then(Commands.literal("reloadblueprints")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> {
                            int count = PhysicsBlueprintLibrary.refresh(context.getSource().getServer());
                            int modules = ModulePresetLibrary.reload(context.getSource().getServer());
                            context.getSource().sendSuccess(
                                    () -> Component.translatable("cmd.deepspacedeployer.reloadblueprints", count),
                                    true);
                            context.getSource().sendSuccess(
                                    () -> Component.translatable("cmd.deepspacedeployer.reloadmodules", modules),
                                    true);
                            return count + modules;
                        }))
                .then(Commands.literal("integration")
                        .executes(context -> {
                            List<String> lines = IntegrationProbe.describe();
                            for (String line : lines) {
                                context.getSource().sendSuccess(() -> Component.literal(line), false);
                            }
                            return lines.size();
                        }))
                .then(Commands.literal("corrosion")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> {
                            List<String> lines =
                                    com.deepspacedeployer.corrosion.CorrosiveErosion.diagnose(context.getSource().getServer());
                            for (String line : lines) {
                                context.getSource().sendSuccess(() -> Component.literal(line), false);
                                com.deepspacedeployer.DeepSpaceDeployer.LOGGER.info("[腐蚀诊断] {}", line);
                            }
                            return lines.size();
                        }))
                .then(Commands.literal("selftest")
                        .executes(context -> {
                            List<String> lines = SelfTest.run(context.getSource().getServer());
                            for (String line : lines) {
                                context.getSource().sendSuccess(() -> Component.literal(line), false);
                                com.deepspacedeployer.DeepSpaceDeployer.LOGGER.info("[自检] {}", line);
                            }
                            return lines.size();
                        })));
    }

    /** 游戏内快速上手：一次印几行，任何人可用 */
    private static int guide(CommandSourceStack source, String topic) {
        String[] keys = switch (topic) {
            case "card" -> new String[]{
                    "guide.deepspacedeployer.card_1",
                    "guide.deepspacedeployer.card_2",
                    "guide.deepspacedeployer.card_3"};
            // 副卡（余额独立的那张）：老板加的，单独一个主题
            case "sub" -> new String[]{
                    "guide.deepspacedeployer.sub_1",
                    "guide.deepspacedeployer.sub_2",
                    "guide.deepspacedeployer.sub_3",
                    "guide.deepspacedeployer.sub_4"};
            case "buy" -> new String[]{
                    "guide.deepspacedeployer.buy_1",
                    "guide.deepspacedeployer.buy_2",
                    "guide.deepspacedeployer.buy_3"};
            case "factory" -> new String[]{
                    "guide.deepspacedeployer.factory_1",
                    "guide.deepspacedeployer.factory_2",
                    "guide.deepspacedeployer.factory_3",
                    "guide.deepspacedeployer.factory_4"};
            // 焊接台已经删除；老习惯敲 "weld" 的人给一句明确提示 + 指向工厂那套说明
            case "weld" -> new String[]{
                    "guide.deepspacedeployer.weld_1",
                    "guide.deepspacedeployer.weld_2"};
            default -> new String[]{
                    "guide.deepspacedeployer.start_1",
                    "guide.deepspacedeployer.start_2",
                    "guide.deepspacedeployer.start_3",
                    "guide.deepspacedeployer.start_4",
                    "guide.deepspacedeployer.start_5"};
        };
        for (String key : keys) {
            source.sendSuccess(() -> Component.translatable(key), false);
        }
        return keys.length;
    }

    private FactoryCommands() {
    }
}
