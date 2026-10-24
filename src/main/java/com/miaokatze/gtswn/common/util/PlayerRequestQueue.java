package com.miaokatze.gtswn.common.util;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

/**
 * 「Netty 入队 → 主线程 drain」玩家请求队列泛型基类（O2-16，原 SWN-OPT-09 方案 Q1）。
 * <p>
 * {@code QuantumTerminalRequestQueue} / {@code WirelessEURequestQueue} 此前各持一份
 * 同构骨架：待处理队列 + 同玩家去重门 +
 * ServerTickEvent 主线程排空。差异仅三点——payload 类型、入队审计钩子、异常策略——
 * 前两点由本基类与钩子承载，<b>异常处理整体留子类 process</b>：量子终端的
 * 「装配异常回发离线快照兜底」是 v1.6.1-4b 防卡死契约，基类不得统一吞异常。
 * <p>
 * 1.7.10 无 ServerThreadUtil / MinecraftServer.addScheduledTask（1.8+ 才有），
 * 排空点由子类调用方（ServerTickEvent END phase）保证主线程语义。
 */
public abstract class PlayerRequestQueue<P> {

    private static final int MAX_PENDING = 1024;

    /** 待处理请求队列（仅缓存 payload，主线程 drain 时再校验在线/手持） */
    private final ArrayDeque<P> pending = new ArrayDeque<>();

    /** 同一玩家同时只保留一个待处理请求，避免客户端轮询在服务器卡顿时形成请求洪峰。 */
    private final Set<EntityPlayerMP> pendingPlayers = Collections.newSetFromMap(new IdentityHashMap<>());

    /** 从 payload 取请求玩家（drain 出队后用于解除去重门） */
    protected abstract EntityPlayerMP playerOf(P payload);

    /** 入队成功（已过玩家去重门）后的钩子：性能审计计数等，默认无操作 */
    protected void onEnqueued(P payload) {}

    /**
     * 主线程处理单条请求。
     * <p>
     * 异常处理策略整体由子类定义：终端侧装配异常回发离线快照兜底，EU 侧掉线/格式异常静默丢弃。
     */
    protected abstract void process(P payload);

    /**
     * Netty 线程入队（仅缓存 payload，主线程 drain 时再校验在线/手持）。
     * <p>
     * 准入和去重门在同一锁内更新；满队列拒绝新请求，由客户端下轮查询重试。
     */
    protected void offer(EntityPlayerMP player, P payload) {
        synchronized (pending) {
            if (player == null || payload == null || pending.size() >= MAX_PENDING || pendingPlayers.contains(player))
                return;
            pendingPlayers.add(player);
            pending.addLast(payload);
            onEnqueued(payload);
        }
    }

    /** Forget the departing connection, without removing a replacement player's pending request. */
    public final void discardPlayer(EntityPlayerMP player) {
        synchronized (pending) {
            pending.removeIf(payload -> playerOf(payload) == player);
            pendingPlayers.remove(player);
        }
    }

    protected final void clearPending() {
        synchronized (pending) {
            pending.clear();
            pendingPlayers.clear();
        }
    }

    /** Stops after a fixed number of requests even if Netty keeps replenishing the queue. */
    protected void drainAtMost(int budget) {
        for (int i = 0; i < budget; i++) {
            P payload;
            synchronized (pending) {
                payload = pending.pollFirst();
                if (payload == null) return;
                pendingPlayers.remove(playerOf(payload));
            }
            process(payload);
        }
    }

    /** Main-thread identity check: a retained player reference does not prove the connection is still current. */
    public static boolean isOnline(EntityPlayerMP player) {
        MinecraftServer server = MinecraftServer.getServer();
        return player != null && !player.isDead
            && player.playerNetServerHandler != null
            && player.playerNetServerHandler.netManager.isChannelOpen()
            && server != null
            && server.getConfigurationManager()
                .func_152612_a(player.getCommandSenderName()) == player;
    }
}
