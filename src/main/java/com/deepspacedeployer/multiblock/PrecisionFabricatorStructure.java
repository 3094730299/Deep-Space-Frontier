package com.deepspacedeployer.multiblock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;

import java.util.Locale;

/**
 * 「精构仪」的多方块几何映射（1 × 1 × 5）。
 *
 * <p>老板定稿：精构仪在世界里占 <b>1 格高 × 5 格长</b>，共 5 格 ——
 * 正中间 1 格是<b>主体</b>（{@code PrecisionFabricatorBlock}，带方块实体、负责画整个模型），
 * 左右各 2 格是<b>部件格</b>（{@code PrecisionFabricatorPartBlock}，隐形、只负责占位与碰撞）。</p>
 *
 * <p>这 5 格是一条直线，横向跟着主体朝向转：面朝北时沿 X 轴铺开，面朝东时沿 Z 轴铺开。
 * 模型本身横跨 5 格（geo 里 X = -40.1 ~ 40.3 单位，约 ±2.5 格），主体正好在正中间，
 * 所以<b>模型不用平移</b> —— 碰撞箱与模型天然重合。</p>
 *
 * <p>写法参考 {@link PrinterStructure}（载具打印机那套 12 格），
 * 区别只在于这里是「一」字排开、没有层。</p>
 */
public final class PrecisionFabricatorStructure {

    /** 部件格数量：主体左右各 2 格 */
    public static final int PARTS = 4;

    /** 整台机器占几格（1 主体 + 4 部件） */
    public static final int LENGTH = 1 + PARTS;

    /**
     * 朝向的「右侧」方向 —— 左右各 2 格就是沿着它铺开的。
     *
     * <p>注意这里的「右」是<b>机器自己的右</b>（面朝北时 = 东）。
     * 玩家站在机器正面看时，机械的右就是玩家的左 —— 整台机器左右对称，
     * 所以这个区别只影响状态名，模型和碰撞完全一样。</p>
     */
    public static Direction rightOf(Direction facing) {
        return facing.getClockWise();
    }

    /**
     * 第 {@code part} 号部件格相对<b>主体</b>的偏移（Y 恒为 0）。
     *
     * @param part   0 .. {@link #PARTS}-1，顺序与 {@link PartSlot} 的枚举顺序一致
     * @param facing 整台机器的朝向
     */
    public static BlockPos offset(int part, Direction facing) {
        Direction right = rightOf(facing);
        int distance = PartSlot.values()[part].rightOffset();
        return new BlockPos(right.getStepX() * distance, 0, right.getStepZ() * distance);
    }

    /**
     * 部件格在结构里的位置（主方块状态里记的就是这个）。
     *
     * <p>{@code LEFT_2} = 机器左侧第 2 格（离主体 2 格），{@code RIGHT_1} = 右侧第 1 格。</p>
     */
    public enum PartSlot implements StringRepresentable {
        LEFT_2(-2),
        LEFT_1(-1),
        RIGHT_1(1),
        RIGHT_2(2);

        /** 沿「右侧」方向相对主体的格数（负数是左侧） */
        private final int rightOffset;

        PartSlot(int rightOffset) {
            this.rightOffset = rightOffset;
        }

        public int rightOffset() {
            return rightOffset;
        }

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private PrecisionFabricatorStructure() {
    }
}
