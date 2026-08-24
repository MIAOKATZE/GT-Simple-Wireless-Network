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
 * <li><b>版本跳过</b>（防轮询压力）：该玩家该终端已收版本 == {@code TerminalData.version}
 * 则跳过重发；包 10 动作排水后调 {@link #invalidate} 失效已收版本，下轮请求立即回发</li>
 * <li>否则按绑定序装配条目（瞬时 = FIFO 最新采样点，平均 = 60 点均值）分页全量发送包 9</li>
 * </ol>
 * 去重骨架（同玩家同时只保留一个待处理请求）由 {@link PlayerRequestQueue} 基类承载。
 */
public final class DeviceTerminalRequestQueue extends PlayerRequestQueue<EntityPlayerMP> {

    private static final DeviceTerminalRequestQueue INSTANCE = new DeviceTerminalRequestQueue();

    /** 每页条目数上限（包 9 分页） */
    private static final int PAGE_SIZE = 128;

    /** 玩家 UUID → (终端 UUID → 已收版本)（仅主线程访问；登出清理防残留） */
    private static final Map<UUID, Map<UUID, Long>> LAST_SENT = new HashMap<>();

    private DeviceTerminalRequestQueue() {}

    /** Netty 线程入队（仅缓存玩家引用，主线程 drain 时再校验在线/持终端） */
    public static void enqueue(EntityPlayerMP player) {
        INSTANCE.offer(player, player);
    }

    /** 主线程逐条处理（由 DeviceSampleScheduler 每 tick 调用） */
    public static void drain() {
        INSTANCE.drainAll();
    }

    /** 包 10 动作应用后失效该玩家全部已收版本（下轮请求排水立即回发） */
    public static void invalidate(EntityPlayerMP player) {
        LAST_SENT.remove(player.getUniqueID());
    }

    /** 玩家登出：清理已收版本缓存（DeviceSampleScheduler 登出监听调用） */
    public static void onPlayerLoggedOut(UUID playerId) {
        LAST_SENT.remove(playerId);
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
            // 版本跳过：已收版本 == 当前版本 → 不重发（D5 防轮询压力）
            Map<UUID, Long> sent = LAST_SENT.get(player.getUniqueID());
            if (sent != null && Long.valueOf(data.version)
                .equals(sent.get(terminalId))) {
                return;
            }
            // 装配条目（绑定序；瞬时 = FIFO 最新采样点，平均 = 均值字段）
            List<Entry> entries = new ArrayList<>();
            for (String key : data.boundKeys) {
                DeviceTerminalDataStore.MachineRecord record = data.records.get(key);
                if (record == null) {
                    continue;
                }
                entries.add(
                    new Entry(
                        key,
                        record.name,
                        (byte) record.state,
                        latestSample(record),
                        record.avg,
                        record.dim,
                        record.x,
                        record.y,
                        record.z,
                        record.recipeStr));
            }
            int entryTotal = Math.min(entries.size(), PacketSyncDeviceTerminalData.MAX_ENTRIES);
            int pageTotal = Math.max(1, (entryTotal + PAGE_SIZE - 1) / PAGE_SIZE);
            for (int page = 0; page < pageTotal; page++) {
                int from = page * PAGE_SIZE;
                int to = Math.min(from + PAGE_SIZE, entryTotal);
                GTSWNPacketHandler.NETWORK.sendTo(
                    new PacketSyncDeviceTerminalData(
                        terminalId,
                        data.version,
                        page,
                        pageTotal,
                        entryTotal,
                        entries.subList(from, to)),
                    player);
            }
            if (sent == null) {
                sent = new HashMap<>();
                LAST_SENT.put(player.getUniqueID(), sent);
            }
            sent.put(terminalId, data.version);
        } catch (Throwable t) {
            // 装配异常：本条丢弃（客户端 GUI 等下个轮询周期重试），不中断本 tick 后续请求
            com.miaokatze.gtswn.main.GTSimpleWirelessNetwork.LOG.error("[设备终端] 请求装配异常（本条丢弃）", t);
        }
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
