package com.miaokatze.gtswn.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.covers.GTswnCoverWirelessBase;
import com.miaokatze.gtswn.common.covers.WirelessNodeIndexCodec;
import com.miaokatze.gtswn.common.covers.WirelessNodeRegistry;
import com.miaokatze.gtswn.common.items.WirelessEnergyTap;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.api.interfaces.tileentity.ICoverable;
import gregtech.common.covers.Cover;

/**
 * 节点显形请求待处理队列（照 {@link DeviceTerminalActionQueue} 的「Netty 入队 → 主线程 drain」
 * 模式）：包 11 的世界态 / 注册表访问全部归位服务端主线程。
 * <p>
 * 设计取舍（与 DeviceTerminalActionQueue 一致）：
 * <ul>
 * <li><b>自宿主 drain</b>：本类自带 @SubscribeEvent ServerTickEvent(END) 监听
 * （1.7.10 该事件挂 FML 总线），{@code CommonProxy.preInit} 与既有队列同址注册一次</li>
 * <li><b>主线程复验</b>：逐条复验在线（playerNetServerHandler 非空）、存活与手持
 * WirelessEnergyTap 后才执行；冷却消费失败静默丢弃（不回包，客户端等待下一轮）</li>
 * </ul>
 * 单条请求流程：
 * <ol>
 * <li>手持复验：{@code player.inventory.getCurrentItem()} 须为 WirelessEnergyTap</li>
 * <li>冷却：{@link WirelessEnergyTap#tryConsumeRevealCooldown}（4 tick，失败丢弃）</li>
 * <li>取 {@link WirelessNodeRegistry} snapshot → 以玩家当前位置为 center →
 * {@link WirelessNodeIndexCodec#selectWithinRadius}（半径²=4096 即 64 格，limit=256）</li>
 * <li>probe 三态：未加载区块 UNLOADED（跳过不修剪）；TE 非法 / 六面无本 mod 覆盖板 INVALID
 * （进修剪集）；记录 type 仍在 VALID；仅存另一 type → 先 unregister + register 实际 type
 * 自愈（同机双类型角落）再按实际 type VALID</li>
 * <li>首次按 tap 当前 OutputMode（true=动力）筛选；15 秒内再次扫描显示两类节点</li>
 * <li>修剪集回传 {@link WirelessNodeRegistry#prune}</li>
 * <li>组 {@link PacketSyncNodeReveal}（服务端 {@code world.getTotalWorldTime()}，
 * durationTicks=300）回发；入选为空也照发（客户端语义 = 清缓存）</li>
 * </ol>
 */
public final class NodeRevealRequestQueue {

    /** 待处理请求队列（仅缓存玩家引用，主线程 drain 时再复验在线/存活/手持） */
    private static final ConcurrentLinkedQueue<EntityPlayerMP> PENDING = new ConcurrentLinkedQueue<>();

    /**
     * 待注册覆盖板挂起清单（v1.7.21 节点显形修复：旧世界 NBT 恢复自愈）。
     * <p>
     * {@code GTswnCoverWirelessBase} 构造（含每次 TE 反序列化/区块加载）时经
     * {@code enqueueNodeRegistration} 挂起，由 {@link #onServerTick} 的 ServerTick(END)
     * drain 消费——此时区块加载窗口已结束，{@code worldObj} 已赋值、{@code isRemote}
     * 可判定，注册入口 {@code registerIntoNodeIndex()} 幂等。复用本类既有 END 挂点，
     * 不新增独立全局 tick 订阅；空清单早退保证零常驻开销。客户端条目在挂起入口
     * （effective-side 判定）即被丢弃，不会进入本清单。drain 时世界仍未就绪的条目
     * 有界重试（{@link #MAX_REGISTRATION_ATTEMPTS} 次后告警丢弃，等待下次区块加载重治愈），
     * 不做无限静默丢弃。
     */
    private static final ConcurrentLinkedQueue<PendingNodeRegistration> PENDING_NODE_REGISTRATIONS = new ConcurrentLinkedQueue<>();

    /** 世界未就绪条目的重试上限（每个 ServerTick(END) drain 计 1 次；100 次 ≈ 5s 后告警丢弃） */
    private static final int MAX_REGISTRATION_ATTEMPTS = 100;

    /** 显形半径平方（64 格，闭边界），与 {@link WirelessNodeIndexCodec#selectWithinRadius} 的 radiusSq 参数对应 */
    public static final long REVEAL_RADIUS_SQ = 4096L;

    /** 单次显形入选上限（防异常大索引下包体暴涨） */
    public static final int REVEAL_LIMIT = 256;

    /** 显形持续时长（tick，15s），服务端权威下发给客户端渲染缓存 */
    public static final int REVEAL_DURATION_TICKS = 300;

    private static final Map<EntityPlayerMP, LinkNodeRevealSession> REVEAL_SESSIONS = new WeakHashMap<>();

    private NodeRevealRequestQueue() {}

    /**
     * 注册自宿主 tick 监听（CommonProxy.preInit 与 {@code DeviceTerminalActionQueue.register()} 同址调用一次）。
     */
    public static void register() {
        FMLCommonHandler.instance()
            .bus()
            .register(new NodeRevealRequestQueue());
    }

    /** Netty 线程入队：包 11 显形请求（只带玩家引用，世界访问留给主线程） */
    public static void enqueue(EntityPlayerMP player) {
        if (player != null) {
            PENDING.add(player);
        }
    }

    /**
     * 覆盖板节点注册挂起入口（v1.7.21）：{@code GTswnCoverWirelessBase} 构造时调用
     * （服务端守卫已在调用侧完成）。null 防御后仅入队，世界访问留给 ServerTick(END) 主线程。
     */
    public static void enqueueNodeRegistration(GTswnCoverWirelessBase cover) {
        if (cover != null) {
            PENDING_NODE_REGISTRATIONS.add(new PendingNodeRegistration(cover));
        }
    }

    /** ServerTickEvent END phase 自宿主排空（主线程语义） */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        long now = System.currentTimeMillis();
        REVEAL_SESSIONS.entrySet()
            .removeIf(
                entry -> entry.getKey().playerNetServerHandler == null || entry.getKey().isDead
                    || !entry.getValue()
                        .isActive(entry.getKey().worldObj, now));
        drainNodeRegistrations();
        drain();
    }

    /**
     * 主线程排空待注册覆盖板清单：空清单早退（常态零开销）→ 逐条回调
     * {@code GTswnCoverWirelessBase.registerIntoNodeIndex()}（内部完成 world/tile 判空与
     * isRemote 守卫，注册幂等，{@code false}=world 未就绪）；单条异常仅记日志丢弃；
     * 未就绪条目有界重试（每轮 drain 计 1 次），超限告警丢弃。
     */
    private static void drainNodeRegistrations() {
        if (PENDING_NODE_REGISTRATIONS.isEmpty()) {
            return;
        }
        // 本轮 world 未就绪条目先收集、循环结束后统一回队：避免同轮 poll→add→poll 自旋
        List<PendingNodeRegistration> deferred = null;
        PendingNodeRegistration pending;
        while ((pending = PENDING_NODE_REGISTRATIONS.poll()) != null) {
            try {
                if (pending.cover.registerIntoNodeIndex()) {
                    continue;
                }
                if (++pending.attempts < MAX_REGISTRATION_ATTEMPTS) {
                    if (deferred == null) {
                        deferred = new ArrayList<>();
                    }
                    deferred.add(pending);
                } else {
                    GTSimpleWirelessNetwork.LOG.warn("[NodeRevealRequestQueue] 覆盖板节点注册重试超限（world 未就绪），丢弃等待下次区块加载自愈");
                }
            } catch (Throwable t) {
                GTSimpleWirelessNetwork.LOG.error("[NodeRevealRequestQueue] 覆盖板节点注册异常（丢弃该条，继续后续）", t);
            }
        }
        if (deferred != null) {
            PENDING_NODE_REGISTRATIONS.addAll(deferred);
        }
    }

    /** 主线程逐条处理：复验在线 + 存活 + 手持后执行；单条异常仅记日志丢弃 */
    private static void drain() {
        EntityPlayerMP player;
        while ((player = PENDING.poll()) != null) {
            if (player.playerNetServerHandler == null || player.isDead || !player.isEntityAlive()) {
                continue;
            }
            try {
                handle(player);
            } catch (Throwable t) {
                GTSimpleWirelessNetwork.LOG.error("[NodeRevealRequestQueue] 显形请求处理异常（丢弃该条，继续后续）", t);
            }
        }
    }

    /** 主线程处理单条显形请求（校验 → 冷却 → 查询 → 过滤 → 回发） */
    private static void handle(EntityPlayerMP player) {
        ItemStack held = player.inventory.getCurrentItem();
        if (held == null || !(held.getItem() instanceof WirelessEnergyTap)) {
            // 发送者已不手持链路终端：静默丢弃
            return;
        }
        WirelessEnergyTap tap = (WirelessEnergyTap) held.getItem();
        if (!PacketTerminalGesture.consumeReveal(player)) return;
        boolean cooldownPass = tap.tryConsumeRevealCooldown(player);
        if (!cooldownPass) {
            // 4 tick 冷却中：静默丢弃（不回包）
            return;
        }
        // 首次按当前模式显形；同一世界的 15 秒窗口内再次扫描同时显形两类节点。
        byte wantedType = WirelessEnergyTap.getOutputModeStatic(held) ? WirelessNodeRegistry.TYPE_DYNAMO
            : WirelessNodeRegistry.TYPE_ENERGY;

        World world = player.worldObj;
        long now = System.currentTimeMillis();
        LinkNodeRevealSession previous = REVEAL_SESSIONS.get(player);
        boolean revealAll = previous != null && previous.isActive(world, now);
        WirelessNodeRegistry registry = WirelessNodeRegistry.get(world);
        Map<Long, Byte> snapshot = registry.snapshot(world);
        long centerPacked = WirelessNodeIndexCodec
            .pack((int) Math.floor(player.posX), (int) Math.floor(player.posY), (int) Math.floor(player.posZ));

        // 六面实际类型掩码：索引每台机器只存一种类型，显形不能因此遗漏另一类覆盖板。
        Map<Long, Byte> actualTypeMasks = new HashMap<>();
        WirelessNodeIndexCodec.RadiusSelection selection = WirelessNodeIndexCodec.selectWithinRadius(
            snapshot,
            centerPacked,
            REVEAL_RADIUS_SQ,
            REVEAL_LIMIT,
            new RevealProbe(world, registry, actualTypeMasks));

        // 修剪集回传注册表（INVALID 节点整批出册，最多一次 markDirty）
        registry.prune(world, selection.pruned);

        List<PacketSyncNodeReveal.RevealedNode> nodes = new ArrayList<>(selection.selected.size());
        for (WirelessNodeIndexCodec.SelectedNode node : selection.selected) {
            byte mask = LinkNodeRevealSession.visibleTypes(actualTypeMasks.get(node.packed), wantedType, revealAll);
            for (byte type = WirelessNodeRegistry.TYPE_ENERGY; type <= WirelessNodeRegistry.TYPE_DYNAMO; type++) {
                if ((mask & (1 << type)) == 0) continue;
                nodes.add(new PacketSyncNodeReveal.RevealedNode(node.x, node.y, node.z, type));
                if (nodes.size() >= REVEAL_LIMIT) break;
            }
            if (nodes.size() >= REVEAL_LIMIT) break;
        }

        // 空列表也照发（客户端语义 = 清缓存）；时间基准取服务端本维世界 tick
        // 客户端按玩家语言渲染扫描反馈
        REVEAL_SESSIONS.put(player, new LinkNodeRevealSession(world, now + REVEAL_DURATION_TICKS * 50L));

        if (!nodes.isEmpty()) {
            player.addChatMessage(new ChatComponentTranslation("gtswn.reveal.scan.result", nodes.size()));
        } else {
            player.addChatMessage(new ChatComponentTranslation("gtswn.reveal.scan.empty"));
        }
        GTSWNPacketHandler.NETWORK
            .sendTo(new PacketSyncNodeReveal(nodes, world.getTotalWorldTime(), REVEAL_DURATION_TICKS), player);
    }

    /**
     * 节点有效性探测器（世界/方块访问全部留在本队列侧，主线程执行）：
     * <ul>
     * <li>{@code world.blockExists}=false → UNLOADED（跳过且不修剪，区块卸载保留索引语义）</li>
     * <li>blockExists 但 TE 缺失或非 GT5U ICoverable → INVALID</li>
     * <li>六面扫描（{@code getCoverAtSide}）无 {@link GTswnCoverWirelessBase} → INVALID；
     * 有：记录 type 仍在 → VALID；只有另一 type → unregister + register(实际 type) 自愈后
     * 按实际 type VALID；六面实际类型记入 actualTypeMasks，供显形筛选两类</li>
     * </ul>
     */
    private static final class RevealProbe implements WirelessNodeIndexCodec.Probe {

        private final World world;
        private final WirelessNodeRegistry registry;
        private final Map<Long, Byte> actualTypeMasks;

        RevealProbe(World world, WirelessNodeRegistry registry, Map<Long, Byte> actualTypeMasks) {
            this.world = world;
            this.registry = registry;
            this.actualTypeMasks = actualTypeMasks;
        }

        @Override
        public WirelessNodeIndexCodec.ProbeResult probe(long packed, int x, int y, int z, byte type) {
            if (!world.blockExists(x, y, z)) {
                return WirelessNodeIndexCodec.ProbeResult.UNLOADED;
            }
            TileEntity tileEntity = world.getTileEntity(x, y, z);
            if (!(tileEntity instanceof ICoverable)) {
                return WirelessNodeIndexCodec.ProbeResult.INVALID;
            }
            ICoverable coverable = (ICoverable) tileEntity;
            // 扫描全部六面，保留能源与动力两类覆盖板，避免同机双类型遗漏。
            byte mask = 0;
            for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
                Cover cover = coverable.getCoverAtSide(side);
                if (cover instanceof GTswnCoverWirelessBase) {
                    mask |= 1 << ((GTswnCoverWirelessBase) cover).nodeTypeId();
                }
            }
            if (mask == 0) {
                return WirelessNodeIndexCodec.ProbeResult.INVALID;
            }
            actualTypeMasks.put(packed, mask);
            if ((mask & (1 << type)) == 0) {
                byte actualType = (mask & (1 << WirelessNodeRegistry.TYPE_ENERGY)) != 0
                    ? WirelessNodeRegistry.TYPE_ENERGY
                    : WirelessNodeRegistry.TYPE_DYNAMO;
                registry.unregister(world, x, y, z);
                registry.register(world, x, y, z, actualType);
            }
            return WirelessNodeIndexCodec.ProbeResult.VALID;
        }
    }

    /**
     * 待注册条目（覆盖板引用 + 已重试次数）。{@code attempts} 仅由主线程 drain 读写，
     * 经 {@link ConcurrentLinkedQueue} 的发布语义对入队线程可见。
     */
    private static final class PendingNodeRegistration {

        private final GTswnCoverWirelessBase cover;
        private int attempts;

        private PendingNodeRegistration(GTswnCoverWirelessBase cover) {
            this.cover = cover;
        }
    }
}
