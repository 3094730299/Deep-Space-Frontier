package com.deepspacedeployer.item;

import com.deepspacedeployer.Tier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;

/**
 * 收购投送机的 3 个等级物品。
 *
 * <p>方块本来只有一个 {@code buyer}（公司属性只作为老存档兼容保留）。
 * 实际放置时由 {@code MachineBlock#getStateForPlacement} 读这个物品的 {@link #tier()}
 * 写进方块状态，所以 /give 出来的物品也能放成正确等级。</p>
 */
public class BuyerBlockItem extends BlockItem {

    private final Tier tier;

    public BuyerBlockItem(Block block, Tier tier, Properties properties) {
        super(block, properties);
        this.tier = tier;
    }

    public Tier tier() {
        return tier;
    }
}
