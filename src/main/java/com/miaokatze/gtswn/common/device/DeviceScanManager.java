package com.miaokatze.gtswn.common.device;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import com.gtnewhorizon.gtnhlib.teams.Team;
import com.gtnewhorizon.gtnhlib.teams.TeamManager;
import com.miaokatze.gtswn.common.device.DeviceRegistryData.DeviceEntry;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 设备信息终端异步扫描管理器（实施计划 C2 / 用户修正 M2，注册于 CommonProxy.init 的 FML 总线）。
 * <p>
 * Shift+右击（{@code ItemDeviceInfoTerminal} onItemRightClick / onItemUse 服务端分支）调
 * {@link #toggleScan}：进行中 = 取消（聊天 scan.cancelled + 清理）；否则启动 20s 倒计时
 * （20/15/10/5/1 五档聊天提示，20s 档即 scan.start）。
 * <p>
 * 0 档执行（用户修正 M2 三段式）：
 * <ol>
 * <li><b>主线程快照</b>：团队成员 UUID 并集（GTNHLib TeamManager owners/officers/members，
 * NoClassDefFoundError/异常回退仅本人）+ 登记表条目副本 + 该终端现绑定副本</li>
 * <li><b>后台单线程 daemon executor</b>（静态懒建，命名 gtswn-device-scan）纯内存过滤合并：
 * registry owner∈团队 − 现绑定，截断至 {@link Config#deviceTerminalMaxMachines} 上限；
 * <b>零世界访问</b>（只做集合运算与字符串解析）</li>
 * <li><b>主线程排水</b>（ServerTickEvent END）：批量 addBinding 应用 + 聊天 scan.complete
 * （已录入 N 台（团队 M 人）），version++ 由 addBinding 内部保证</li>
 * </ol>
 * 清理路径三保险：取消（toggle 再按）/ 登出（PlayerLoggedOutEvent）/ 异常（快照与排水各自
 * try-catch 后移除状态，后台任务异常仅丢弃结果不落状态）。
 */
public final class DeviceScanManager {

    /** 倒计时六档剩余 tick（20s 档由 scan.start 提示，0 档=执行） */
    private static final int[] COUNTDOWN_TICKS = { 20 * 20, 15 * 20, 10 * 20, 5 * 20, 1 * 20, 0 };

    /** 玩家 UUID → 扫描状态（仅服务端主线程访问） */
    private static final Map<UUID, ScanState> STATES = new HashMap<>();

    /** 后台合并结果队列（后台线程生产，主线程排水消费） */
    private static final ConcurrentLinkedQueue<ScanResult> RESULTS = new ConcurrentLinkedQueue<>();

    /** 后台单线程 executor（静态懒建，daemon：不阻塞 JVM 退出） */
    private static volatile ExecutorService scanExecutor;

    private DeviceScanManager() {}

    /**
     * 注册自宿主 tick / 登出监听（CommonProxy.init 调用一次）。
     */
    public static void register() {
        FMLCommonHandler.instance()
            .bus()
            .register(new DeviceScanManager());
    }

    // ==================== 扫描开关（物品手势入口） ====================

    /**
     * 扫描开关：该玩家扫描进行中 → 取消；否则启动 20s 倒计时扫描。
     *
     * @param player     服务端玩家（仅主线程调用）
     * @param terminalId 手持终端实例 UUID（绑定数据锚点）
     */
    public static void toggleScan(EntityPlayerMP player, UUID terminalId) {
        UUID playerId = player.getUniqueID();
        ScanState state = STATES.get(playerId);
        if (state != null) {
            // 取消路径：标记作废（后台结果到达时丢弃）+ 移除状态 + 聊天确认
            state.cancelled = true;
            STATES.remove(playerId);
            sendChat(player, "gtswn.device.chat.scan.cancelled");
            return;
        }
        MinecraftServer server = MinecraftServer.getServer();
        World overworld = server == null ? null : server.worldServerForDimension(0);
        if (overworld == null) {
            return;
        }
        ScanState fresh = new ScanState(player, terminalId, overworld.getTotalWorldTime() + COUNTDOWN_TICKS[0]);
        STATES.put(playerId, fresh);
        // 20s 档提示（含性能提示文案：后台执行不卡服务器）
        sendChat(player, "gtswn.device.chat.scan.start");
    }

    // ==================== tick：倒计时推进 + 结果排水 ====================

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return;
        }
        World overworld = server.worldServerForDimension(0);
        if (overworld == null) {
            return;
        }
        if (!STATES.isEmpty()) {
            advanceCountdowns(overworld.getTotalWorldTime());
        }
        drainResults(overworld);
    }

    /** 玩家登出：清理扫描状态（后台结果到达时因状态缺失被丢弃） */
    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!event.player.worldObj.isRemote) {
            ScanState state = STATES.remove(event.player.getUniqueID());
            if (state != null) {
                state.cancelled = true;
            }
        }
    }

    /**
     * 倒计时推进：跨过未提示档位则各发一条聊天；跨过 0 档执行扫描（每状态仅一次）。
     * <p>
     * 迭代快照副本：executeScan 的异常路径会从 STATES 移除条目，避免并发修改。
     */
    private static void advanceCountdowns(long now) {
        for (ScanState state : new ArrayList<>(STATES.values())) {
            if (state.running) {
                continue;
            }
            long remaining = state.deadlineTick - now;
            while (state.thresholdIndex < COUNTDOWN_TICKS.length
                && remaining <= COUNTDOWN_TICKS[state.thresholdIndex]) {
                int threshold = COUNTDOWN_TICKS[state.thresholdIndex];
                state.thresholdIndex++;
                if (threshold == 0) {
                    executeScan(state);
                    break;
                }
                sendChat(state.player, "gtswn.device.chat.scan.countdown", threshold / 20);
            }
        }
    }

    // ==================== 0 档执行：主线程快照 → 后台过滤 ====================

    /**
     * 主线程快照（团队 UUID 并集 + 登记表副本 + 现绑定副本）后提交后台过滤。
     * 快照异常（世界 / 团队 API）→ 移除状态并记日志（异常清理路径）。
     */
    private static void executeScan(ScanState state) {
        state.running = true;
        try {
            EntityPlayerMP player = state.player;
            if (player.playerNetServerHandler == null) {
                STATES.remove(player.getUniqueID());
                return;
            }
            MinecraftServer server = MinecraftServer.getServer();
            World overworld = server.worldServerForDimension(0);
            // 团队成员并集（含本人；GTNHLib 缺失/异常回退仅本人）
            Set<UUID> team = resolveTeam(player.getUniqueID());
            // 登记表副本 / 现绑定副本（后台线程只读副本，零世界访问）
            Map<String, DeviceEntry> registry = DeviceRegistryData.get(overworld)
                .snapshotEntries();
            Set<String> bound = new LinkedHashSet<>(
                DeviceTerminalDataStore.get(overworld)
                    .getOrCreateTerminal(state.terminalId).boundKeys);
            final Set<UUID> teamCopy = team;
            final Map<String, DeviceEntry> registryCopy = registry;
            final Set<String> boundCopy = bound;
            final int capacity = Math.max(0, Config.deviceTerminalMaxMachines - boundCopy.size());
            executor().execute(() -> {
                try {
                    RESULTS.add(mergeScan(state, teamCopy, registryCopy, boundCopy, capacity));
                } catch (Throwable t) {
                    // 后台纯内存运算异常：仅丢弃结果（状态由排水侧超时无果自然残留至登出/取消清理，
                    // 此处无法安全触碰主线程 Map）
                    GTSimpleWirelessNetwork.LOG.error("[设备终端] 后台扫描合并异常（结果丢弃）", t);
                }
            });
        } catch (Throwable t) {
            GTSimpleWirelessNetwork.LOG.error("[设备终端] 扫描快照异常（扫描终止）", t);
            STATES.remove(state.player.getUniqueID());
        }
    }

    /**
     * 后台线程过滤合并（纯内存）：registry owner∈团队 − 现绑定 → 按键排序 → 截断至上限。
     * <b>本方法不得访问任何世界 / WorldSavedData / 实体对象</b>。
     */
    private static ScanResult mergeScan(ScanState state, Set<UUID> team, Map<String, DeviceEntry> registry,
        Set<String> bound, int capacity) {
        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<String, DeviceEntry> entry : registry.entrySet()) {
            if (candidates.size() >= capacity) {
                break;
            }
            String key = entry.getKey();
            if (bound.contains(key) || !team.contains(entry.getValue().owner)) {
                continue;
            }
            int[] pos = parseKey(key);
            if (pos == null) {
                continue;
            }
            String localName = entry.getValue().localName;
            candidates.add(new Candidate(key, localName == null ? "" : localName, pos[0], pos[1], pos[2], pos[3]));
        }
        candidates.sort((a, b) -> a.key.compareTo(b.key));
        return new ScanResult(state, team.size(), candidates);
    }

    // ==================== 结果排水（主线程） ====================

    /**
     * 主线程排空后台合并结果：状态仍有效（未取消/未登出/未被新扫描顶替）才批量应用。
     */
    private static void drainResults(World overworld) {
        ScanResult result;
        while ((result = RESULTS.poll()) != null) {
            ScanState state = STATES.get(result.state.player.getUniqueID());
            if (state == null || state != result.state || state.cancelled) {
                // 取消 / 登出 / 新扫描顶替：丢弃结果
                continue;
            }
            EntityPlayerMP player = state.player;
            STATES.remove(player.getUniqueID());
            if (player.playerNetServerHandler == null) {
                continue;
            }
            try {
                DeviceTerminalDataStore store = DeviceTerminalDataStore.get(overworld);
                int added = 0;
                for (Candidate candidate : result.candidates) {
                    if (store.addBinding(
                        state.terminalId,
                        candidate.key,
                        candidate.localName,
                        candidate.dim,
                        candidate.x,
                        candidate.y,
                        candidate.z) == DeviceTerminalDataStore.AddResult.SUCCESS) {
                        added++;
                    }
                }
                sendChat(player, "gtswn.device.chat.scan.complete", added, result.teamSize);
            } catch (Throwable t) {
                GTSimpleWirelessNetwork.LOG.error("[设备终端] 扫描结果应用异常（结果丢弃）", t);
            }
        }
    }

    // ==================== 工具 ====================

    /** 后台单线程 executor（懒建；daemon 线程 gtswn-device-scan） */
    private static ExecutorService executor() {
        ExecutorService executor = scanExecutor;
        if (executor == null) {
            synchronized (DeviceScanManager.class) {
                executor = scanExecutor;
                if (executor == null) {
                    executor = Executors.newSingleThreadExecutor(runnable -> {
                        Thread thread = new Thread(runnable, "gtswn-device-scan");
                        thread.setDaemon(true);
                        return thread;
                    });
                    scanExecutor = executor;
                }
            }
        }
        return executor;
    }

    /**
     * 团队成员 UUID 并集（含本人）：GTNHLib {@code TeamManager.getTeamByPlayer} →
     * owners + officers + members；无队伍 / GTNHLib 缺失 / 任何异常 → 仅本人。
     */
    private static Set<UUID> resolveTeam(UUID playerId) {
        Set<UUID> team = new HashSet<>();
        team.add(playerId);
        try {
            Team playerTeam = TeamManager.getTeamByPlayer(playerId);
            if (playerTeam != null) {
                team.addAll(playerTeam.getOwners());
                team.addAll(playerTeam.getOfficers());
                team.addAll(playerTeam.getMembers());
            }
        } catch (Throwable t) {
            // NoClassDefFoundError（GTNHLib 缺失）/ 运行时异常：回退仅本人
            GTSimpleWirelessNetwork.LOG.warn("[设备终端] 团队解析失败，回退仅本人扫描", t);
        }
        return team;
    }

    /** 解析机器键 {@code dim:x:y:z} → [dim,x,y,z]；格式坏返回 null（纯字符串运算，任意线程安全） */
    private static int[] parseKey(String key) {
        if (key == null) {
            return null;
        }
        String[] parts = key.split(":");
        if (parts.length != 4) {
            return null;
        }
        try {
            return new int[] { Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                Integer.parseInt(parts[3]) };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 服务端本地化聊天 */
    private static void sendChat(EntityPlayerMP player, String key, Object... args) {
        if (player != null && player.playerNetServerHandler != null) {
            player.addChatMessage(new ChatComponentText(StatCollector.translateToLocalFormatted(key, args)));
        }
    }

    // ==================== 数据结构 ====================

    /** 单玩家扫描状态（仅主线程访问；cancelled 供结果排水作废判定） */
    private static final class ScanState {

        /** 发起玩家（主线程引用；应用前排水复验在线） */
        final EntityPlayerMP player;

        /** 目标终端实例 UUID */
        final UUID terminalId;

        /** 0 档执行时刻（overworld 总 tick） */
        final long deadlineTick;

        /** 下一个未提示档位下标（0=20s 档已由 scan.start 提示，从 1 起） */
        int thresholdIndex = 1;

        /** 已提交后台（倒计时结束） */
        boolean running;

        /** 已取消（结果到达即丢弃） */
        boolean cancelled;

        ScanState(EntityPlayerMP player, UUID terminalId, long deadlineTick) {
            this.player = player;
            this.terminalId = terminalId;
            this.deadlineTick = deadlineTick;
        }
    }

    /** 后台合并产出（进入主线程结果队列的载荷） */
    private static final class ScanResult {

        /** 关联扫描状态（排水侧校验未取消/未顶替） */
        final ScanState state;

        /** 团队人数（聊天报告参数） */
        final int teamSize;

        /** 候选机器（已过滤排序截断，纯内存副本） */
        final List<Candidate> candidates;

        ScanResult(ScanState state, int teamSize, List<Candidate> candidates) {
            this.state = state;
            this.teamSize = teamSize;
            this.candidates = candidates;
        }
    }

    /** 候选机器（键 + 显示名 + 坐标，addBinding 载荷） */
    private static final class Candidate {

        final String key;
        final String localName;
        final int dim;
        final int x;
        final int y;
        final int z;

        Candidate(String key, String localName, int dim, int x, int y, int z) {
            this.key = key;
            this.localName = localName;
            this.dim = dim;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
