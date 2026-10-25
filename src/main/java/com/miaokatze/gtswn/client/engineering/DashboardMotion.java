package com.miaokatze.gtswn.client.engineering;

import java.util.HashMap;
import java.util.Map;

import com.miaokatze.gtswn.client.engineering.desktop.WidgetEffects;

/** Render-owned clocks; one allocation per visible card, released on page change or close. */
final class DashboardMotion {

    static final class State {

        final GridDialMotion grid = new GridDialMotion();
        private FactoryChartGeometry.Bars bars;
        private double[] barValues;
        private long[] barTicks;
        private int barWidth;
        double phase, velocity;
        long frame, lastNanos;
        boolean started;

        FactoryChartGeometry.Bars bars(double[] values, long[] ticks, int width) {
            if (bars == null || barValues != values || barTicks != ticks || barWidth != width) {
                bars = new FactoryChartGeometry.Bars(values, ticks, width);
                barValues = values;
                barTicks = ticks;
                barWidth = width;
            }
            return bars;
        }

        void advance(long now, WidgetEffects effects) {
            if (started && effects.animated && effects.speed > 0) {
                double dt = Math.max(0, Math.min(.1, (now - lastNanos) / 1_000_000_000.0));
                double target = .18 * effects.speed;
                double decay = Math.exp(-5 * dt);
                double distance = target * dt + (velocity - target) * (1 - decay) / 5;
                velocity = target + (velocity - target) * decay;
                phase = (phase + distance) % 1;
            }
            if (!effects.animated || effects.speed == 0) velocity = 0;
            started = true;
            lastNanos = now;
        }
    }

    private final Map<String, State> states = new HashMap<>();
    private long frame, now;

    void begin(long now) {
        this.now = now;
        frame++;
    }

    State card(String id, WidgetEffects effects) {
        State state = states.get(id);
        if (state == null) {
            state = new State();
            states.put(id, state);
        }
        if (state.frame != frame) state.advance(now, effects);
        state.frame = frame;
        return state;
    }

    void end() {
        states.values()
            .removeIf(state -> state.frame != frame);
    }

    void clear() {
        states.clear();
    }

    int size() {
        return states.size();
    }

    long now() {
        return now;
    }
}
