package com.miaokatze.gtswn.client.engineering;

import java.util.Objects;

/** Player-owned discovery state. World changes only establish a new equipment baseline. */
final class FactoryEntryHintState {

    static final long REMINDER_MILLIS = 6_000;
    static final long COOLDOWN_MILLIS = 60_000;
    private boolean completed;
    private boolean firstPending;
    private boolean equipped;
    private String equipment;
    private long reminderUntil;
    private long nextReminder;

    FactoryEntryHintState(boolean completed) {
        this.completed = completed;
    }

    void observe(String current, long now, boolean baseline) {
        boolean changed = !Objects.equals(equipment, current);
        equipment = current;
        equipped = current != null;
        if (!completed && equipped) firstPending = true;
        if (baseline) {
            reminderUntil = 0;
            return;
        }
        if (completed && equipped && changed && now >= nextReminder) {
            reminderUntil = now + REMINDER_MILLIS;
            nextReminder = now + COOLDOWN_MILLIS;
        }
    }

    boolean visible(long now) {
        return equipped && (firstPending || now < reminderUntil);
    }

    boolean firstPending() {
        return firstPending;
    }

    /** Only a successfully acquired factory camera confirms discovery. */
    boolean opened(long now) {
        if (!completed && !equipped && !firstPending) return false;
        boolean changed = !completed;
        completed = true;
        firstPending = false;
        reminderUntil = 0;
        nextReminder = now + COOLDOWN_MILLIS;
        return changed;
    }
}
