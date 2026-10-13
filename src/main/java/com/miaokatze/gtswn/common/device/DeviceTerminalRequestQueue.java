package com.miaokatze.gtswn.common.device;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;

import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.common.util.PlayerRequestQueue;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

/**
 * 设备信息终端数据请求的待处理队列（实施计划 C3 / D1-D5，仿
 * {@code common.quantum.QuantumTerminalRequestQueue}）。
 * <p>
 * 包 8（{@code PacketRequestDeviceTerminalData}）Handler 运行在 Netty 网络线程，
 * 仅入队 (player)；由 {@link DeviceSampleScheduler} 的 ServerTickEvent（END phase）
 * 每_tick 在主线程 drain：
 * <ol>
 * <li>解析发送者物品栏首台设备信息终端（手持优先）→ terminalId；无终端静默丢弃</li>
 * <li><b>版本跳过</b>（防轮询压力，D5）：该玩家该终端当前版本整批已发完则零发包跳过；
 * 包 10 动作排水后调 {@link #invalidate} 失效发送进度，下轮请求立即回发</li>
 * <li><b>增量分页</b>（v1.7.14 G5）：否则按绑定序装配条目（瞬时 = FIFO 最新采样点，
 * 平均 = 60 点均值）每次排水最多 {@value #PAGES_PER_BURST} 页（当前页 + 预取一页），
 * 后续轮询 / 客户端滚轮触发的追加请求自 {@code pagesSent} 接力续页直到整批发完——
 * 满配 1024 条不再「版本一变即 8 页全量连发」，打开 GUI 首轮响应 ≤2 页</li>
 * </ol>
 * 去重骨架（同玩家同时只保留一个待处理请求）由 {@link PlayerRequestQueue} 基类承载。
 */
public final class DeviceTerminalRequestQueue extends PlayerRequestQueue<DeviceTerminalRequestQueue.Request> {

    private static final DeviceTerminalRequestQueue INSTANCE = new DeviceTerminalRequestQueue();

    /** 每页条目数上限（包 9 分页） */
    private static final int PAGE_SIZE = 128;

    /** 每次排水最多发送页数（当前页 + 预取一页；v1.7.14 G5 增量分页） */
    private static final int PAGES_PER_BURST = 2;

    /** 玩家 UUID → (终端 UUID → 发送进度)（仅主线程访问；登出清理防残留） */
    private static final Map<UUID, SentState> SENT = new HashMap<>();
    private static final long MIN_REQUEST_INTERVAL_TICKS = 5;
    private static final long SNAPSHOT_IDLE_TTL_TICKS = 600;
    private static long lastPurgeTick = Long.MIN_VALUE;

    /** 本类异常日志的冷却窗口（JVM 单调纳秒，30 秒）：抑制刷屏同时保留「仍在复发」信号。 */
    private static final long LOG_COOLDOWN_NANOS = 30_000_000_000L;

    /** 上次输出时刻（{@link System#nanoTime} 原点，0 表示尚未输出过 ⇒ 首次必然输出）。 */
    private static long lastLoggedNanos = 0L;

    private DeviceTerminalRequestQueue() {}

    /** 单终端发送进度：批次版本 + 已发页数 + 整批发完标记（版本跳过的零发包判据） */
    static final class SentState {

        UUID terminalId;
        long lastProcessed = Long.MIN_VALUE;
        long lastSent = Long.MIN_VALUE;
        int entryTotal;
        int pageTotal;

        // One bounded immutable delivery per player; live sampling cannot restart its continuation.
        List<Entry> snapshot;

        /** 已发送批次的数据版本（-1=从未发送） */
        long version = -1L;

        /** 该版本批次已发送页数（下一批自此页接力，不重头发） */
        int pagesSent;

        /** 整批发完标记（true 时同版本请求直接跳过，语义等价旧 LAST_SENT 版本跳过） */
        boolean complete;

        void prepareSnapshot(DeviceTerminalDataStore.TerminalData data, long knownVersion, boolean cacheComplete,
            boolean supplied) {
            if (snapshot == null) {
                capture(data);
                return;
            }
            boolean acknowledged = !supplied || (cacheComplete && knownVersion == version);
            if (complete && acknowledged && version != data.version) {
                capture(data);
            } else if (supplied && (knownVersion != version || (complete && !cacheComplete))) {
                // Cache loss or a missing page replays this delivery, never a moving live generation.
                pagesSent = 0;
                complete = false;
            }
        }

        private void capture(DeviceTerminalDataStore.TerminalData data) {
            entryTotal = Math.min(countLiveEntries(data), PacketSyncDeviceTerminalData.MAX_ENTRIES);
            snapshot = java.util.Collections.unmodifiableList(collectEntries(data, 0, entryTotal));
            pageTotal = Math.max(1, (entryTotal + PAGE_SIZE - 1) / PAGE_SIZE);
            version = data.version;
            pagesSent = 0;
            complete = false;
        }
    }

    /** Netty 线程入队（仅缓存玩家引用，主线程 drain 时再校验在线/持终端） */
    public static void enqueue(EntityPlayerMP player) {
        enqueue(player, null, -1, false, false);
    }

    public static final class Request {

        final EntityPlayerMP player;
        final UUID terminalId;
        final long knownVersion;
        final boolean complete, hasClientState;

        Request(EntityPlayerMP player, UUID id, long version, boolean complete, boolean hasClientState) {
            this.player = player;
            terminalId = id;
            knownVersion = version;
            this.complete = complete;
            this.hasClientState = hasClientState;
        }
    }

    public static void enqueue(EntityPlayerMP player, UUID terminalId, long version, boolean complete,
        boolean hasClientState) {
        INSTANCE.offer(player, new Request(player, terminalId, version, complete, hasClientState));
    }

    /** 主线程逐条处理（由 DeviceSampleScheduler 每 tick 调用） */
    public static void drain() {
        World overworld = net.minecraftforge.common.DimensionManager.getWorld(0);
        if (overworld != null) {
            long now = overworld.getTotalWorldTime();
            if (lastPurgeTick == Long.MIN_VALUE || now < lastPurgeTick || now - lastPurgeTick >= 20) {
                lastPurgeTick = now;
                SENT.values()
                    .removeIf(
                        state -> state.lastProcessed != Long.MIN_VALUE
                            && (now < state.lastProcessed || now - state.lastProcessed >= SNAPSHOT_IDLE_TTL_TICKS));
            }
        }
        INSTANCE.drainAtMost(64);
    }

    public static void clear() {
        SENT.clear();
        lastPurgeTick = Long.MIN_VALUE;
    }

    /** 包 10 动作应用后失效该玩家全部发送进度（下轮请求排水自页 0 立即回发） */
    public static void invalidate(EntityPlayerMP player) {
        SENT.remove(player.getUniqueID());
    }

    /** 玩家登出：清理发送进度缓存（DeviceSampleScheduler 登出监听调用） */
    public static void onPlayerLoggedOut(UUID playerId) {
        SENT.remove(playerId);
    }

    @Override
    protected EntityPlayerMP playerOf(Request request) {
        return request.player;
    }

    @Override
    protected void process(Request request) {
        EntityPlayerMP player = request.player;
        try {
            if (player.playerNetServerHandler == null) {
                return;
            }
            MinecraftServer server = MinecraftServer.getServer();
            if (server == null || server.getConfigurationManager()
                .func_152612_a(player.getCommandSenderName()) != player) return;
            ItemStack stack = findTerminalStack(player, request.terminalId);
            if (stack == null) {
                SENT.remove(player.getUniqueID());
                // 发送者已不持有终端（GUI 关闭后残留轮询）：静默丢弃
                return;
            }
            UUID terminalId = ItemDeviceInfoTerminal.ensureTerminalIdSynced(player, stack);
            World overworld = server == null ? null : server.worldServerForDimension(0);
            if (overworld == null) {
                return;
            }
            SentState state = sentState(player.getUniqueID(), terminalId);
            long now = overworld.getTotalWorldTime();
            if (state.lastProcessed != Long.MIN_VALUE && now >= state.lastProcessed
                && now - state.lastProcessed < MIN_REQUEST_INTERVAL_TICKS) return;
            state.lastProcessed = now;
            DeviceTerminalSettings.get(overworld)
                .sync(player);
            com.miaokatze.gtswn.network.DeviceTerminalVisualActionQueue.refresh(player, terminalId);
            DeviceTerminalDataStore store = DeviceTerminalDataStore.get(overworld);
            DeviceTerminalDataStore.TerminalData data = store.getOrCreateTerminal(terminalId);
            // 会话内新获得终端补登记活跃索引（否则采样调度跳过直到重登）
            DeviceTerminalDataStore.ensureActiveTerminal(player.getUniqueID(), terminalId);
            // 版本跳过（D5 防轮询压力）：该版本整批已发完 → 零发包返回
            state.prepareSnapshot(data, request.knownVersion, request.complete, request.hasClientState);
            if (state.complete) {
                long keepAliveTicks = Math.max(20, Math.min(600, Config.deviceSampleIntervalSeconds * 20));
                if (state.lastSent == Long.MIN_VALUE || now < state.lastSent
                    || now - state.lastSent >= keepAliveTicks) {
                    GTSWNPacketHandler.NETWORK.sendTo(
                        new PacketSyncDeviceTerminalData(
                            terminalId,
                            state.version,
                            -1,
                            state.pageTotal,
                            state.entryTotal,
                            java.util.Collections.emptyList()),
                        player);
                    state.lastSent = now;
                }
                return;
            }
            // 版本变化：新批次自页 0 重新增量发送（旧批次残留页由客户端按版本丢弃）
            int entryTotal = state.entryTotal;
            int pageTotal = state.pageTotal;
            if (state.pagesSent >= pageTotal) {
                // 防御：进度越界（理论不可达，版本重置保证）→ 按整批发完收敛
                state.complete = true;
                return;
            }
            int from = state.pagesSent * PAGE_SIZE;
            int to = Math.min(from + PAGES_PER_BURST * PAGE_SIZE, entryTotal);
            List<Entry> batch = state.snapshot.subList(from, to);
            // 至少发一页（空终端发页 0 空页，客户端凭整批到齐消除「...」占位改示无绑定）
            int sentPages = Math.max(1, (to + PAGE_SIZE - 1) / PAGE_SIZE);
            for (int page = from / PAGE_SIZE; page < sentPages; page++) {
                int pageFrom = Math.max(page * PAGE_SIZE, from);
                int pageTo = Math.min(page * PAGE_SIZE + PAGE_SIZE, entryTotal);
                GTSWNPacketHandler.NETWORK.sendTo(
                    new PacketSyncDeviceTerminalData(
                        terminalId,
                        state.version,
                        page,
                        pageTotal,
                        entryTotal,
                        batch.subList(pageFrom - from, pageTo - from)),
                    player);
            }
            state.pageTotal = pageTotal;
            state.entryTotal = entryTotal;
            state.lastSent = now;
            state.pagesSent = sentPages;
            state.complete = to >= entryTotal;
        } catch (Throwable t) {
            // 装配异常：本条丢弃（客户端 GUI 等下个轮询周期重试），不中断本 tick 后续请求
            long now = System.nanoTime();
            if (lastLoggedNanos == 0L || now - lastLoggedNanos >= LOG_COOLDOWN_NANOS) {
                lastLoggedNanos = now;
                com.miaokatze.gtswn.main.GTSimpleWirelessNetwork.LOG.error("[设备终端] 请求装配异常（本条丢弃）", t);
            }
        }
    }

    /** 取或建玩家 → 终端的发送进度（仅主线程） */
    private static SentState sentState(UUID playerId, UUID terminalId) {
        SentState state = SENT.get(playerId);
        if (state == null || !terminalId.equals(state.terminalId)) {
            state = new SentState();
            state.terminalId = terminalId;
            SENT.put(playerId, state);
        }
        return state;
    }

    /** 有记录的有效条目数（绑定键遍历，零分配；boundKeys 与 records 理论对称，缺记录防御跳过） */
    private static int countLiveEntries(DeviceTerminalDataStore.TerminalData data) {
        int count = 0;
        for (String key : data.boundKeys) {
            if (data.records.get(key) != null) {
                count++;
            }
        }
        return count;
    }

    /** 装配 [from, to) 下标区间的条目（绑定序；瞬时 = FIFO 最新采样点，平均 = 均值字段） */
    private static List<Entry> collectEntries(DeviceTerminalDataStore.TerminalData data, int from, int to) {
        List<Entry> batch = new ArrayList<>(Math.max(0, to - from));
        int index = 0;
        for (String key : data.boundKeys) {
            if (index >= to) {
                break;
            }
            DeviceTerminalDataStore.MachineRecord record = data.records.get(key);
            if (record == null) {
                continue;
            }
            if (index >= from) {
                batch.add(
                    new Entry(
                        key,
                        record.name,
                        (byte) record.state,
                        record.powerType,
                        record.instIn,
                        record.instOut,
                        record.inAvg,
                        record.outAvg,
                        record.dim,
                        record.x,
                        record.y,
                        record.z,
                        record.recipeIn,
                        record.recipeOut,
                        record.metaTileId,
                        record.energyKind,
                        record.evidenceKind,
                        record.linkNodeType,
                        record.steamInstIn,
                        record.steamInstOut,
                        record.steamAvgIn,
                        record.steamAvgOut,
                        record.displayName,
                        record.nameColor,
                        record.nameBold,
                        record.capabilityMask,
                        record.recognitionMode));
            }
            index++;
        }
        return batch;
    }

    /** 物品栏首台设备信息终端（手持优先，其次主背包扫描） */
    private static ItemStack findTerminalStack(EntityPlayerMP player, UUID requested) {
        ItemStack held = player.getHeldItem();
        if (held != null && held.getItem() instanceof ItemDeviceInfoTerminal
            && (requested == null || requested.equals(ItemDeviceInfoTerminal.readTerminalId(held)))) {
            return held;
        }
        for (ItemStack stack : player.inventory.mainInventory) {
            if (stack != null && stack.getItem() instanceof ItemDeviceInfoTerminal
                && (requested == null || requested.equals(ItemDeviceInfoTerminal.readTerminalId(stack)))) {
                return stack;
            }
        }
        return null;
    }
}
