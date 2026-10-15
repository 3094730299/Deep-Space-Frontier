package com.deepspacedeployer.item;

/**
 * 标记接口：这些物品是"对着机器右键使用"的（授权铭牌、等级升级套件、蓝图凭证）。
 *
 * <p><b>为什么需要这个接口：</b>原版右键的执行顺序是
 * {@code 方块.useItemOn → （若返回 PASS）方块.useWithoutItem → 物品.useOn}。
 * 而我们的机器方块在 {@code useWithoutItem} 里会**打开界面并消费交互**，
 * 于是手持这些物品右键时只会弹出界面、物品效果永远触发不了。
 * 所以机器方块看到这个接口的实现时，会先返回
 * {@code ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION}（不消费、且跳过 useWithoutItem），
 * 把这次右键让给物品自己处理。</p>
 */
public interface MachineTargetItem {
}
