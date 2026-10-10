package com.miaokatze.gtswn.client;

/** 无游戏依赖的蓄力时序，首次tick建立服务端意图，随后保持20 tick。 */
public final class TerminalChargeProgress {

    public enum Step {
        IDLE,
        BEGIN,
        HOLD,
        COMPLETE
    }

    private int ticks = -1;

    public void start() {
        ticks = 0;
    }

    public void cancel() {
        ticks = -1;
    }

    public Step tick(boolean valid) {
        if (!valid) cancel();
        if (ticks < 0) return Step.IDLE;
        if (ticks++ == 0) return Step.BEGIN;
        if (ticks <= 20) return Step.HOLD;
        cancel();
        return Step.COMPLETE;
    }
}
