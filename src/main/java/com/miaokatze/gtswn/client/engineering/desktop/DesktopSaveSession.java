package com.miaokatze.gtswn.client.engineering.desktop;

/** An explicit save checkpoint, independent of automatic view preference persistence. */
public final class DesktopSaveSession {

    private DesktopLayout saved;
    private java.util.Properties signature;

    public DesktopSaveSession(DesktopLayout initial) {
        accept(initial);
    }

    public boolean unsaved(DesktopLayout current) {
        return !signature.equals(DesktopStore.encode(current));
    }

    public DesktopLayout saved() {
        return saved.copy();
    }

    public boolean save(DesktopStore store, String profile, DesktopLayout current, DesktopViewport view) {
        if (!store.saveLayout(profile, current, view)) return false;
        accept(current);
        return true;
    }

    public void accept(DesktopLayout current) {
        saved = current.copy();
        signature = DesktopStore.encode(current);
    }
}
