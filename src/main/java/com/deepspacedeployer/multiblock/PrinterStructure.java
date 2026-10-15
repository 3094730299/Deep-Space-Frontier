package com.deepspacedeployer.multiblock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 「载具打印机」的多方块几何映射。
 *
 * <p>老板定稿：载具打印机在世界里占 <b>长 3 × 宽 2 × 高 2</b> 格（共 12 格）。
 * 模型尺寸 X -24..24（3 格）、Z -16.35..16.35（2 格）、Y 0..32.6（2 格），正好吻合。</p>
 *
 * <p>把 12 个格子归成 <b>6 列 × 2 层</b>：先在水平面找 6 个"列"，
 * 每列再从下往上 2 格。水平偏移按朝向旋转，所以整台机器摆在哪、朝哪都对得上。</p>
 *
 * <p><b>朝向约定</b>（和模型一致）：脚位在朝向的<b>右侧</b>。
 * 例如 {@code FACING = NORTH} 时，机器人位于北侧小格，
 * 装甲车位于东北、西北两个格子，朝东移动。</p>
 */
public final class PrinterStructure {

    /** 水平方向的列数（3 × 2 = 6 列） */
    public static final int COLUMNS = 6;
    /** 每列的高度（格） */
    public static final int HEIGHT = 2;
    /** 总共占多少格 */
    public static final int PARTS = COLUMNS * HEIGHT;

    /**
     * 水平偏移（未旋转，朝向 = NORTH 时）：X 3 格、Z 2 格。
     *
     * <p>⚠️ <b>结构不要挪</b>：挪的是"模型"（见 factory_core.geo.json 的平移），
     * 不是这 12 格结构。结构挪了会让碰撞箱跟着挪，老板明确要求
     * "以碰撞箱为准、把模型对齐过去"。</p>
     */
    private static final int[] OFF_X = {0, 1, 2, 0, 1, 2};
    private static final int[] OFF_Z = {-1, -1, -1, 0, 0, 0};

    /** 朝向的"右侧"方向（用于旋转水平偏移） */
    private static Direction rightOf(Direction facing) {
        return facing.getCounterClockWise();
    }

    /**
     * 第 {@code part} 号格子相对主方块（控制器）的偏移。
     *
     * @param part   0 .. {@link #PARTS}-1（前 6 个是底层，后 6 个是对应列的顶层）
     * @param facing 整台机器的朝向
     */
    public static BlockPos offset(int part, Direction facing) {
        int col = part % COLUMNS;
        int layer = part / COLUMNS;
        int ox = OFF_X[col];
        int oz = OFF_Z[col];

        // 朝向的"前" = facing，"右" = 右侧
        Direction front = facing;
        Direction right = rightOf(facing);

        // 未旋转时：X 沿"右"、Z 沿"前"
        int dx = right.getStepX() * ox + front.getStepX() * oz;
        int dz = right.getStepZ() * ox + front.getStepZ() * oz;
        return new BlockPos(dx, layer, dz);
    }

    /**
     * 从某个格子的世界坐标反推它相对主方块的偏移（不含 Y，Y 直接取世界差）。
     */
    public static BlockPos relativeTo(BlockPos part, BlockPos controller) {
        return new BlockPos(part.getX() - controller.getX(),
                part.getY() - controller.getY(),
                part.getZ() - controller.getZ());
    }

    /** 这个偏移是不是 6 列之一（Y 必须是 0 或 1） */
    public static boolean isValidOffset(BlockPos rel, Direction facing) {
        if (rel.getY() < 0 || rel.getY() >= HEIGHT) {
            return false;
        }
        for (int i = 0; i < COLUMNS; i++) {
            BlockPos o = offset(i, facing);
            if (o.getX() == rel.getX() && o.getZ() == rel.getZ()) {
                return true;
            }
        }
        return false;
    }

    private PrinterStructure() {
    }
}
