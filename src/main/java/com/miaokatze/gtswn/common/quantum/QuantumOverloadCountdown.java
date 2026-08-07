package com.miaokatze.gtswn.common.quantum;

import java.util.HashMap;
import java.util.Map;

/**
 * 量子网络频道超限爆炸倒计时（v1.6.19）。
 * <p>
 * 检测到 used &gt; total 时启动 3 分钟倒计时，在剩余 3/2/1 分钟与 10 秒处各公告一次；
 * 期间频道恢复即取消。状态按锚点维度+坐标共享，同一锚点的多个量子节点共用一份倒计时。
 * 仅服务端主线程调用；服务器切换时由外部调用 {@link #clear()} 清理。
 */
public final class QuantumOverloadCountdown {

    /** 倒计时总长：3 分钟 = 180 秒 = 3600 tick */
    private static final long GRACE_TICKS = 3600L;

    /** 10 秒 = 200 tick */
    private static final long TICKS_10S = 200L;

    /** 1 分钟 = 1200 tick */
    private static final long TICKS_1MIN = 1200L;

    /** 2 分钟 = 2400 tick */
    private static final long TICKS_2MIN = 2400L;

    private static final Map<AnchorKey, State> STATES = new HashMap<>();

    private QuantumOverloadCountdown() {}

    /** 每次检查的结果（公告粒度） */
    public enum Result {
        /** 未超限且无倒计时在跑 */
        IDLE,
        /** 刚启动倒计时（剩余 3 分钟） */
        STARTED,
        /** 剩余 2 分钟（首次跨过该阈值） */
        ANNOUNCE_2MIN,
        /** 剩余 1 分钟（首次跨过该阈值） */
        ANNOUNCE_1MIN,
        /** 剩余 10 秒（首次跨过该阈值） */
        ANNOUNCE_10S,
        /** 倒计时进行中，无需公告 */
        WAITING,
        /** 倒计时到期，应爆炸 */
        EXPLODE,
        /** 频道已恢复，倒计时取消 */
        CANCELLED
    }

    /**
     * 按锚点检查一次超限状态（主线程）。
     *
     * @param key        锚点坐标键（调用方持有常驻实例复用，避免高频分配）
     * @param worldTick  当前世界 tick
     * @param overloaded 本次采样是否超限（used &gt; total）
     * @return 需要执行的公告/动作结果
     */
    public static Result check(AnchorKey key, long worldTick, boolean overloaded) {
        if (!overloaded) {
            if (STATES.remove(key) != null) {
                return Result.CANCELLED;
            }
            return Result.IDLE;
        }
        State state = STATES.get(key);
        if (state == null) {
            STATES.put(key, new State(worldTick + GRACE_TICKS, 0));
            return Result.STARTED;
        }
        long remaining = state.deadlineTick - worldTick;
        if (remaining <= 0L) {
            STATES.remove(key);
            return Result.EXPLODE;
        }
        int level = remaining <= TICKS_10S ? 3 : remaining <= TICKS_1MIN ? 2 : remaining <= TICKS_2MIN ? 1 : 0;
        if (level > state.lastAnnounceLevel) {
            state.lastAnnounceLevel = level;
            return level == 3 ? Result.ANNOUNCE_10S : level == 2 ? Result.ANNOUNCE_1MIN : Result.ANNOUNCE_2MIN;
        }
        return Result.WAITING;
    }

    /** 服务器切换/测试时清理运行期状态 */
    public static void clear() {
        STATES.clear();
    }

    private static final class State {

        private final long deadlineTick;
        private int lastAnnounceLevel;

        private State(long deadlineTick, int lastAnnounceLevel) {
            this.deadlineTick = deadlineTick;
            this.lastAnnounceLevel = lastAnnounceLevel;
        }
    }
}
