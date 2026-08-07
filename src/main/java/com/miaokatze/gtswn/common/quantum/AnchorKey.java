package com.miaokatze.gtswn.common.quantum;

/**
 * 锚点坐标值类型（维度 + x/y/z 四字段，全等语义）。
 * <p>
 * 用作统计缓存（{@link QuantumNetworkStatsCache}）、过载倒计时
 * （{@link QuantumOverloadCountdown}）与终端完整快照缓存（{@link QuantumNetworkData}）
 * 的键。不可变值类型：equals / hashCode 按四字段全等，可安全作为 HashMap 键。
 * 节点（{@code TileEntityNetworkQuantumNode}）持有常驻实例按需复用，
 * 避免每 20 tick 每次检查重复分配新键对象。
 */
public final class AnchorKey {

    private final int dimension;
    private final int x;
    private final int y;
    private final int z;

    public AnchorKey(int dimension, int x, int y, int z) {
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** 锚点维度 ID */
    public int getDimension() {
        return this.dimension;
    }

    /** 锚点 X 坐标 */
    public int getX() {
        return this.x;
    }

    /** 锚点 Y 坐标 */
    public int getY() {
        return this.y;
    }

    /** 锚点 Z 坐标 */
    public int getZ() {
        return this.z;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof AnchorKey)) {
            return false;
        }
        AnchorKey other = (AnchorKey) obj;
        return this.dimension == other.dimension && this.x == other.x && this.y == other.y && this.z == other.z;
    }

    @Override
    public int hashCode() {
        int result = this.dimension;
        result = 31 * result + this.x;
        result = 31 * result + this.y;
        result = 31 * result + this.z;
        return result;
    }

    @Override
    public String toString() {
        return "AnchorKey[" + this.dimension + "," + this.x + "," + this.y + "," + this.z + "]";
    }
}
