package com.miaokatze.gtswn.common.quantum;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import appeng.me.helpers.AENetworkProxy;
import appeng.me.helpers.IGridProxyable;
import appeng.tile.networking.TileController;

/**
 * 锚点可达性纯判定（O2-B08：自 {@code TileEntityNetworkQuantumNode.tryConnect} 与
 * {@code QuantumNetworkData.assemble} 收敛的同构判定序列，双点共用单源）。
 *
 * <p>
 * 判定顺序（tryConnect 原口径）：量子化（可选）→ 锚点区块已加载 → 锚点是 ME 控制器 →
 * 锚点 proxy 就绪（可选）。注册表查询仅读 WorldSavedData 坐标集合，不触发区块加载，
 * 故可在区块校验前执行；区块校验用 {@code blockExists} 同样不触发加载——
 * 节点 tick 不得把锚点区块常加载造成级联加载。
 * </p>
 *
 * <p>
 * NO_ANCHOR（节点实例状态）与维度解析（tryConnect 用 {@code DimensionManager.getWorld}、
 * assemble 用 {@code worldServerForDimension}，两调用点口径各自保留）不进本类：
 * 本类只做「已取得锚点 world」之后的纯判定。
 * </p>
 */
public final class AnchorReachability {

    /** 可达性判定结果（按判定顺序排列；离线原因映射由调用方完成） */
    public enum Status {

        /** 锚点控制器未处于量子化状态（仅 requireQuantized=true 的调用方产生） */
        NOT_QUANTIZED,

        /** 锚点区块未加载（不主动加载，待自然加载后由下一轮维护重试） */
        CHUNK_UNLOADED,

        /** 锚点位置已不是 ME 控制器（D7：锚点被拆 → 离线保留绑定，可经终端改绑后重放节点） */
        NOT_CONTROLLER,

        /** 锚点控制器 proxy 未就绪（仅 requireProxyReady=true 的调用方产生；区块刚加载尚未首 tick 的瞬时态） */
        PROXY_NOT_READY,

        /** 全部判定通过（{@link Result#anchorTE} 携带锚点控制器 TileEntity） */
        OK
    }

    /** 判定结果值对象：status 为 {@link Status#OK} 时 anchorTE 为锚点控制器，其余为 null */
    public static final class Result {

        public final Status status;

        /**
         * 锚点控制器 TileEntity（仅 OK 时非 null）。
         * 静态类型保持 TileEntity：调用方经 {@code IGridProxyable} 接口取 proxy，
         * 直接 cast TileController 会触发 javac 解析 AEPowerTile 上挂的
         * Mekanism/CoFH/RotaryCraft 可选接口（不在编译 classpath，报「无法访问」）。
         */
        public final TileEntity anchorTE;

        private Result(Status status, TileEntity anchorTE) {
            this.status = status;
            this.anchorTE = anchorTE;
        }
    }

    private AnchorReachability() {}

    /**
     * 判定锚点控制器可达性（纯函数）。
     *
     * @param anchorWorld       锚点所在维度世界（调用方已解析，非 null）
     * @param x                 锚点 X 坐标
     * @param y                 锚点 Y 坐标
     * @param z                 锚点 Z 坐标
     * @param requireQuantized  true 时先查注册表量子化状态（节点建连路径）；
     *                          false 跳过（终端装配原实现不查量子化，口径保持）
     * @param requireProxyReady true 时加查锚点 proxy 非空且就绪（节点建连路径）；
     *                          false 跳过（终端装配由 getGrid 的 GridAccessException 兜底，口径保持）
     * @return 判定结果（OK 时携带锚点控制器 TileEntity）
     */
    public static Result resolve(World anchorWorld, int x, int y, int z, boolean requireQuantized,
        boolean requireProxyReady) {
        // 注册表查询仅读 WorldSavedData 坐标集合，不触发区块加载，可在区块校验前执行
        if (requireQuantized && !QuantumControllerRegistry.get(anchorWorld)
            .isQuantized(x, y, z)) {
            return new Result(Status.NOT_QUANTIZED, null);
        }
        // 锚点区块未加载：blockExists 不触发区块加载，避免节点 tick 把锚点区块常加载造成级联加载
        if (!anchorWorld.blockExists(x, y, z)) {
            return new Result(Status.CHUNK_UNLOADED, null);
        }
        TileEntity te = anchorWorld.getTileEntity(x, y, z);
        if (!(te instanceof TileController)) {
            return new Result(Status.NOT_CONTROLLER, null);
        }
        if (requireProxyReady) {
            // 经 IGridProxyable 接口调用 getProxy()：源表达式必须是 TileEntity 而非 TileController——
            // 后者 cast 会触发 javac 解析 AEPowerTile 上挂的 Mekanism/CoFH/RotaryCraft 可选接口
            // （不在编译 classpath，报「无法访问」），而 TileEntity 的层次是干净的
            AENetworkProxy proxy = ((IGridProxyable) te).getProxy();
            if (proxy == null || !proxy.isReady()) {
                return new Result(Status.PROXY_NOT_READY, null);
            }
        }
        return new Result(Status.OK, te);
    }
}
