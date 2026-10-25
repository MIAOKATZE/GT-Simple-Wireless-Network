package com.miaokatze.gtswn.client.engineering;

import java.util.HashMap;
import java.util.Map;

import com.miaokatze.gtswn.client.engineering.desktop.WidgetEffects;

/** Render-owned clocks; one allocation per visible card, released on page change or close. */
final class DashboardMotion {

    static final class State {

        final GridDialMotion grid = new GridDialMotion();
        double phase;
        long frame, lastNanos;
        boolean started;

        void advance(long now, WidgetEffects effects) {
            if (started && effects.animated) {
                double dt = Math.max(0, Math.min(.1, (now - lastNanos) / 1_000_000_000.0));
                phase = (phase + dt * .18 * effects.speed) % 1;
            }
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
