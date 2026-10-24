package com.miaokatze.gtswn.network;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;

import com.miaokatze.gtswn.common.device.DeviceTerminalDataStore;
import com.miaokatze.gtswn.common.device.DeviceTerminalRecognitionMode;
import com.miaokatze.gtswn.common.device.DeviceTerminalRequestQueue;
import com.miaokatze.gtswn.common.device.DeviceTerminalSettings;
import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Server-thread terminal actions. Pending actions are coalesced per player/action/target and capped at
 * 1024 globally and 16 per player; each tick applies at most 64. Terminal possession and OP rights are rechecked.
 * Teleports use the server's persisted experience cost. Settings updates synchronize independently of machine pages.
 */
public final class DeviceTerminalActionQueue {

    /** 待处理动作队列（仅缓存载荷，主线程 drain 时再复验在线/持终端） */
    private static final LinkedHashMap<String, Action> PENDING = new LinkedHashMap<>();
    private static final Map<UUID, Integer> PENDING_COUNTS = new HashMap<>();
    private static final int MAX_PENDING = 1024;
    private static final int MAX_PLAYER_PENDING = 16;
    private static final int TICK_BUDGET = 64;
    private static long cycleSequence;

    private DeviceTerminalActionQueue() {}

    /**
     * 注册自宿主 tick 监听（CommonProxy.preInit 与 {@code PanelActionQueue.register()} 同址调用一次）。
     */
    public static void register() {
        FMLCommonHandler.instance()
            .bus()
            .register(new DeviceTerminalActionQueue());
    }

    /** Netty 线程入队：包 10 动作载荷（fromBytes 后线程封闭，可直接引用传递） */
    public static void enqueue(EntityPlayerMP player, byte action, byte column, byte direction, byte countMode,
        String key) {
        enqueue(player, action, column, direction, countMode, key, null);
    }

    public static void enqueue(EntityPlayerMP player, byte action, byte column, byte direction, byte countMode,
        String key, UUID terminalId) {
        enqueue(player, action, column, direction, countMode, key, terminalId, -1);
    }

    public static void enqueue(EntityPlayerMP player, byte action, byte column, byte direction, byte countMode,
        String key, UUID terminalId, int targetMetaTileId) {
        if (player == null || action < 0
            || action > PacketDeviceTerminalAction.ACTION_CYCLE_RECOGNITION
            || (action != PacketDeviceTerminalAction.ACTION_SET_TELEPORT_COST && terminalId == null)) return;
        UUID id = player.getUniqueID();
        String token = id + ":"
            + action
            + ":"
            + (action == 2 || action == 3 || action == 5 ? key : "")
            + ":"
            + terminalId;
        synchronized (PENDING) {
            if (action == PacketDeviceTerminalAction.ACTION_CYCLE_RECOGNITION) token += ":" + (++cycleSequence);
            int count = PENDING_COUNTS.getOrDefault(id, 0);
            if (!PENDING.containsKey(token)) {
                if (PENDING.size() >= MAX_PENDING || count >= MAX_PLAYER_PENDING) return;
                PENDING_COUNTS.put(id, count + 1);
            }
            PENDING.put(
                token,
                new Action(player, action, column, direction, countMode, key, terminalId, targetMetaTileId));
        }
    }

    /** ServerTickEvent END phase 自宿主排空（主线程语义） */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        drain();
    }

    /** 主线程逐条处理：复验在线 + 终端持有后应用；单条异常仅记日志丢弃 */
    private static void drain() {
        for (int processed = 0; processed < TICK_BUDGET; processed++) {
            Action action;
            synchronized (PENDING) {
                Iterator<Action> iterator = PENDING.values()
                    .iterator();
                if (!iterator.hasNext()) break;
                action = iterator.next();
                iterator.remove();
                UUID id = action.player.getUniqueID();
                int count = PENDING_COUNTS.getOrDefault(id, 1) - 1;
                if (count <= 0) PENDING_COUNTS.remove(id);
                else PENDING_COUNTS.put(id, count);
            }
            EntityPlayerMP player = action.player;
            if (!isOnline(player)) {
                continue;
            }
            try {
                apply(player, action);
            } catch (Throwable t) {
                GTSimpleWirelessNetwork.LOG.error("[DeviceTerminalActionQueue] 动作应用异常（丢弃该条，继续后续）", t);
            }
        }
    }

    /** 主线程应用单条动作 */
    public static void onLogout(UUID playerId) {
        com.miaokatze.gtswn.common.device.DeviceTerminalTeleport.onLogout(playerId);
        DeviceTerminalVisualActionQueue.onLogout(playerId);
        synchronized (PENDING) {
            Iterator<Action> iterator = PENDING.values()
                .iterator();
            while (iterator.hasNext()) {
                if (iterator.next().player.getUniqueID()
                    .equals(playerId)) iterator.remove();
            }
            PENDING_COUNTS.remove(playerId);
        }
    }

    public static void clear() {
        com.miaokatze.gtswn.common.device.DeviceTerminalTeleport.clear();
        DeviceTerminalVisualActionQueue.clear();
        synchronized (PENDING) {
            PENDING.clear();
            PENDING_COUNTS.clear();
        }
    }

    private static boolean isOnline(EntityPlayerMP player) {
        MinecraftServer server = MinecraftServer.getServer();
        return player != null && player.playerNetServerHandler != null
            && server != null
            && server.getConfigurationManager()
                .func_152612_a(player.getCommandSenderName()) == player;
    }

    private static void apply(EntityPlayerMP player, Action action) {
        if (!isOnline(player)) return;
        if (action.action == PacketDeviceTerminalAction.ACTION_SET_TELEPORT_COST) {
            if (action.key.matches("[0-9]{1,7}")) DeviceTerminalSettings.get(player.worldObj)
                .setTeleportCost(player, Integer.parseInt(action.key));
            return;
        }
        if (action.action == PacketDeviceTerminalAction.ACTION_CYCLE_RECOGNITION) {
            boolean bootstrap = new UUID(0, 0).equals(action.terminalId);
            ItemStack held = bootstrap ? player.getHeldItem() : findTerminalStack(player, action.terminalId);
            if (held == null || !(held.getItem() instanceof ItemDeviceInfoTerminal)) return;
            UUID heldId = ItemDeviceInfoTerminal.readTerminalId(held);
            if (bootstrap ? heldId != null : !action.terminalId.equals(heldId)) return;
            DeviceTerminalRecognitionMode.cycle(player, held, action.key);
            return;
        }
        ItemStack stack = findTerminalStack(player, action.terminalId);
        if (stack == null) {
            // 发送者已不持有终端：静默丢弃
            return;
        }
        UUID terminalId = ItemDeviceInfoTerminal.ensureTerminalIdSynced(player, stack);
        if (action.action == PacketDeviceTerminalAction.ACTION_UNBIND
            || action.action == PacketDeviceTerminalAction.ACTION_TELEPORT) {
            DeviceTerminalDataStore.TerminalData data = DeviceTerminalDataStore.get(player.worldObj)
                .getTerminal(terminalId);
            DeviceTerminalDataStore.MachineRecord record = data == null ? null : data.records.get(action.key);
            if (record == null || record.metaTileId != action.targetMetaTileId) {
                DeviceTerminalRequestQueue.invalidate(player);
                com.miaokatze.gtswn.common.device.DeviceTerminalTeleport.feedback(
                    player,
                    new com.miaokatze.gtswn.common.device.DeviceTerminalTeleport.Outcome(
                        com.miaokatze.gtswn.common.device.DeviceTerminalTeleport.Status.STALE_TARGET,
                        0));
                return;
            }
        }
        // 会话内新获得终端补登记活跃索引（否则采样调度跳过直到重登）
        DeviceTerminalDataStore.ensureActiveTerminal(player.getUniqueID(), terminalId);
        switch (action.action) {
            case PacketDeviceTerminalAction.ACTION_SORT: {
                // 排序偏好写回物品 NBT（服务端权威，聊天静默）
                String column = columnName(action.column);
                if (column != null) {
                    ItemDeviceInfoTerminal.setSortColumn(stack, column);
                }
                ItemDeviceInfoTerminal.setSortDirection(
                    stack,
                    action.direction == PacketDeviceTerminalAction.DIR_ASC ? ItemDeviceInfoTerminal.DIR_ASC
                        : ItemDeviceInfoTerminal.DIR_DESC);
                break;
            }
            case PacketDeviceTerminalAction.ACTION_COUNT_MODE: {
                String mode = countModeName(action.countMode);
                if (mode != null) {
                    ItemDeviceInfoTerminal.setCountMode(stack, mode);
                }
                break;
            }
            case PacketDeviceTerminalAction.ACTION_UNBIND: {
                // 解绑（找不到绑定静默；version++ 由 store 内部保证）
                if (PacketSyncDeviceTerminalData.KEY_PATTERN.matcher(action.key)
                    .matches()) {
                    DeviceTerminalDataStore.get(player.worldObj)
                        .removeBinding(terminalId, action.key);
                }
                break;
            }
            case PacketDeviceTerminalAction.ACTION_TELEPORT: {
                teleport(player, terminalId, action.key);
                break;
            }
            default:
                // 未知动作：忽略
                return;
        }
        if (action.action == PacketDeviceTerminalAction.ACTION_UNBIND
            || action.action == PacketDeviceTerminalAction.ACTION_TELEPORT
            || action.action == PacketDeviceTerminalAction.ACTION_CYCLE_RECOGNITION) {
            // 仅数据 / 界面语义变化动作失效重发（排序 / 计数法客户端已本地应用且不改 TerminalData，
            // 无条件失效会在修复批次重开条件前引发同版本整包重发 NPE，且常态下也无谓全量重同步）
            DeviceTerminalRequestQueue.invalidate(player);
        }
    }

    private static void teleport(EntityPlayerMP player, UUID terminalId, String key) {
        com.miaokatze.gtswn.common.device.DeviceTerminalTeleport.attempt(player, terminalId, key, false);
    }

    /** 排序列 byte → 物品 NBT 列名（非法返回 null） */
    private static String columnName(byte column) {
        switch (column) {
            case PacketDeviceTerminalAction.COLUMN_NAME:
                return ItemDeviceInfoTerminal.SORT_NAME;
            case PacketDeviceTerminalAction.COLUMN_STATE:
                return ItemDeviceInfoTerminal.SORT_STATE;
            case PacketDeviceTerminalAction.COLUMN_INST:
                return ItemDeviceInfoTerminal.SORT_INST;
            case PacketDeviceTerminalAction.COLUMN_AVG:
                return ItemDeviceInfoTerminal.SORT_AVG;
            case PacketDeviceTerminalAction.COLUMN_POS:
                return ItemDeviceInfoTerminal.SORT_POS;
            default:
                return null;
        }
    }

    /** 计数法 byte → 物品 NBT 计数法名（非法返回 null） */
    private static String countModeName(byte mode) {
        switch (mode) {
            case PacketDeviceTerminalAction.MODE_NORMAL:
                return ItemDeviceInfoTerminal.NUM_NORMAL;
            case PacketDeviceTerminalAction.MODE_SCIENTIFIC:
                return ItemDeviceInfoTerminal.NUM_SCIENTIFIC;
            case PacketDeviceTerminalAction.MODE_THOUSANDS:
                return ItemDeviceInfoTerminal.NUM_THOUSANDS;
            case PacketDeviceTerminalAction.MODE_VOLTAGE:
                return ItemDeviceInfoTerminal.NUM_VOLTAGE;
            default:
                return null;
        }
    }

    /** Resolve only the opened UUID across held, inventory and accessory locations; never choose a substitute. */
    private static ItemStack findTerminalStack(EntityPlayerMP player, UUID requested) {
        if (requested == null) return null;
        ItemStack held = player.getHeldItem();
        if (held != null && held.getItem() instanceof ItemDeviceInfoTerminal
            && requested.equals(ItemDeviceInfoTerminal.readTerminalId(held))) {
            return held;
        }
        for (ItemStack stack : player.inventory.mainInventory) {
            if (stack != null && stack.getItem() instanceof ItemDeviceInfoTerminal
                && requested.equals(ItemDeviceInfoTerminal.readTerminalId(stack))) {
                return stack;
            }
        }
        for (ItemStack stack : com.miaokatze.gtswn.common.baubles.TerminalBaubles.terminals(player)) {
            if (stack != null && stack.getItem() instanceof ItemDeviceInfoTerminal
                && requested.equals(ItemDeviceInfoTerminal.readTerminalId(stack))) return stack;
        }
        return null;
    }

    /** 包 10 载荷（Netty 线程捕获，主线程 drain 复验后应用） */
    private static final class Action {

        final EntityPlayerMP player;
        final byte action;
        final byte column;
        final byte direction;
        final byte countMode;
        final String key;
        final UUID terminalId;
        final int targetMetaTileId;

        Action(EntityPlayerMP player, byte action, byte column, byte direction, byte countMode, String key,
            UUID terminalId, int targetMetaTileId) {
            this.terminalId = terminalId;
            this.targetMetaTileId = targetMetaTileId;
            this.player = player;
            this.action = action;
            this.column = column;
            this.direction = direction;
            this.countMode = countMode;
            this.key = key == null ? "" : key;
        }
    }
}
