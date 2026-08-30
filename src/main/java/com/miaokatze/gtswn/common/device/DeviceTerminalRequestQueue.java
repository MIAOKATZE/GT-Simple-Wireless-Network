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
public final class DeviceTerminalRequestQueue extends PlayerRequestQueue<EntityPlayerMP> {

    private static final DeviceTerminalRequestQueue INSTANCE = new DeviceTerminalRequestQueue();

    /** 每页条目数上限（包 9 分页） */
    private static final int PAGE_SIZE = 128;

    /** 每次排水最多发送页数（当前页 + 预取一页；v1.7.14 G5 增量分页） */
    private static final int PAGES_PER_BURST = 2;

    /** 玩家 UUID → (终端 UUID → 发送进度)（仅主线程访问；登出清理防残留） */
    private static final Map<UUID, Map<UUID, SentState>> SENT = new HashMap<>();

    private DeviceTerminalRequestQueue() {}

    /** 单终端发送进度：批次版本 + 已发页数 + 整批发完标记（版本跳过的零发包判据） */
    private static final class SentState {

        /** 已发送批次的数据版本（-1=从未发送） */
        long version = -1L;

        /** 该版本批次已发送页数（下一批自此页接力，不重头发） */
        int pagesSent;

        /** 整批发完标记（true 时同版本请求直接跳过，语义等价旧 LAST_SENT 版本跳过） */
        boolean complete;
    }

    /** Netty 线程入队（仅缓存玩家引用，主线程 drain 时再校验在线/持终端） */
    public static void enqueue(EntityPlayerMP player) {
        INSTANCE.offer(player, player);
    }

    /** 主线程逐条处理（由 DeviceSampleScheduler 每 tick 调用） */
    public static void drain() {
        INSTANCE.drainAll();
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
    protected EntityPlayerMP playerOf(EntityPlayerMP player) {
        return player;
    }

    @Override
    protected void process(EntityPlayerMP player) {
        try {
            if (player.playerNetServerHandler == null) {
                return;
            }
            ItemStack stack = findTerminalStack(player);
            if (stack == null) {
                // 发送者已不持有终端（GUI 关闭后残留轮询）：静默丢弃
                return;
            }
            UUID terminalId = ItemDeviceInfoTerminal.getOrCreateTerminalId(stack);
            MinecraftServer server = MinecraftServer.getServer();
            World overworld = server == null ? null : server.worldServerForDimension(0);
            if (overworld == null) {
                return;
            }
            DeviceTerminalDataStore store = DeviceTerminalDataStore.get(overworld);
            DeviceTerminalDataStore.TerminalData data = store.getOrCreateTerminal(terminalId);
            // 会话内新获得终端补登记活跃索引（否则采样调度跳过直到重登）
            DeviceTerminalDataStore.ensureActiveTerminal(player.getUniqueID(), terminalId);
            // 版本跳过（D5 防轮询压力）：该版本整批已发完 → 零发包返回
            SentState state = sentState(player.getUniqueID(), terminalId);
            if (state.complete && state.version == data.version) {
                return;
            }
            // 版本变化：新批次自页 0 重新增量发送（旧批次残留页由客户端按版本丢弃）
            if (state.version != data.version) {
                state.version = data.version;
                state.pagesSent = 0;
                state.complete = false;
            }
            // 条目总数与总页数（封顶 MAX_ENTRIES；计数遍历零分配，装配只分配本次将发送条目）
            int entryTotal = Math.min(countLiveEntries(data), PacketSyncDeviceTerminalData.MAX_ENTRIES);
            int pageTotal = Math.max(1, (entryTotal + PAGE_SIZE - 1) / PAGE_SIZE);
            if (state.pagesSent >= pageTotal) {
                // 防御：进度越界（理论不可达，版本重置保证）→ 按整批发完收敛
                state.complete = true;
                return;
            }
            int from = state.pagesSent * PAGE_SIZE;
            int to = Math.min(from + PAGES_PER_BURST * PAGE_SIZE, entryTotal);
            List<Entry> batch = collectEntries(data, from, to);
            // 至少发一页（空终端发页 0 空页，客户端凭整批到齐消除「...」占位改示无绑定）
            int sentPages = Math.max(1, (to + PAGE_SIZE - 1) / PAGE_SIZE);
            for (int page = from / PAGE_SIZE; page < sentPages; page++) {
                int pageFrom = Math.max(page * PAGE_SIZE, from);
                int pageTo = Math.min(page * PAGE_SIZE + PAGE_SIZE, entryTotal);
                GTSWNPacketHandler.NETWORK.sendTo(
                    new PacketSyncDeviceTerminalData(
                        terminalId,
                        data.version,
                        page,
                        pageTotal,
                        entryTotal,
                        batch.subList(pageFrom - from, pageTo - from)),
                    player);
            }
            state.pagesSent = sentPages;
            state.complete = to >= entryTotal;
        } catch (Throwable t) {
            // 装配异常：本条丢弃（客户端 GUI 等下个轮询周期重试），不中断本 tick 后续请求
            com.miaokatze.gtswn.main.GTSimpleWirelessNetwork.LOG.error("[设备终端] 请求装配异常（本条丢弃）", t);
        }
    }

    /** 取或建玩家 → 终端的发送进度（仅主线程） */
    private static SentState sentState(UUID playerId, UUID terminalId) {
        Map<UUID, SentState> perPlayer = SENT.get(playerId);
        if (perPlayer == null) {
            perPlayer = new HashMap<>();
            SENT.put(playerId, perPlayer);
        }
        SentState state = perPlayer.get(terminalId);
        if (state == null) {
            state = new SentState();
            perPlayer.put(terminalId, state);
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
                        latestSample(record),
                        record.avg,
                        record.dim,
                        record.x,
                        record.y,
                        record.z,
                        record.recipeIn,
                        record.recipeOut));
            }
            index++;
        }
        return batch;
    }

    /** FIFO 最新采样点（环形缓冲上一个写入位；无样本返回 0） */
    private static long latestSample(DeviceTerminalDataStore.MachineRecord record) {
        if (record.count <= 0) {
            return 0L;
        }
        return record.fifo[(record.idx - 1 + DeviceTerminalDataStore.FIFO_SIZE) % DeviceTerminalDataStore.FIFO_SIZE];
    }

    /** 物品栏首台设备信息终端（手持优先，其次主背包扫描） */
    private static ItemStack findTerminalStack(EntityPlayerMP player) {
        ItemStack held = player.getHeldItem();
        if (held != null && held.getItem() instanceof ItemDeviceInfoTerminal) {
            return held;
        }
        for (ItemStack stack : player.inventory.mainInventory) {
            if (stack != null && stack.getItem() instanceof ItemDeviceInfoTerminal) {
                return stack;
            }
        }
        return null;
    }
}
