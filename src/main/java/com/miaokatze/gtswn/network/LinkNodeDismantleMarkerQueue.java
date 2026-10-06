package com.miaokatze.gtswn.network;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Alt 拆机标记待处理队列（v1.8.22，照 {@link NodeRevealRequestQueue} 的
 * 「Netty 入队 → 主线程 drain」模式）：包 14 的 Map 写入与时间基准读取全部归位
 * 服务端主线程。
 * <p>
 * 设计取舍（与 NodeRevealRequestQueue 一致）：
 * <ul>
 * <li><b>自宿主 drain</b>：本类自带 @SubscribeEvent ServerTickEvent(END) 监听
 * （1.7.10 该事件挂 FML 总线），{@code CommonProxy.preInit} 与既有队列同址注册一次</li>
 * <li><b>主线程 Map</b>：{@link #MARKED} 只由主线程 drain 写 / {@link #isMarked} 读
 * （消费方覆盖板拆机事件处理器亦在服务端主线程），无并发竞争面</li>
 * <li><b>TTL 20t</b>：值 = overworld {@code getTotalWorldTime()} + 20。客户端按下沿发包，
 * 服务端同 tick 或下一 tick drain 写入，随后的挖掘进度（扳手秒拆 / 硬方块若干 tick）
 * 内触发的 BlockEvent.BreakEvent 都能命中；20t 后自动失效，玩家松开 Alt 再挖即回到
 * 普通路径，不会把一次按键错误地放大成长期状态</li>
 * <li><b>在线复验</b>：drain 时 playerNetServerHandler 为空（已断线）的条目直接丢弃</li>
 * </ul>
 * 空 Map 常态零开销：无标记时 drain 仅一次空队列 poll + 空遍历。
 */
public final class LinkNodeDismantleMarkerQueue {

    /** 待处理请求队列（仅缓存玩家引用，主线程 drain 时再复验在线） */
    private static final ConcurrentLinkedQueue<EntityPlayerMP> PENDING = new ConcurrentLinkedQueue<>();

    /** 已标记玩家 UUID → 过期时刻（overworld 总 tick 基准 + 20）；仅服务端主线程读写 */
    private static final Map<UUID, Long> MARKED = new HashMap<>();

    /** 标记 TTL（tick）：覆盖一次左键挖掘周期，超出自动失效 */
    private static final long MARK_TTL_TICKS = 20L;

    private LinkNodeDismantleMarkerQueue() {}

    /**
     * 注册自宿主 tick 监听（CommonProxy.preInit 与 {@code NodeRevealRequestQueue.register()}
     * 同址调用一次）。
     */
    public static void register() {
        FMLCommonHandler.instance()
            .bus()
            .register(new LinkNodeDismantleMarkerQueue());
    }

    /** Netty 线程入队：包 14 Alt 拆机标记（只带玩家引用，Map 写入留给主线程） */
    public static void enqueue(EntityPlayerMP player) {
        if (player != null) {
            PENDING.add(player);
        }
    }

    /**
     * 主线程读：该玩家当前是否持有有效 Alt 拆机标记。
     * 读时惰性剪过期（双保险；drain 每 tick 也剪，正常情况此处恒干净）。
     */
    public static boolean isMarked(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        Long deadline = MARKED.get(playerId);
        if (deadline == null) {
            return false;
        }
        if (nowTicks() >= deadline) {
            pruneExpired();
            return false;
        }
        return true;
    }

    /** ServerTickEvent END phase 自宿主排空（主线程语义）：先剪过期，再写新标记 */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        drain();
    }

    /** 主线程排空：过期条目清理 → 逐条复验在线后写入 TTL 标记 */
    private static void drain() {
        pruneExpired();
        EntityPlayerMP player;
        while ((player = PENDING.poll()) != null) {
            if (player.playerNetServerHandler == null) {
                // 已断线条目：丢弃（标记无消费方，写入也无意义）
                continue;
            }
            MARKED.put(player.getUniqueID(), nowTicks() + MARK_TTL_TICKS);
        }
    }

    /** 当前时间基准：overworld 总 tick（服务端权威；世界未就绪时 0 保证只增不减的保守语义） */
    private static long nowTicks() {
        MinecraftServer server = MinecraftServer.getServer();
        World overworld = (server != null) ? server.worldServerForDimension(0) : null;
        return (overworld != null) ? overworld.getTotalWorldTime() : 0L;
    }

    /** 剪除已过期标记（仅主线程调用；空 Map 早退） */
    private static void pruneExpired() {
        if (MARKED.isEmpty()) {
            return;
        }
        long now = nowTicks();
        Iterator<Map.Entry<UUID, Long>> it = MARKED.entrySet()
            .iterator();
        while (it.hasNext()) {
            if (now >= it.next()
                .getValue()) {
                it.remove();
            }
        }
    }
}
