package com.miaokatze.gtswn.client.engineering;

/** Player-owned discovery state. Existing accessories qualify without requiring an equipment change. */
final class FactoryEntryHintState {

    private boolean completed;
    private boolean firstPending;
    private boolean equipped;

    FactoryEntryHintState(boolean completed) {
        this.completed = completed;
    }

    void observe(String current) {
        equipped = current != null;
        if (!completed && equipped) firstPending = true;
    }

    boolean visible() {
        return equipped && firstPending;
    }

    boolean firstPending() {
        return firstPending;
    }

    /** Accepted factory binding use confirms discovery only while an accessory is still equipped. */
    boolean confirmBinding() {
        if (completed || !equipped) return false;
        completed = true;
        firstPending = false;
        return true;
    }
}
