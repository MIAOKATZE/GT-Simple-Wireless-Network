package com.miaokatze.gtswn.common.tile;

import java.util.EnumSet;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.StatCollector;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.performance.PerformanceAudit;
import com.miaokatze.gtswn.common.quantum.AnchorKey;
import com.miaokatze.gtswn.common.quantum.QuantumControllerRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkStatsCache;
import com.miaokatze.gtswn.common.quantum.QuantumOverloadCountdown;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;
import com.miaokatze.gtswn.register.BlockRegistrar;

import appeng.api.AEApi;
import appeng.api.exceptions.ExistingConnectionException;
import appeng.api.exceptions.FailedConnection;
import appeng.api.exceptions.SecurityConnectionException;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.api.util.AECableType;
import appeng.api.util.DimensionalCoord;
import appeng.core.worlddata.WorldData;
import appeng.me.GridAccessException;
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
 * 连接存活则跳过，否则按自然加载状态和 D7（锚点破坏离线）规则尝试重建；
 * 本节点不主动申请 ForgeChunkManager Ticket，节点与锚点持续工作依赖服务器或其他模组提供的区块加载；
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

    /** 同步 NBT 键名：桥接在线状态（仅 description packet 用，不持久化） */
    private static final String NBT_SYNC_LINKED = "linked";

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

    /** 客户端渲染用在线状态缓存（由 onDataPacket 维护；服务端勿用，服务端以 isLinked() 为权威） */
    private boolean clientLinked = false;

    /** 服务端上次已同步的在线状态（每 tick 比对，变化才 markBlockForUpdate 发包） */
    private boolean lastSyncedLinked = false;

    /** 该节点上次 max usedChannels（-1 = 未初始化；v1.6.8 新增，用于 95% 预警跟踪本节点频道增长） */
    private int lastNodeUsedChannels = -1;

    /** 锚点坐标键常驻实例（checkNetworkOverload 复用，锚点改指时按四字段比对重建；不持久化） */
    private AnchorKey anchorKey = null;

    // ==================== 离线原因枚举 ====================

    /**
     * 节点离线原因（规划 §9 风险行对应：无锚点/跨维度 D6/锚点不可达 D7/无权限/网络未就绪）。
     */
    public enum OfflineReason {
        /** 在线（非离线） */
        NONE,
        /** 无锚点（未经量子终端放置，或锚点数据缺失） */
        NO_ANCHOR,
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

    /** 客户端渲染用在线状态（仅客户端有意义；服务端请用 {@link #isLinked()}） */
    public boolean isLinkedClient() {
        return this.clientLinked;
    }

    /** 服务端每 tick 比对在线状态，变化即 markBlockForUpdate 推送 S35（驱动客户端材质切换） */
    private void syncLinkedStateIfChanged(boolean now) {
        if (now != this.lastSyncedLinked) {
            this.lastSyncedLinked = now;
            // v1.6.19：性能审计——在线状态同步包计数（每变化一次即发包一次）
            PerformanceAudit.recordQuantumSyncPacket();
            worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
        }
    }

    /**
     * 当前状态对应的 lang 键（在线返回在线提示键，离线返回原因提示键）。
     * <p>
     * 键位映射（尽量复用已有键）：
     * NONE → gtswn.chat.quantum.node_online（新增）；
     * NO_PERMISSION → gtswn.chat.quantum.no_permission（已有）；
     * ANCHOR_NOT_QUANTIZED → gtswn.chat.quantum.node_offline_not_quantized（v1.6.1 新增，lang 由任务 B 补）；
     * 其余（无锚点/锚点不可达/网络未就绪）→ gtswn.chat.quantum.node_offline（已有）。
     * <p>
     * v1.6.10：移除 CROSS_DIMENSION 离线原因（已支持跨维度桥接），node_offline_crossdim 键同步删除。
     */
    public String getOfflineReasonKey() {
        switch (this.offlineReason) {
            case NONE:
                return "gtswn.chat.quantum.node_online";
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
            // v1.6.4 任务2：注入视觉代表物品，否则 AE2 网络工具设备枚举（ContainerNetworkStatus）
            // 因 getMachineRepresentation()==null 跳过本节点
            gridProxy.setVisualRepresentation(new ItemStack(BlockRegistrar.networkQuantumNode, 1, 0));
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
        if (worldObj == null || worldObj.isRemote) {
            return null;
        }
        // v1.6.19：量子节点之间互不连接——相邻为量子节点时该方向不暴露节点，
        // AE2 FindConnections 在两侧都会跳过该方向的建连尝试
        if (dir != null) {
            TileEntity neighbor = worldObj
                .getTileEntity(xCoord + dir.offsetX, yCoord + dir.offsetY, zCoord + dir.offsetZ);
            if (neighbor instanceof TileEntityNetworkQuantumNode) {
                return null;
            }
        }
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
        // v1.6.2 修复：无锚点节点（未经量子终端放置，如创造模式直接放置的空白节点）无效——
        // 不初始化 AE proxy（不创建 GridNode、不耗电、不接受邻居连接），恒离线 NO_ANCHOR。
        // 空白节点一旦经终端放置写入锚点（setAnchor），本判定自动解除，proxy 走正常就绪流程。
        if (!hasAnchor()) {
            this.offlineReason = OfflineReason.NO_ANCHOR;
            syncLinkedStateIfChanged(false);
            return;
        }
        // v1.6.19：性能审计——本节点单 tick 耗时采样起点（开关关闭时零开销）
        long auditT0 = PerformanceAudit.start();
        // ===== proxy 就绪流程（照样板：暂存 NBT 重放 → onReady 一次性调用） =====
        if (pendingProxyNBT != null) {
            getProxy().readFromNBT(pendingProxyNBT);
            pendingProxyNBT = null;
        }
        if (!aeProxyReady) {
            getProxy().onReady();
            aeProxyReady = true;
        }
        // v1.6.4 任务4：每 tick 比对在线状态（不受下方 20t 维护窗口限制），
        // 变化即 markBlockForUpdate 推送 S35，材质切换延迟 ≤1t
        boolean linkedNow = isLinked();
        syncLinkedStateIfChanged(linkedNow);
        // ===== 桥接连接维护：每 20 tick 一次；lastMaintenanceTick 初值 -1 保证就绪后首轮立即执行 =====
        long tick = worldObj.getTotalWorldTime();
        if (this.lastMaintenanceTick >= 0L && tick - this.lastMaintenanceTick < MAINTENANCE_INTERVAL_TICKS) {
            // v1.6.19：性能审计——非维护窗口 tick 也结算本 tick 耗时（isLinked 判活等）
            PerformanceAudit.record(auditT0);
            return;
        }
        this.lastMaintenanceTick = tick;
        maintainConnection();
        PerformanceAudit.record(auditT0);
    }

    // ==================== 桥接逻辑（仅服务端） ====================

    /**
     * 连接维护主循环（每 20 tick 一次）。
     * <ol>
     * <li>连接存活、锚点未改指且锚点仍量子化 → 跳过；</li>
     * <li>连接在但锚点已改指 / 锚点已取消量子化（v1.6.1 问题 5）→ 销毁旧连接后重建（重建时按规则离线）；</li>
     * <li>无连接 → 按 D6/D7 规则尝试建连，失败记录离线原因待下轮重试。</li>
     * <li>节点与锚点区块由外部区块加载器自然加载；区块加载后本循环负责自动恢复连接。</li>
     * </ol>
     */
    private void maintainConnection() {
        // v1.6.19：性能审计——连接维护计数
        PerformanceAudit.recordQuantumMaintenance();
        // v1.6.8：网络过载检查（仅桥接存活时执行，避免离线节点重复触发）
        if (this.connection != null && isLinked()) {
            checkNetworkOverload();
            // 检查后如果爆炸已触发，connection 会被同步销毁，直接返回
            if (this.connection == null || !isLinked()) {
                return;
            }
        }
        // ===== 原有逻辑保持不变 =====
        if (this.connection != null) {
            if (isLinked() && isAnchorSnapshotMatched() && isAnchorStillQuantized()) {
                return;
            }
            // 连接已死（对端销毁/本节点重建）或锚点改指或锚点已取消量子化：清理后走重建
            destroyBridgeConnection();
        }
        tryConnect();
    }

    // ==================== v1.6.8：网络过载爆炸 + 95% 预警 ====================

    /**
     * v1.6.8：网络过载检查。
     * <p>
     * 每 20t 在 maintainConnection 顶部执行：
     * <ol>
     * <li>获取锚点控制器 grid</li>
     * <li>计算 totalChannels（floodControllers + computeTotalChannels）</li>
     * <li>计算全局 usedChannels（复用 QuantumNetworkData.computeUsedChannels）</li>
     * <li>used > total → 启动 3 分钟爆炸倒计时并公告；剩余 2/1 分钟、10 秒处各再公告一次；
     * 期间频道恢复即取消倒计时并公告；到期仍超限才 explodeControllers（整个控制器结构 TNT 级爆炸）</li>
     * <li>used >= 95% * total 且本节点频道增长 → warnPlacer（向放置者发聊天警告）</li>
     * </ol>
     */
    private void checkNetworkOverload() {
        // v1.6.19：性能审计——过载检查计数
        PerformanceAudit.recordQuantumOverloadCheck();
        // AE2 Channels=false already removes the native channel limit. Skip this
        // mod's independent budget, warning, and overflow explosion checks too.
        if (QuantumControllerRegistry.isChannelsInfinite()) {
            return;
        }
        // v1.6.10：取锚点维度 world（跨维度桥接时 worldObj 是节点维度，锚点控制器在 anchorWorld）
        WorldServer anchorWorld = DimensionManager.getWorld(this.anchorDim);
        if (anchorWorld == null) {
            return;
        }
        // 1. 获取锚点控制器 TE
        TileEntity anchorTE = anchorWorld.getTileEntity(this.anchorX, this.anchorY, this.anchorZ);
        if (!(anchorTE instanceof TileController)) {
            return;
        }
        // 2. 经接口获取 grid（编译坑规避：源表达式静态类型为 TileEntity）
        AENetworkProxy anchorProxy = ((IGridProxyable) anchorTE).getProxy();
        if (anchorProxy == null || !anchorProxy.isReady()) {
            return;
        }
        final IGrid grid;
        try {
            grid = anchorProxy.getGrid();
        } catch (GridAccessException e) {
            return;
        }
        // v1.6.20：复用节点常驻 AnchorKey（锚点改指/首次使用时按四字段重建），
        // 避免每 20t 每次检查向统计缓存与倒计时各分配一个新键
        AnchorKey key = this.anchorKey;
        if (key == null || key.getDimension() != this.anchorDim
            || key.getX() != this.anchorX
            || key.getY() != this.anchorY
            || key.getZ() != this.anchorZ) {
            key = this.anchorKey = new AnchorKey(this.anchorDim, this.anchorX, this.anchorY, this.anchorZ);
        }
        // 3-4. 复用同一锚点的 100 tick 主线程统计快照，避免每个节点重复洪泛和遍历全网节点。
        QuantumNetworkStatsCache.Snapshot stats = QuantumNetworkStatsCache.getOrCompute(key, anchorWorld, grid);
        if (stats == null) {
            return;
        }
        int total = stats.totalChannels;
        int used = stats.usedChannels;
        // 5. 计算本节点 max usedChannels
        IGridNode myNode = getProxy().getNode();
        if (myNode == null) {
            return;
        }
        int nodeUsed = 0;
        for (IGridConnection c : myNode.getConnections()) {
            nodeUsed = Math.max(nodeUsed, c.getUsedChannels());
        }
        // 6. 超限倒计时判定（v1.6.19：不再立即爆炸，先 3 分钟倒计时，期间恢复即取消）
        QuantumOverloadCountdown.Result countdown = QuantumOverloadCountdown
            .check(key, anchorWorld.getTotalWorldTime(), used > total);
        switch (countdown) {
            case EXPLODE:
                explodeControllers(stats.getStructure());
                return;
            case STARTED:
                messagePlacer("gtswn.chat.quantum.overload_warning", "3 分钟");
                break;
            case ANNOUNCE_2MIN:
                messagePlacer("gtswn.chat.quantum.overload_warning", "2 分钟");
                break;
            case ANNOUNCE_1MIN:
                messagePlacer("gtswn.chat.quantum.overload_warning", "1 分钟");
                break;
            case ANNOUNCE_10S:
                messagePlacer("gtswn.chat.quantum.overload_warning", "10 秒");
                break;
            case CANCELLED:
                messagePlacer("gtswn.chat.quantum.overload_cancelled");
                break;
            default:
                break;
        }
        // 7. 95% 预警判定（本节点频道增长 + 全局达 95% + 未过载）
        if (nodeUsed > this.lastNodeUsedChannels && used * 100 >= total * 95 && used < total) {
            warnPlacer(used, total);
        }
        // 8. 更新本节点上次值
        this.lastNodeUsedChannels = nodeUsed;
    }

    /**
     * v1.6.8：网络过载爆炸——销毁整个控制器结构 + TNT 级爆炸。
     * <p>
     * v1.6.19 起仅在超限倒计时到期仍超限时触发（不再立即爆炸）。
     * 先 setBlockToAir 全部控制器（同步销毁所有量子节点连接，去重后续节点爆炸检测），
     * 再在锚点位置 createExplosion 制造 TNT 级爆炸效果（威力 4.0，破坏方块+伤害实体）。
     *
     * @param structure floodControllers 返回的整结构坐标集
     */
    private void explodeControllers(Set<Long> structure) {
        // v1.6.10：取锚点维度 world（跨维度桥接时爆炸须发生在锚点维度，而非节点维度）
        WorldServer anchorWorld = DimensionManager.getWorld(this.anchorDim);
        if (anchorWorld == null) {
            return;
        }
        // 1. 销毁所有控制器方块（setBlockToAir 触发 TileController.invalidate → GridNode.destroy
        // → 所有量子节点 connection 同步销毁，后续节点 isLinked() 返回 false 跳过爆炸）
        for (long packed : structure) {
            int cx = QuantumControllerRegistry.unpackX(packed);
            int cy = QuantumControllerRegistry.unpackY(packed);
            int cz = QuantumControllerRegistry.unpackZ(packed);
            anchorWorld.setBlockToAir(cx, cy, cz);
        }
        // 2. 锚点位置 TNT 级爆炸（威力 4.0，smoking=true 破坏周围方块）
        anchorWorld.createExplosion(null, this.anchorX + 0.5D, this.anchorY + 0.5D, this.anchorZ + 0.5D, 4.0F, true);
    }

    /**
     * v1.6.8：95% 预警——向本节点放置者发送聊天警告（委托给 {@link #messagePlacer}）。
     *
     * @param used  当前已用频道
     * @param total 总频道上限
     */
    private void warnPlacer(int used, int total) {
        messagePlacer("gtswn.chat.quantum.warning_95", used, total);
    }

    /**
     * v1.6.19：向本节点放置者发送聊天消息。
     * <p>
     * 通过 GridNode.getPlayerID() 反查在线玩家（WorldData.instance().playerData().getPlayerFromID）。
     * 玩家离线时返回 null，消息静默跳过（符合"仅放置者"语义）。
     * args 为空时用 {@link StatCollector#translateToLocal}，非空时用
     * {@link StatCollector#translateToLocalFormatted}。
     *
     * @param key  语言键
     * @param args 格式化参数（可为空）
     */
    private void messagePlacer(String key, Object... args) {
        IGridNode myNode = getProxy().getNode();
        if (myNode == null) {
            return;
        }
        int playerID = myNode.getPlayerID();
        if (playerID < 0) {
            return;
        }
        EntityPlayer placer = WorldData.instance()
            .playerData()
            .getPlayerFromID(playerID);
        if (placer == null) {
            return;
        }
        String msg = args.length == 0 ? StatCollector.translateToLocal(key)
            : StatCollector.translateToLocalFormatted(key, args);
        placer.addChatMessage(new ChatComponentText(msg));
    }

    /** 锚点控制器当前是否仍处于量子化状态（v1.6.10 跨维度：取锚点维度 world 查询注册表） */
    private boolean isAnchorStillQuantized() {
        WorldServer anchorWorld = DimensionManager.getWorld(this.anchorDim);
        if (anchorWorld == null) {
            return false;
        }
        return QuantumControllerRegistry.get(anchorWorld)
            .isQuantized(this.anchorX, this.anchorY, this.anchorZ);
    }

    /** 尝试向锚点控制器建立桥接连接，失败时记录离线原因 */
    private void tryConnect() {
        // v1.6.19：性能审计——建连尝试计数
        PerformanceAudit.recordQuantumTryConnect();
        // 无锚点：未经量子终端放置的节点（如创造模式直接放置）恒离线
        if (!hasAnchor()) {
            this.offlineReason = OfflineReason.NO_ANCHOR;
            return;
        }
        // v1.6.10：跨维度桥接——取锚点维度 world（维度未加载 → 离线 ANCHOR_UNREACHABLE）
        WorldServer anchorWorld = DimensionManager.getWorld(this.anchorDim);
        if (anchorWorld == null) {
            this.offlineReason = OfflineReason.ANCHOR_UNREACHABLE;
            return;
        }
        // v1.6.1 问题 5：锚点控制器未处于量子化状态（已取消量子化）→ 不建连。
        // 注册表查询仅读 WorldSavedData 坐标集合，不触发区块加载，可在区块校验前执行
        if (!QuantumControllerRegistry.get(anchorWorld)
            .isQuantized(this.anchorX, this.anchorY, this.anchorZ)) {
            this.offlineReason = OfflineReason.ANCHOR_NOT_QUANTIZED;
            return;
        }
        // 锚点区块未加载：blockExists 不触发区块加载（与 TileWirelessBase 重连循环同一手法），
        // 避免节点 tick 把锚点区块常加载造成级联加载。
        // 锚点区块未加载时保持离线；不主动加载区块，待服务器或其他模组自然加载后由下一轮重试
        if (!anchorWorld.blockExists(this.anchorX, this.anchorY, this.anchorZ)) {
            this.offlineReason = OfflineReason.ANCHOR_UNREACHABLE;
            return;
        }
        // 锚点位置已不是 ME 控制器（D7：锚点被拆 → 离线保留绑定，可经终端改绑后重放节点）
        TileEntity anchorTE = anchorWorld.getTileEntity(this.anchorX, this.anchorY, this.anchorZ);
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
            // v1.6.19：性能审计——建连成功计数
            PerformanceAudit.recordQuantumBridgeSuccess();
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
                // v1.6.19：性能审计——收养既有直连成功计数
                PerformanceAudit.recordQuantumBridgeSuccess();
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
            // v1.6.19：性能审计——桥接销毁计数
            PerformanceAudit.recordBridgeDestroyed();
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
        // v1.6.13 任务1：防御性读取同步在线状态，防止区块加载/磁盘读取后 clientLinked 缺失
        if (tag.hasKey(NBT_SYNC_LINKED)) {
            this.clientLinked = tag.getBoolean(NBT_SYNC_LINKED);
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
        // v1.6.13 任务1：持久化同步在线状态，用于区块加载/磁盘读取后恢复 clientLinked
        tag.setBoolean(NBT_SYNC_LINKED, isLinked());
        GTSimpleWirelessNetwork.LOG
            .debug("[量子节点] writeToNBT 写入同步状态 @ ({},{},{}) linked={}", xCoord, yCoord, zCoord, isLinked());
        if (gridProxy != null) {
            gridProxy.writeToNBT(tag);
        }
    }

    // ==================== 在线状态同步（v1.6.4 任务4：驱动客户端状态材质渲染） ====================
    // 独立小 NBT，不带 readFromNBT/writeToNBT 的 "proxy" GridNode 持久化数据

    @Override
    public Packet getDescriptionPacket() {
        // ①chunk 初次同步（S21/S26 携带）②服务端 markBlockForUpdate 触发的 S35 单点更新
        NBTTagCompound tag = new NBTTagCompound();
        tag.setBoolean(NBT_SYNC_LINKED, isLinked());
        return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 1, tag);
    }

    @Override
    public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity pkt) {
        // v1.6.13 任务1：防御 pkt 为空或读取失败
        if (pkt == null) {
            GTSimpleWirelessNetwork.LOG.warn("[量子节点] onDataPacket 收到 null 包");
            return;
        }
        NBTTagCompound tag = pkt.func_148857_g();
        if (tag == null) {
            GTSimpleWirelessNetwork.LOG.warn("[量子节点] onDataPacket 读取 NBT 失败");
            return;
        }
        this.clientLinked = tag.getBoolean(NBT_SYNC_LINKED);
        // 1.7.10 客户端收 S35 不自动重渲染：markBlockForUpdate → RenderGlobal 标脏，下帧按新图标重绘
        if (worldObj != null) {
            worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
        }
    }
}
