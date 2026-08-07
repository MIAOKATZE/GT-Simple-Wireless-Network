package com.miaokatze.gtswn.network;

import java.math.BigInteger;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.entity.player.EntityPlayerMP;

import gregtech.common.misc.WirelessNetworkManager;

/**
 * 无线 EU 查询请求的待处理队列（v1.6.19）。
 * <p>
 * {@code WirelessNetworkManager} 是服务端主线程维护的 HashMap，Netty 网络线程直读存在并发风险。
 * {@link PacketRequestWirelessEU.Handler} 仅入队 (player, ownerUUID)，由
 * {@code QuantumControllerEventHandler} 的 ServerTickEvent（END phase）在主线程逐条 drain：
 * 主线程查询 EU 并回发 {@link PacketResponseWirelessEU}；玩家掉线/格式异常时静默丢弃，
 * 客户端下轮轮询重试。与 {@link QuantumTerminalRequestQueue} 同一「Netty 入队 → 主线程 drain」模式。
 */
public final class WirelessEURequestQueue {

    /** 待处理请求队列：仅缓存玩家引用与拥有者 UUID，主线程 drain 时再查询回包 */
    private static final ConcurrentLinkedQueue<Request> PENDING = new ConcurrentLinkedQueue<>();

    /** 同一玩家同时只保留一个待处理请求，避免客户端轮询在服务器卡顿时形成请求洪峰 */
    private static final ConcurrentHashMap<EntityPlayerMP, Boolean> PENDING_PLAYERS = new ConcurrentHashMap<>();

    private WirelessEURequestQueue() {}

    /** Netty 线程入队（仅缓存玩家引用与拥有者 UUID，主线程 drain 时再查询回包） */
    public static void enqueue(EntityPlayerMP player, String ownerUUID) {
        if (player != null && ownerUUID != null && PENDING_PLAYERS.putIfAbsent(player, Boolean.TRUE) == null) {
            PENDING.add(new Request(player, ownerUUID));
        }
    }

    /** 主线程逐条处理；本 tick 内排空当前快照 */
    public static void drain() {
        Request req;
        while ((req = PENDING.poll()) != null) {
            PENDING_PLAYERS.remove(req.player);
            try {
                if (req.player.playerNetServerHandler == null) continue;
                BigInteger eu = WirelessNetworkManager.getUserEU(UUID.fromString(req.ownerUUID));
                GTSWNPacketHandler.NETWORK.sendTo(new PacketResponseWirelessEU(eu.toString()), req.player);
            } catch (Throwable t) {
                // 玩家掉线/格式异常：静默丢弃，客户端下轮轮询
            }
        }
    }

    private static final class Request {

        private final EntityPlayerMP player;
        private final String ownerUUID;

        private Request(EntityPlayerMP player, String ownerUUID) {
            this.player = player;
            this.ownerUUID = ownerUUID;
        }
    }
}
