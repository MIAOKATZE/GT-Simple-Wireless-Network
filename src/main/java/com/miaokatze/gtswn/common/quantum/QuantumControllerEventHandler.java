package com.miaokatze.gtswn.common.quantum;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.StatCollector;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.ExplosionEvent;

import com.miaokatze.gtswn.common.block.BlockNetworkQuantumNode;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.performance.PerformanceAudit;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;
import com.miaokatze.gtswn.network.WirelessEURequestQueue;

import appeng.api.implementations.items.INetworkToolItem;
import appeng.block.networking.BlockCreativeEnergyCell;
import appeng.block.networking.BlockEnergyAcceptor;
import appeng.block.networking.BlockEnergyCell;
import appeng.me.helpers.AENetworkProxy;
import appeng.me.helpers.IGridProxyable;
import appeng.parts.networking.PartQuartzFiber;
import appeng.tile.networking.TileCableBus;
import appeng.tile.networking.TileController;
import appeng.tile.networking.TileCreativeEnergyCell;
import appeng.tile.networking.TileEnergyAcceptor;
import appeng.tile.networking.TileEnergyCell;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 量子化控制器事件处理器（T2 核心，规划 §1 架构 / §2 D1-B / §8 T2）。
 * <p>
 * 职责总览：
 * <ul>
 * <li>{@link #onPlayerInteract}：全量拦截对已量子化控制器的右键交互（D2-A），
 * 手持量子终端时放行（手势由物品 onItemUseFirst 处理）</li>
 * <li>{@link #onBreakSpeed}：量子化控制器与量子节点挖掘减速至等效硬度 500（黑曜石×10）</li>
 * <li>{@link #onBlockPlace}：方块放置时即时重算邻接已量子化控制器的连接过滤
 * （1.7.10 无 NeighborNotifyEvent，以 PlaceEvent 覆盖主路径）</li>
 * <li>{@link #onBlockBreak}：已量子化控制器被拆时出册清理（整结构逐块拆除逐块出册）</li>
 * <li>{@link #onServerTick}：每秒（20t）巡检——连接过滤兜底重算、失效坐标出册、
 * D8 新贴上控制器自动合并量子化</li>
 * </ul>
 * <p>
 * 连接过滤原理（规划 §1 已核实）：连接形成的唯一逻辑闸门是
 * {@code AENetworkProxy.getConnectableSides()}（经 GridNode.updateState 压入 validDirections），
 * {@code setValidSides} 为 public API 且变更后 updateState 会销毁不再合法的既有连接。
 * 注册：CommonProxy.init() 中同时注册到 MinecraftForge.EVENT_BUS（前 4 个事件）
 * 与 FMLCommonHandler bus（tick 事件）。
 */
public class QuantumControllerEventHandler {

    /** 等效挖掘硬度目标值：黑曜石（50）× 20 = 1000 */
    private static final float QUANTUM_EFFECTIVE_HARDNESS = 1000.0F;

    /** 右键拦截聊天提示冷却（tick），防止按住右键/连点刷屏 */
    private static final long BLOCKED_MSG_COOLDOWN_TICKS = 40L;

    /** tick 巡检间隔（tick）：20t = 1 秒 */
    private static final long SWEEP_INTERVAL_TICKS = 20L;

    /** D8 合并事件聊天提示半径（格，平方距离比较） */
    private static final double MERGE_NOTIFY_RADIUS_SQ = 48.0D * 48.0D;

    /** 右键拦截提示冷却表：玩家 UUID → 上次提示的世界 tick */
    private final Map<UUID, Long> lastBlockedMsgTick = new HashMap<>();

    /** 上次巡检的世界 tick（-1 = 未巡检过） */
    private long lastSweepTick = -1L;

    /** 上一次处理的服务器实例，用于切换存档时清理运行期缓存。 */
    private MinecraftServer lastCacheServer;

    /** 连接过滤实际触发 AE2 updateState 的次数，仅用于每秒 DEBUG 汇总。 */
    private static long filterUpdates;

    // ==================== 1. 右键交互全量拦截（D2-A） ====================

    /**
     * 拦截对已量子化 ME 控制器的右键交互。
     * <p>
     * 仅服务端执行：取消事件阻止方块激活（服务端权威），聊天提示带 2s 冷却防刷屏。
     * 手持量子终端时不拦截——量子化/绑定/取消手势由 {@link ItemNetworkQuantumTerminal#onItemUseFirst} 处理。
     */
    @SubscribeEvent
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        // 仅服务端取消与提示；客户端镜像事件直接忽略，避免提示双发
        if (event.world.isRemote) {
            return;
        }
        TileEntity te = event.world.getTileEntity(event.x, event.y, event.z);
        if (!(te instanceof TileController)) {
            return;
        }
        if (!QuantumControllerRegistry.get(event.world)
            .isQuantized(event.x, event.y, event.z)) {
            return;
        }
        // AE2 网络工具普通右击 → 放行原生网络状态 GUI；Shift+右击仍落入下方拦截，防止拆卸
        ItemStack held = event.entityPlayer.getHeldItem();
        if (held != null && held.getItem() instanceof INetworkToolItem && !event.entityPlayer.isSneaking()) {
            return;
        }
        // 手持量子终端 → 放行（不取消事件，物品 onItemUseFirst 才能收到交互）
        if (held != null && held.getItem() instanceof ItemNetworkQuantumTerminal) {
            return;
        }
        // v1.6.12：手持能源相关方块（能源接收器/能源元件/创造能源元件/致密能源元件）→ 放行放置
        // 与 applyConnectionFilter 连接白名单语义对齐：能放行放置的方块即是放置后控制器会接受连接的方块
        if (isEnergyRelatedItem(held)) {
            return;
        }
        // 非能源相关物品/空手 → 全量拦截
        GTSimpleWirelessNetwork.LOG.debug(
            "[量子化] 拦截非能源相关物品/空手右键已量子化控制器 @ ({},{},{}) 玩家={}",
            event.x,
            event.y,
            event.z,
            event.entityPlayer.getCommandSenderName());
        event.setCanceled(true);
        // 冷却防刷屏：同一玩家 2 秒内仅提示一次
        long now = event.world.getTotalWorldTime();
        UUID playerId = event.entityPlayer.getUniqueID();
        Long last = this.lastBlockedMsgTick.get(playerId);
        if (last == null || now - last >= BLOCKED_MSG_COOLDOWN_TICKS) {
            this.lastBlockedMsgTick.put(playerId, now);
            event.entityPlayer
                .addChatMessage(new ChatComponentText(StatCollector.translateToLocal("gtswn.chat.quantum.blocked")));
        }
    }

    /**
     * 判定手持物品是否为「能源相关方块」（v1.6.12 放行放置）。
     * <p>
     * 用于 {@link #onPlayerInteract} 放行玩家贴已量子化控制器表面放置能源相关方块的场景。
     * 与 {@link #applyConnectionFilter} 的连接白名单语义对齐——能放行的方块即是放置后
     * 控制器会接受连接的方块（能源接收器 / 能源元件 / 创造能源元件），避免玩家贴控制器
     * 放置非能源方块造成「放得下却连不上」的视觉不一致。
     * <p>
     * 覆盖：
     * <ul>
     * <li>{@link BlockEnergyAcceptor}（能源接收器）</li>
     * <li>{@link BlockEnergyCell}（能源元件，含致密 BlockDenseEnergyCell——因其继承 BlockEnergyCell，
     * instanceof 一并覆盖）</li>
     * <li>{@link BlockCreativeEnergyCell}（创造能源元件）</li>
     * </ul>
     * 不放行 cable bus（ItemMultiPart）——玩家需在控制器旁的非控制器面先放 cable bus，
     * 再插入石英纤维朝向控制器（此路径不触发 onPlayerInteract，已正常工作）。
     *
     * @param stack 玩家手持物品栈（可能为 null）
     * @return true 表示该物品是能源相关方块，应放行右键放置
     */
    private static boolean isEnergyRelatedItem(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return false;
        }
        Block block = Block.getBlockFromItem(stack.getItem());
        if (block == null) {
            return false;
        }
        // 致密 BlockDenseEnergyCell 继承 BlockEnergyCell，instanceof BlockEnergyCell 一并覆盖
        return block instanceof BlockEnergyAcceptor || block instanceof BlockEnergyCell
            || block instanceof BlockCreativeEnergyCell;
    }

    // ==================== 2. 挖掘减速（等效硬度 1000） ====================

    /**
     * 把已量子化控制器的挖掘速度按「实际硬度 / 1000」缩放，
     * 使挖掘耗时等效于硬度 1000（黑曜石 50 的 20 倍）。
     * <p>
     * 机制：挖掘耗时 ∝ blockHardness / newSpeed，故 newSpeed ×= (hardness/1000) 后等效硬度即 1000。
     * 量子节点方块的硬度已直接设为 1000，不再由本事件修正。本事件双侧触发，此处只读查询无副作用，
     * 客户端查不到量子化状态（WorldSavedData 不同步）时不减速——由服务端权威纠正（挖掘回弹），可接受。
     */
    @SubscribeEvent
    public void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        // 1.7.10 中对空气/无效目标触发时 y = -1，直接跳过
        if (event.y < 0) {
            return;
        }
        World world = event.entityPlayer.worldObj;
        if (world.isRemote) {
            return;
        }
        // 量子节点方块：硬度/抗性已在方块属性中直接表达，不依赖事件修正
        if (event.block instanceof BlockNetworkQuantumNode) {
            return;
        }
        // 非量子节点方块：仅当目标 TE 是「已量子化」的控制器时才减速
        TileEntity te = world.getTileEntity(event.x, event.y, event.z);
        if (!(te instanceof TileController)) {
            return;
        }
        if (!QuantumControllerRegistry.get(world)
            .isQuantized(event.x, event.y, event.z)) {
            return;
        }
        float hardness = event.block.getBlockHardness(world, event.x, event.y, event.z);
        if (hardness <= 0.0F) {
            return;
        }
        event.newSpeed = event.originalSpeed * (hardness / QUANTUM_EFFECTIVE_HARDNESS);
    }

    /**
     * 爆炸免疫：从受影响方块列表中移除已量子化 ME 控制器，使其等效防爆 400000。
     * <p>
     * 仅服务端处理：爆炸事件服务端权威，客户端直接忽略。
     */
    @SubscribeEvent
    public void onExplodeDetonate(ExplosionEvent.Detonate event) {
        if (event.world.isRemote) {
            return;
        }
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(event.world);
        Iterator<ChunkPosition> it = event.getAffectedBlocks()
            .iterator();
        while (it.hasNext()) {
            ChunkPosition pos = it.next();
            if (event.world.getTileEntity(pos.chunkPosX, pos.chunkPosY, pos.chunkPosZ) instanceof TileController
                && registry.isQuantized(pos.chunkPosX, pos.chunkPosY, pos.chunkPosZ)) {
                it.remove();
            }
        }
    }

    // ==================== 3. 邻接变化即时重算连接过滤（D1-B 主路径） ====================

    /**
     * 方块放置事件：放置位置自身或其 6 邻接存在已量子化控制器时，对其重算连接过滤。
     * <p>
     * 1.7.10 无 1.9+ 的 NeighborNotifyEvent，此处以 PlaceEvent 覆盖「贴控制器放线缆/机器」
     * 这一主路径（事件在 setBlock 之后触发，TE 已就绪）；破坏侧由 AE2 节点移除自然断连，
     * 部件级变化（cable bus 内插拔石英纤维等）由每秒巡检兜底重算。
     */
    @SubscribeEvent
    public void onBlockPlace(BlockEvent.PlaceEvent event) {
        if (event.world.isRemote) {
            return;
        }
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(event.world);
        applyFilterIfQuantized(registry, event.world, event.x, event.y, event.z);
        for (ForgeDirection d : ForgeDirection.VALID_DIRECTIONS) {
            applyFilterIfQuantized(
                registry,
                event.world,
                event.x + d.offsetX,
                event.y + d.offsetY,
                event.z + d.offsetZ);
        }
    }

    /** 若指定位置是已量子化控制器则重算其连接过滤 */
    private static void applyFilterIfQuantized(QuantumControllerRegistry registry, World world, int x, int y, int z) {
        if (!registry.isQuantized(x, y, z)) {
            return;
        }
        if (world.getTileEntity(x, y, z) instanceof TileController) {
            applyConnectionFilter(world, x, y, z);
        }
    }

    // ==================== 4. 破坏出册清理 ====================

    /**
     * 已量子化控制器被拆时出册。整结构其余块保持量子化（逐块拆除逐块出册），
     * 被破坏方块的 setValidSides 恢复已无意义（方块实体随方块一并销毁）。
     * 量子节点方块被拆无需处理（其 TE 桥接生命周期由 T4 自理）。
     */
    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.world.isRemote) {
            return;
        }
        TileEntity te = event.world.getTileEntity(event.x, event.y, event.z);
        if (te instanceof TileController) {
            QuantumControllerRegistry.get(event.world)
                .dequantize(event.x, event.y, event.z);
        }
    }

    // ==================== 5. 每秒巡检（兜底 + D8 自动合并） ====================

    /**
     * ServerTickEvent（END 相）：
     * <ol>
     * <li>每 tick：排空量子终端数据请求队列（v1.6.1 问题 4b，Netty 线程入队 → 主线程装配回包）</li>
     * <li>每 20t：遍历每个已加载世界的注册表，TE 已不是控制器 → 出册（防 NBT 重载/旁路残留）</li>
     * <li>每 20t：仍在册的控制器 → 重算连接过滤（兜底，覆盖邻接通知未触达的路径）</li>
     * <li>每 20t：D8——已量子化控制器 6 邻接出现未入册控制器 → 洪泛其整结构自动入册
     * （防止借新结构开线缆后门），逐块应用过滤并向附近玩家提示合并事件</li>
     * </ol>
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return;
        }
        if (this.lastCacheServer != server) {
            this.lastCacheServer = server;
            this.lastSweepTick = -1L;
            QuantumNetworkStatsCache.clear();
            QuantumNetworkData.clearCache();
            QuantumOverloadCountdown.clear();
        }
        // v1.6.1 问题 4b：每 tick 在主线程排空量子终端数据请求队列（独立于下方每秒巡检节奏）
        QuantumTerminalRequestQueue.drain();
        // v1.6.19：每 tick 在主线程排空无线 EU 查询请求队列（WirelessNetworkManager 主线程安全）
        WirelessEURequestQueue.drain();
        // 以 overworld 总 tick 做间隔基准（与 NetworkInfoMonitorScheduler 一致）
        World overworld = server.worldServerForDimension(0);
        if (overworld == null) {
            return;
        }
        long tick = overworld.getTotalWorldTime();
        if (this.lastSweepTick >= 0L && tick - this.lastSweepTick < SWEEP_INTERVAL_TICKS) {
            return;
        }
        this.lastSweepTick = tick;
        // WorldSavedData 是 perWorldStorage 每世界一份，逐世界各自巡检
        for (WorldServer world : server.worldServers) {
            try {
                sweepWorld(world);
            } catch (Throwable t) {
                // 单世界巡检异常不影响其他世界与主循环
                com.miaokatze.gtswn.main.GTSimpleWirelessNetwork.LOG
                    .error("[量子化] 世界 " + world.provider.dimensionId + " 巡检异常", t);
            }
        }
        if (GTSimpleWirelessNetwork.LOG.isDebugEnabled()) {
            GTSimpleWirelessNetwork.LOG.debug(
                "[量子性能] {} ; {} ; filterUpdate={}",
                QuantumNetworkStatsCache.consumeDebugStats(),
                QuantumNetworkData.consumeDebugStats(),
                filterUpdates);
        }
        filterUpdates = 0L;
    }

    /** 巡检单个世界：出册失效坐标、重算过滤、D8 合并 */
    private static void sweepWorld(WorldServer world) {
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(world);
        Set<Long> all = registry.getAll();
        if (all.isEmpty()) {
            return;
        }
        // v1.6.19：性能审计——巡检计数
        PerformanceAudit.recordControllerSweep();
        // v1.6.20：允许面集合在循环外建一次复用，避免每控制器每次调用新建 EnumSet
        EnumSet<ForgeDirection> allowed = EnumSet.noneOf(ForgeDirection.class);
        for (long packed : all) {
            int x = QuantumControllerRegistry.unpackX(packed);
            int y = QuantumControllerRegistry.unpackY(packed);
            int z = QuantumControllerRegistry.unpackZ(packed);
            TileEntity te = world.getTileEntity(x, y, z);
            if (!(te instanceof TileController)) {
                // TE 消失（拆除/卸载异常/NBT 重载旁路）→ 出册
                registry.dequantize(packed);
                continue;
            }
            // 连接过滤兜底重算
            applyConnectionFilter(world, x, y, z, allowed);
            // D8：6 邻接发现未入册控制器 → 整结构自动合并量子化
            for (ForgeDirection d : ForgeDirection.VALID_DIRECTIONS) {
                int nx = x + d.offsetX;
                int ny = y + d.offsetY;
                int nz = z + d.offsetZ;
                if (registry.isQuantized(nx, ny, nz)) {
                    continue;
                }
                if (world.getTileEntity(nx, ny, nz) instanceof TileController) {
                    mergeNewStructure(registry, world, nx, ny, nz);
                    // 一次合并后跳出邻接扫描：同结构其余邻接已在洪泛集合内，
                    // 若仍存其他未入册邻接结构，下一轮巡检再处理
                    break;
                }
            }
        }
    }

    /** D8 合并：洪泛新结构入册 + 逐块应用过滤 + 向附近玩家提示 */
    private static void mergeNewStructure(QuantumControllerRegistry registry, World world, int x, int y, int z) {
        // v1.6.19：性能审计——D8 合并计数
        PerformanceAudit.recordControllerMerge();
        Set<Long> structure = QuantumControllerRegistry.floodControllers(world, x, y, z);
        // 统计真正新增的块数（洪泛可能覆盖已入册的旧结构块）
        int added = 0;
        for (long packed : structure) {
            if (!registry.isQuantized(packed)) {
                added++;
            }
        }
        registry.quantizeAll(structure);
        for (long packed : structure) {
            applyConnectionFilter(
                world,
                QuantumControllerRegistry.unpackX(packed),
                QuantumControllerRegistry.unpackY(packed),
                QuantumControllerRegistry.unpackZ(packed));
        }
        notifyMerged(world, x, y, z, added);
    }

    /** 向合并点附近 48 格内的玩家发送合并提示 */
    private static void notifyMerged(World world, int x, int y, int z, int added) {
        String msg = StatCollector.translateToLocalFormatted("gtswn.chat.quantum.merged", added);
        for (Object obj : world.playerEntities) {
            EntityPlayer player = (EntityPlayer) obj;
            if (player.getDistanceSq(x + 0.5D, y + 0.5D, z + 0.5D) <= MERGE_NOTIFY_RADIUS_SQ) {
                player.addChatMessage(new ChatComponentText(msg));
            }
        }
    }

    // ==================== 连接过滤核心（静态工具，供物品/事件共用） ====================

    /**
     * 对已量子化控制器逐面判定并应用连接白名单（自建允许面集合）。
     * 委托给复用集合的重载，语义一致。
     */
    public static void applyConnectionFilter(World world, int x, int y, int z) {
        applyConnectionFilter(world, x, y, z, EnumSet.noneOf(ForgeDirection.class));
    }

    /**
     * 对已量子化控制器逐面判定并应用连接白名单（复用调用方提供的允许面集合）。
     * <p>
     * 放行面 = 相邻为以下之一：
     * <ul>
     * <li>{@link TileController}：同伴控制器（D10 整结构互联）</li>
     * <li>{@link TileEnergyAcceptor}：能量接收器（纯能量交互设备）</li>
     * <li>{@link TileEnergyCell} / {@link TileCreativeEnergyCell}：能源元件（v1.6.1 问题 4a，
     * 量子化控制器需保持与能源元件连接，否则网络断电停机；致密能源元件
     * TileDenseEnergyCell 继承 TileEnergyCell，一并覆盖）</li>
     * <li>{@link TileCableBus} 且其<b>朝向本控制器那一面</b>的 Part 是 {@link PartQuartzFiber}
     * （石英纤维通过 outerProxy 按面暴露纯能量节点）</li>
     * </ul>
     * 其余面（线缆/机器/存储等）全部拒绝。setValidSides 变更后 GridNode.updateState
     * 会销毁不再合法的既有连接，且该集合是连接形成的唯一逻辑闸门。
     * <p>
     * 注意：控制器 proxy 未 ready 时调用同样安全——AENetworkProxy.getNode() 创建节点后
     * updateState 会读取最新 validSides。
     * <p>
     * v1.6.20：allowed 由调用方（每秒巡检循环外）持有复用，方法开头 clear() 后填充，
     * 避免每控制器每次调用新建 EnumSet；语义与自建版本逐点等价。
     */
    public static void applyConnectionFilter(World world, int x, int y, int z, EnumSet<ForgeDirection> allowed) {
        TileEntity te = world.getTileEntity(x, y, z);
        if (!(te instanceof TileController)) {
            return;
        }
        allowed.clear();
        for (ForgeDirection d : ForgeDirection.VALID_DIRECTIONS) {
            TileEntity neighbor = world.getTileEntity(x + d.offsetX, y + d.offsetY, z + d.offsetZ);
            if (neighbor instanceof TileController) {
                allowed.add(d);
            } else if (neighbor instanceof TileEnergyAcceptor) {
                allowed.add(d);
            } else if (neighbor instanceof TileEnergyCell || neighbor instanceof TileCreativeEnergyCell) {
                // v1.6.1 问题 4a：量子化控制器需保持与能源元件连接，否则网络断电停机
                // （TileDenseEnergyCell 致密能源元件继承 TileEnergyCell，此处一并放行）
                allowed.add(d);
            } else if (neighbor instanceof TileCableBus) {
                // 石英纤维判定：站在控制器视角邻居在方向 d，从 cable bus 视角控制器在 d 的反方向，
                // 附着在该反方向面上的 Part 即朝向本控制器的部件
                if (((TileCableBus) neighbor).getPart(d.getOpposite()) instanceof PartQuartzFiber) {
                    allowed.add(d);
                }
            }
        }
        setValidSidesIfChanged(te, allowed);
    }

    /**
     * 恢复控制器全方向可连接（取消量子化时逐块调用）。
     * 若结构本身不合法，TileController.onNeighborChange 后续会自行修正 validSides，无需额外处理。
     */
    public static void restoreAllSides(World world, int x, int y, int z) {
        TileEntity te = world.getTileEntity(x, y, z);
        if (te instanceof TileController) {
            setValidSidesIfChanged(te, EnumSet.allOf(ForgeDirection.class));
        }
    }

    /** 只有 AE2 当前有效面集合发生变化时才触发 GridNode.updateState。 */
    private static void setValidSidesIfChanged(TileEntity te, EnumSet<ForgeDirection> desired) {
        AENetworkProxy proxy = ((IGridProxyable) te).getProxy();
        EnumSet<ForgeDirection> current = proxy.getConnectableSides();
        if (current != null && current.equals(desired)) {
            return;
        }
        EnumSet<ForgeDirection> copy = EnumSet.noneOf(ForgeDirection.class);
        copy.addAll(desired);
        proxy.setValidSides(copy);
        filterUpdates++;
        // v1.6.19：性能审计——连接过滤实际更新计数
        PerformanceAudit.recordControllerFilterUpdate();
    }
}
