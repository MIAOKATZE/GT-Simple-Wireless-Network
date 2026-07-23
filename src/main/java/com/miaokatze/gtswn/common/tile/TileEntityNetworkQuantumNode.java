package com.miaokatze.gtswn.common.tile;

import java.util.EnumSet;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.quantum.QuantumControllerRegistry;
import com.miaokatze.gtswn.config.Config;

import appeng.api.AEApi;
import appeng.api.exceptions.ExistingConnectionException;
import appeng.api.exceptions.FailedConnection;
import appeng.api.exceptions.SecurityConnectionException;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.api.util.AECableType;
import appeng.api.util.DimensionalCoord;
import appeng.me.helpers.AENetworkProxy;
import appeng.me.helpers.IGridProxyable;
import appeng.tile.networking.TileController;

/**
 * ME 网络量子节点 TileEntity（T4：AE 网络桥接核心，规划 plan_20260722152445.md §3/§5.2/§9）。
 * <p>
 * 本质是一条「无频道上限的 ME 线缆」：持有 {@link AENetworkProxy}（DENSE_CAPACITY = 32 频道容量，
 * AE2 单连接上限，即规划定的最高形态），通过 {@code AEApi.createGridConnection} 向锚点控制器的
 * GridNode 建立一条无方向的桥接连接（已核实：GridConnection 构造不校验方向位/邻接，仅查自重、
 * 安全、重复——见 appeng/core/Api.java:109 与 appeng/me/GridConnection.java:204-246），把相邻设备
 * 以普通 AE 邻接连接（每连接 ≤32 频道）接入锚点所属 ME 网络。
 * <p>
 * 生命周期严格仿本项目 {@link TileEntityNetworkInfoPanel}：proxy 懒加载构造、
 * validate/invalidate/onChunkUnload/updateEntity 接入 proxy 生命周期、NBT 键名 "proxy" 一致。
 * 桥接连接为运行时字段不持久化，每 20 tick（含就绪后首轮立即一次）执行一次连接维护：
 * 连接存活则跳过，否则按 D6（不跨维度）/D7（锚点破坏离线）规则尝试重建；
 * invalidate/onChunkUnload 先显式 destroy 桥接连接再走 proxy 生命周期，防止网格残留幽灵节点。
 */
public class TileEntityNetworkQuantumNode extends TileEntity implements IGridProxyable {

    // ==================== NBT 键名 ====================

    /** NBT 键名：锚点控制器维度 */
    private static final String NBT_ANCHOR_DIM = "anchorDim";

    /** NBT 键名：锚点控制器坐标 */
    private static final String NBT_ANCHOR_X = "anchorX";
    private static final String NBT_ANCHOR_Y = "anchorY";
    private static final String NBT_ANCHOR_Z = "anchorZ";

    /** 连接维护间隔（tick）：20t = 1 秒，与量子化事件处理器巡检同节奏 */
    private static final long MAINTENANCE_INTERVAL_TICKS = 20L;

    // ==================== 锚点字段（T3 已有，NBT 持久化） ====================

    /** 锚点控制器维度 ID（未设置时为 Integer.MIN_VALUE，见 {@link #hasAnchor()}） */
    private int anchorDim = Integer.MIN_VALUE;

    /** 锚点控制器坐标 */
    private int anchorX;
    private int anchorY;
    private int anchorZ;

    // ==================== AE2 网络代理（仿 TileEntityNetworkInfoPanel 生命周期） ====================

    /** AE2 网络代理，懒加载，首次调用 getProxy() 时初始化 */
    private AENetworkProxy gridProxy = null;

    /** 标记 proxy 是否已就绪（onReady 已调用） */
    private boolean aeProxyReady = false;

    /** 区块加载时 worldObj 可能尚未设置，暂存 proxy NBT 父标签，待世界可用后再恢复 */
    private NBTTagCompound pendingProxyNBT = null;

    // ==================== 桥接运行时状态（不持久化） ====================

    /** 当前桥接连接（运行时缓存；AE2 的连接本就是运行时对象，重启后靠维护循环重建） */
    private IGridConnection connection = null;

    /** 建连成功时的锚点快照：锚点被改指（setAnchor 再次调用）后旧连接不再有效，需销毁重建 */
    private int connectedAnchorDim = Integer.MIN_VALUE;
    private int connectedAnchorX;
    private int connectedAnchorY;
    private int connectedAnchorZ;

    /** 当前离线原因（运行时缓存，供状态查询与右键提示；在线时为 NONE） */
    private OfflineReason offlineReason = OfflineReason.NO_ANCHOR;

    /** 上次连接维护的世界 tick；-1 = 尚未维护（就绪后首轮 updateEntity 立即执行一次） */
    private long lastMaintenanceTick = -1L;

    // ==================== 离线原因枚举 ====================

    /**
     * 节点离线原因（规划 §9 风险行对应：无锚点/跨维度 D6/锚点不可达 D7/无权限/网络未就绪）。
     */
    public enum OfflineReason {
        /** 在线（非离线） */
        NONE,
        /** 无锚点（未经量子终端放置，或锚点数据缺失） */
        NO_ANCHOR,
        /** 跨维度（D6：v1 不支持跨维度桥接） */
        CROSS_DIMENSION,
        /** 锚点不可达（锚点区块未加载，或锚点位置已不是 ME 控制器） */
        ANCHOR_UNREACHABLE,
        /** 无权限（网络有安全终端且放置者无权限，createGridConnection 抛 SecurityConnectionException） */
        NO_PERMISSION,
        /** 网络未就绪（锚点控制器 proxy 未 ready / GridNode 未创建 / 其他建连失败，瞬时可重试） */
        NETWORK_NOT_READY,
        /** 锚点控制器未处于量子化状态（v1.6.1 问题 5：取消量子化后桥接应断开并停连） */
        ANCHOR_NOT_QUANTIZED
    }

    // ==================== 锚点写入（量子终端放置节点时调用） ====================

    /**
     * 设置锚点控制器坐标（量子终端放置节点时写入）。
     *
     * @param dim 锚点控制器所在维度 ID
     * @param x   锚点控制器 X 坐标
     * @param y   锚点控制器 Y 坐标
     * @param z   锚点控制器 Z 坐标
     */
    public void setAnchor(int dim, int x, int y, int z) {
        this.anchorDim = dim;
        this.anchorX = x;
        this.anchorY = y;
        this.anchorZ = z;
        // 标记 TE 数据已修改，确保锚点写入随区块保存落盘
        markDirty();
    }

    /**
     * 记录放置者身份（量子终端放置节点时调用）。
     * <p>
     * 对应 AE2 标准放置路径 {@code AEBaseItemBlock} 中的
     * {@code ((IGridProxyable) tile).getProxy().setOwner(player)}：本节点由终端经
     * {@code world.setBlock} 放置、绕过了 ItemBlock 放置路径，必须手动补上 owner，
     * 否则节点 GridNode 的 playerID 恒为默认值 -1——在带安全终端的网络上
     * {@code Platform.securityCheck} 会因 -1 无任何权限而恒抛 SecurityConnectionException，
     * 连合法网络主人自己的网络也桥接不上。设置后：放置者有权限则正常桥接，
     * 放置者无权限（≠ 网络 owner）则按规划 §9 风险行捕获安全异常并置离线（NO_PERMISSION）。
     * owner 在 proxy 节点创建时被冲刷进 GridNode.playerID 并随 "proxy" NBT 持久化。
     */
    public void setPlacer(EntityPlayer player) {
        if (player == null || worldObj == null || worldObj.isRemote) {
            return;
        }
        getProxy().setOwner(player);
        markDirty();
    }

    /** 是否已设置锚点（放置时未写入锚点的节点视为无锚，桥接保持离线） */
    public boolean hasAnchor() {
        return this.anchorDim != Integer.MIN_VALUE;
    }

    /** 锚点控制器维度 ID */
    public int getAnchorDim() {
        return this.anchorDim;
    }

    /** 锚点控制器 X 坐标 */
    public int getAnchorX() {
        return this.anchorX;
    }

    /** 锚点控制器 Y 坐标 */
    public int getAnchorY() {
        return this.anchorY;
    }

    /** 锚点控制器 Z 坐标 */
    public int getAnchorZ() {
        return this.anchorZ;
    }

    // ==================== 状态查询（供 T5 统计与方块右键状态显示） ====================

    /**
     * 桥接连接是否存活（节点已接入锚点所属 ME 网络）。
     * <p>
     * 判活依据：连接被任一端销毁后会同时从两端 GridNode.connections 移除
     * （GridConnection.destroy() → sideA/sideB.removeConnection；
     * 锚点控制器失效时其 GridNode.destroy() 会连带销毁全部连接），
     * 故「本节点连接列表仍含该连接」是最稳的存活判据（IGridConnection 接口本身无 isValid 类方法）。
     */
    public boolean isLinked() {
        if (worldObj == null || worldObj.isRemote || this.connection == null || this.gridProxy == null) {
            return false;
        }
        IGridNode node = this.gridProxy.getNode();
        return node != null && node.getConnections()
            .contains(this.connection);
    }

    /** 当前离线原因（在线时为 {@link OfflineReason#NONE}），供 T5 统计使用 */
    public OfflineReason getOfflineReason() {
        return this.offlineReason;
    }

    /**
     * 当前状态对应的 lang 键（在线返回在线提示键，离线返回原因提示键）。
     * <p>
     * 键位映射（尽量复用已有键，T4 仅新增 node_online 与 node_offline_crossdim 两键）：
     * NONE → gtswn.chat.quantum.node_online（新增）；
     * CROSS_DIMENSION → gtswn.chat.quantum.node_offline_crossdim（新增）；
     * NO_PERMISSION → gtswn.chat.quantum.no_permission（已有）；
     * ANCHOR_NOT_QUANTIZED → gtswn.chat.quantum.node_offline_not_quantized（v1.6.1 新增，lang 由任务 B 补）；
     * 其余（无锚点/锚点不可达/网络未就绪）→ gtswn.chat.quantum.node_offline（已有）。
     */
    public String getOfflineReasonKey() {
        switch (this.offlineReason) {
            case NONE:
                return "gtswn.chat.quantum.node_online";
            case CROSS_DIMENSION:
                return "gtswn.chat.quantum.node_offline_crossdim";
            case NO_PERMISSION:
                return "gtswn.chat.quantum.no_permission";
            case ANCHOR_NOT_QUANTIZED:
                return "gtswn.chat.quantum.node_offline_not_quantized";
            case NO_ANCHOR:
            case ANCHOR_UNREACHABLE:
            case NETWORK_NOT_READY:
            default:
                return "gtswn.chat.quantum.node_offline";
        }
    }

    // ==================== IGridProxyable 接口实现（仿 TileEntityNetworkInfoPanel） ====================

    @Override
    public AENetworkProxy getProxy() {
        if (gridProxy == null && !worldObj.isRemote) {
            // 构造参数照抄信息屏样板：(IGridProxyable, nbtName="proxy", 视觉物品=null, inWorld=true)
            gridProxy = new AENetworkProxy(this, "proxy", null, true);
            // DENSE_CAPACITY = 32 频道容量（AE2 单连接上限，即规划定的「无频道上限」最高形态），
            // 与控制器 proxy 的 DENSE_CAPACITY 对齐，桥接连接即可满载 32 频道
            gridProxy.setFlags(GridFlags.DENSE_CAPACITY);
            // 全方向可邻接：相邻设备/线缆可普通邻接接入本节点
            gridProxy.setValidSides(EnumSet.allOf(ForgeDirection.class));
            // D5 → v1.6.1 问题 7：闲置功耗改读配置（默认 10 AE/t；v1.6.0 硬编码 16）
            gridProxy.setIdlePowerUsage(Config.quantumNodeIdlePowerUsage);
        }
        return gridProxy;
    }

    @Override
    public DimensionalCoord getLocation() {
        return new DimensionalCoord(worldObj, xCoord, yCoord, zCoord);
    }

    @Override
    public void gridChanged() {
        // AE2 网络连接变化回调，不做复杂操作
    }

    @Override
    public IGridNode getGridNode(ForgeDirection dir) {
        if (worldObj == null || worldObj.isRemote) return null;
        return getProxy().getNode();
    }

    @Override
    public AECableType getCableConnectionType(ForgeDirection dir) {
        // 渲染用类型（规划 §1 已核实：该方法只影响相邻线缆渲染，不参与连接逻辑）；
        // 与桥接对端 TileController 一致返回 DENSE（其为 DENSE_CAPACITY 密集节点），
        // 相邻线缆按密集线缆样式渲染，直观表达 32 频道容量
        return AECableType.DENSE;
    }

    @Override
    public void securityBreak() {
        // AE2 安全系统回调，不做破坏
    }

    // ==================== AE2 网络节点生命周期（仿 TileEntityNetworkInfoPanel） ====================

    @Override
    public void validate() {
        super.validate();
        if (gridProxy != null) {
            gridProxy.validate();
        }
    }

    @Override
    public void invalidate() {
        // 断桥接连接必须先于 proxy 生命周期：显式 destroy 防止网格残留幽灵节点
        destroyBridgeConnection();
        super.invalidate();
        if (gridProxy != null) {
            gridProxy.invalidate();
        }
        // 防御性重置：同一 TE 实例被 re-validate 时可重新走 onReady 重建节点
        this.aeProxyReady = false;
    }

    @Override
    public void onChunkUnload() {
        // 同 invalidate：先断桥接连接再走 proxy 生命周期
        destroyBridgeConnection();
        super.onChunkUnload();
        if (gridProxy != null) {
            gridProxy.onChunkUnload();
        }
        this.aeProxyReady = false;
    }

    @Override
    public void updateEntity() {
        super.updateEntity();
        if (worldObj == null || worldObj.isRemote) {
            return;
        }
        // ===== proxy 就绪流程（照样板：暂存 NBT 重放 → onReady 一次性调用） =====
        if (pendingProxyNBT != null) {
            getProxy().readFromNBT(pendingProxyNBT);
            pendingProxyNBT = null;
        }
        if (!aeProxyReady) {
            getProxy().onReady();
            aeProxyReady = true;
        }
        // ===== 桥接连接维护：每 20 tick 一次；lastMaintenanceTick 初值 -1 保证就绪后首轮立即执行 =====
        long tick = worldObj.getTotalWorldTime();
        if (this.lastMaintenanceTick >= 0L && tick - this.lastMaintenanceTick < MAINTENANCE_INTERVAL_TICKS) {
            return;
        }
        this.lastMaintenanceTick = tick;
        maintainConnection();
    }

    // ==================== 桥接逻辑（仅服务端） ====================

    /**
     * 连接维护主循环（每 20 tick 一次）。
     * <ol>
     * <li>连接存活、锚点未改指且锚点仍量子化 → 跳过；</li>
     * <li>连接在但锚点已改指 / 锚点已取消量子化（v1.6.1 问题 5）→ 销毁旧连接后重建（重建时按规则离线）；</li>
     * <li>无连接 → 按 D6/D7 规则尝试建连，失败记录离线原因待下轮重试。</li>
     * </ol>
     */
    private void maintainConnection() {
        if (this.connection != null) {
            if (isLinked() && isAnchorSnapshotMatched() && isAnchorStillQuantized()) {
                // 连接存活、锚点未变且锚点仍量子化：无需维护
                return;
            }
            // 连接已死（对端销毁/本节点重建）或锚点改指或锚点已取消量子化：清理后走重建
            destroyBridgeConnection();
        }
        tryConnect();
    }

    /** 锚点控制器当前是否仍处于量子化状态（同维度 + 已入册） */
    private boolean isAnchorStillQuantized() {
        return this.anchorDim == worldObj.provider.dimensionId && QuantumControllerRegistry.get(worldObj)
            .isQuantized(this.anchorX, this.anchorY, this.anchorZ);
    }

    /** 尝试向锚点控制器建立桥接连接，失败时记录离线原因 */
    private void tryConnect() {
        // 无锚点：未经量子终端放置的节点（如创造模式直接放置）恒离线
        if (!hasAnchor()) {
            this.offlineReason = OfflineReason.NO_ANCHOR;
            return;
        }
        // D6：v1 不支持跨维度桥接
        if (this.anchorDim != worldObj.provider.dimensionId) {
            this.offlineReason = OfflineReason.CROSS_DIMENSION;
            return;
        }
        // v1.6.1 问题 5：锚点控制器未处于量子化状态（已取消量子化）→ 不建连。
        // 注册表查询仅读 WorldSavedData 坐标集合，不触发区块加载，可在区块校验前执行
        if (!QuantumControllerRegistry.get(worldObj)
            .isQuantized(this.anchorX, this.anchorY, this.anchorZ)) {
            this.offlineReason = OfflineReason.ANCHOR_NOT_QUANTIZED;
            return;
        }
        // 锚点区块未加载：blockExists 不触发区块加载（与 TileWirelessBase 重连循环同一手法），
        // 避免节点 tick 把锚点区块常加载造成级联加载
        if (!worldObj.blockExists(this.anchorX, this.anchorY, this.anchorZ)) {
            this.offlineReason = OfflineReason.ANCHOR_UNREACHABLE;
            return;
        }
        // 锚点位置已不是 ME 控制器（D7：锚点被拆 → 离线保留绑定，可经终端改绑后重放节点）
        TileEntity anchorTE = worldObj.getTileEntity(this.anchorX, this.anchorY, this.anchorZ);
        if (!(anchorTE instanceof TileController)) {
            this.offlineReason = OfflineReason.ANCHOR_UNREACHABLE;
            return;
        }
        // 经 IGridProxyable 接口调用 getProxy()：源表达式必须是 TileEntity 而非 TileController——
        // 后者 cast 会触发 javac 解析 AEPowerTile 上挂的 Mekanism/CoFH/RotaryCraft 可选接口
        // （不在编译 classpath，报「无法访问」），而 TileEntity 的层次是干净的
        // （与 QuantumControllerEventHandler.applyConnectionFilter 同一写法）
        AENetworkProxy anchorProxy = ((IGridProxyable) anchorTE).getProxy();
        if (anchorProxy == null || !anchorProxy.isReady()) {
            // 锚点控制器 proxy 未 ready（区块刚加载尚未首 tick）：瞬时状态，下轮重试
            this.offlineReason = OfflineReason.NETWORK_NOT_READY;
            return;
        }
        IGridNode anchorNode = anchorProxy.getNode();
        if (anchorNode == null) {
            this.offlineReason = OfflineReason.NETWORK_NOT_READY;
            return;
        }
        IGridNode myNode = getProxy().getNode();
        if (myNode == null) {
            // 本节点 proxy 未 ready 时 getNode() 返回 null；首轮维护在 onReady 之后执行，此处仅防御
            this.offlineReason = OfflineReason.NETWORK_NOT_READY;
            return;
        }
        try {
            // 已核实：createGridConnection 不校验方向位/邻接，仅查自重/安全/重复
            // （appeng/core/Api.java:109 → GridConnection.java:204-246）
            this.connection = AEApi.instance()
                .createGridConnection(myNode, anchorNode);
            snapshotAnchor();
            this.offlineReason = OfflineReason.NONE;
        } catch (SecurityConnectionException e) {
            // §9 风险行：网络有安全终端且放置者无权限（放置者 ≠ 网络 owner）
            this.connection = null;
            this.offlineReason = OfflineReason.NO_PERMISSION;
        } catch (ExistingConnectionException e) {
            // 两节点间已存在直连（如玩家另拉了线缆/石英纤维以外的部件直接贴上）：
            // 桥接冗余但目标已达成——收养既有直连视为在线，保证 isLinked() 语义正确
            this.connection = findDirectConnection(myNode, anchorNode);
            if (this.connection != null) {
                snapshotAnchor();
                this.offlineReason = OfflineReason.NONE;
            } else {
                this.offlineReason = OfflineReason.NETWORK_NOT_READY;
            }
        } catch (FailedConnection e) {
            // 其余建连失败（FailedConnection 剩余子类如 NullNodeConnectionException）：瞬时处理，下轮重试
            this.connection = null;
            this.offlineReason = OfflineReason.NETWORK_NOT_READY;
        }
    }

    /**
     * 显式销毁桥接连接（invalidate/onChunkUnload/锚点改指时调用）。
     * 判空 + try-catch：连接可能已被对端先行销毁（如锚点控制器先拆），
     * 此时再次 destroy 内部 removeConnection 为空操作但 validateGrid/repath 可能抛异常，吞掉即可。
     */
    private void destroyBridgeConnection() {
        if (this.connection != null) {
            try {
                this.connection.destroy();
            } catch (Exception e) {
                // 连接已被对端销毁或网格已解体：忽略，保证 TE 拆除路径不被打断
            }
            this.connection = null;
        }
        clearAnchorSnapshot();
    }

    /** 查找两节点间的既有直连（用于收养 ExistingConnectionException 场景）；无则返回 null */
    private static IGridConnection findDirectConnection(IGridNode a, IGridNode b) {
        for (IGridConnection c : a.getConnections()) {
            if (c.getOtherSide(a) == b) {
                return c;
            }
        }
        return null;
    }

    /** 建连成功时快照当前锚点（供改指检测） */
    private void snapshotAnchor() {
        this.connectedAnchorDim = this.anchorDim;
        this.connectedAnchorX = this.anchorX;
        this.connectedAnchorY = this.anchorY;
        this.connectedAnchorZ = this.anchorZ;
    }

    /** 当前锚点与建连时快照一致 */
    private boolean isAnchorSnapshotMatched() {
        return this.connectedAnchorDim == this.anchorDim && this.connectedAnchorX == this.anchorX
            && this.connectedAnchorY == this.anchorY
            && this.connectedAnchorZ == this.anchorZ;
    }

    /** 清除锚点快照（连接销毁后置于「未连接」状态） */
    private void clearAnchorSnapshot() {
        this.connectedAnchorDim = Integer.MIN_VALUE;
    }

    // ==================== NBT 持久化（锚点字段保留现有代码；proxy 键名 "proxy" 与样板一致） ====================

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        if (tag.hasKey(NBT_ANCHOR_DIM)) {
            this.anchorDim = tag.getInteger(NBT_ANCHOR_DIM);
            this.anchorX = tag.getInteger(NBT_ANCHOR_X);
            this.anchorY = tag.getInteger(NBT_ANCHOR_Y);
            this.anchorZ = tag.getInteger(NBT_ANCHOR_Z);
        }
        if (tag.hasKey("proxy")) {
            if (worldObj != null && !worldObj.isRemote) {
                getProxy().readFromNBT(tag);
            } else {
                // 区块从磁盘加载时 worldObj 尚未赋值，暂存待首个 updateEntity 重放。
                // 注意暂存的是父标签而非 getCompoundTag("proxy")：
                // AENetworkProxy.readFromNBT 内部按 nbtName="proxy" 自取子 compound
                // （GridNode.loadFromNBT(name, nodeData) → nodeData.getCompoundTag(name)），
                // 传子 compound 会导致 playerID/安全键/GridStorage ID 恢复为空，
                // 重启后在带安全终端的网络上无法通过 securityCheck 重连
                this.pendingProxyNBT = tag;
            }
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        if (hasAnchor()) {
            tag.setInteger(NBT_ANCHOR_DIM, this.anchorDim);
            tag.setInteger(NBT_ANCHOR_X, this.anchorX);
            tag.setInteger(NBT_ANCHOR_Y, this.anchorY);
            tag.setInteger(NBT_ANCHOR_Z, this.anchorZ);
        }
        if (gridProxy != null) {
            gridProxy.writeToNBT(tag);
        }
    }
}
