package com.miaokatze.gtswn.common.util;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 「Netty 入队 → 主线程 drain」玩家请求队列泛型基类（O2-16，原 SWN-OPT-09 方案 Q1）。
 * <p>
 * {@code QuantumTerminalRequestQueue} / {@code WirelessEURequestQueue} 此前各持一份
 * 同构骨架（全仓仅有的 2 处 ConcurrentLinkedQueue）：待处理队列 + 同玩家去重门 +
 * ServerTickEvent 主线程排空。差异仅三点——payload 类型、入队审计钩子、异常策略——
 * 前两点由本基类与钩子承载，<b>异常处理整体留子类 process</b>：量子终端的
 * 「装配异常回发离线快照兜底」是 v1.6.1-4b 防卡死契约，基类不得统一吞异常。
 * <p>
 * 1.7.10 无 ServerThreadUtil / MinecraftServer.addScheduledTask（1.8+ 才有），
 * 排空点由子类调用方（ServerTickEvent END phase）保证主线程语义。
 */
public abstract class PlayerRequestQueue<P> {

    /** 待处理请求队列（仅缓存 payload，主线程 drain 时再校验在线/手持） */
    private final ConcurrentLinkedQueue<P> pending = new ConcurrentLinkedQueue<>();

    /** 同一玩家同时只保留一个待处理请求，避免客户端轮询在服务器卡顿时形成请求洪峰。 */
    private final ConcurrentHashMap<EntityPlayerMP, Boolean> pendingPlayers = new ConcurrentHashMap<>();

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
     * 命名为 offer/drainAll 以避开子类静态门面（enqueue/drain）与本实例方法的同名隐藏冲突。
     */
    protected void offer(EntityPlayerMP player, P payload) {
        if (player != null && pendingPlayers.putIfAbsent(player, Boolean.TRUE) == null) {
            onEnqueued(payload);
            pending.add(payload);
        }
    }

    /** 主线程逐条处理；本 tick 内排空当前快照 */
    protected void drainAll() {
        P payload;
        while ((payload = pending.poll()) != null) {
            pendingPlayers.remove(playerOf(payload));
            process(payload);
        }
    }
}
