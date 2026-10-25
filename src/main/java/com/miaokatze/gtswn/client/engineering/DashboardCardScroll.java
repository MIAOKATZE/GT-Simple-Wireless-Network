package com.miaokatze.gtswn.client.engineering;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import com.miaokatze.gtswn.client.engineering.desktop.DashboardTheme;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetSpec;
import com.miaokatze.miaokatzecore.api.ui.component.UiEvent;
import com.miaokatze.miaokatzecore.api.ui.component.UiNode;
import com.miaokatze.miaokatzecore.api.ui.component.UiRect;
import com.miaokatze.miaokatzecore.api.ui.component.UiStyle;

/** Transient card scrolling. Core owns clipping, scroll offsets and pointer capture. */
final class DashboardCardScroll {

    static final int TOP = 25, BOTTOM = 5, BAR = 8;
    private static final Map<String, State> states = new HashMap<>();
    private static final UiStyle CLEAR = new UiStyle(new UiStyle.Paint(0, 0, 0));

    private static final class State {

        final ScrollModel model = new ScrollModel();
        String signature = "";
        UiNode body, extent;
        double scale = 1, grab;
        boolean dragging;
    }

    private DashboardCardScroll() {}

    static void attach(UiNode card, WidgetSpec widget, BooleanSupplier forceZoom) {
        State s = states.computeIfAbsent(widget.id, id -> new State());
        UiNode body = UiNode.panel("card-scroll-" + widget.id)
            .style(CLEAR)
            .scrollable(true)
            .visible(false);
        UiNode extent = UiNode.panel("card-scroll-extent-" + widget.id)
            .style(CLEAR)
            .pointerTransparent(true);
        s.body = body;
        s.extent = extent;
        s.dragging = false;
        body.add(extent);
        body.on(UiEvent.Type.SCROLL, e -> {
            if (forceZoom.getAsBoolean() || body.maxScrollY() <= 0) return;
            // Desktop canvas wheel is positive upwards. Keep consuming at either edge.
            body.scrollTo(body.scrollY() - Math.signum(e.wheel) * 29 * s.scale);
            remember(s);
            e.consume();
        })
            .on(UiEvent.Type.DOWN, e -> {
                if (e.button != 0 || body.maxScrollY() <= 0 || e.localX() < body.bounds().width - BAR * s.scale) return;
                UiRect thumb = thumb(body);
                s.grab = thumb.contains(e.x, e.y) ? e.y - thumb.y : thumb.height / 2;
                s.dragging = true;
                drag(s, e.y);
                e.capturePointer();
                e.consume();
            })
            .on(UiEvent.Type.MOVE, e -> {
                if (!s.dragging) return;
                drag(s, e.y);
                e.consume();
            })
            .on(UiEvent.Type.UP, e -> {
                if (!s.dragging) return;
                drag(s, e.y);
                s.dragging = false;
                e.releasePointer();
                e.consume();
            })
            .on(UiEvent.Type.CANCEL, e -> {
                s.dragging = false;
                e.consume();
            })
            .onDraw((node, renderer) -> {
                if (node.maxScrollY() <= 0) return;
                DashboardTheme theme = FactoryDashboard.scrollTheme();
                UiRect b = node.bounds();
                double bar = BAR * s.scale;
                renderer.fill(new UiRect(b.right() - bar, b.y, bar, b.height), theme.track);
                UiRect thumb = thumb(node);
                renderer.fill(
                    new UiRect(thumb.x + s.scale, thumb.y, Math.max(s.scale, thumb.width - 2 * s.scale), thumb.height),
                    s.dragging || node.hovered() ? theme.positive : theme.muted);
            });
        card.add(body);
    }

    static void update(UiNode card, WidgetSpec widget, double scale, boolean enabled) {
        State s = states.get(widget.id);
        if (s == null || s.body == null || s.body.disposed()) return;
        String signature = widget.kind + ":"
            + widget.presentation
            + ":"
            + widget.fieldsConfigured
            + ":"
            + widget.fields
            + ":"
            + widget.displayColumns
            + ":"
            + widget.device
            + ":"
            + widget.screen
            + ":"
            + widget.resource
            + ":"
            + FactoryDashboard.scrollSource(widget);
        if (!signature.equals(s.signature)) {
            s.signature = signature;
            s.model.set(0);
        }
        s.scale = scale;
        int width = DesktopCardGeometry.canonical(widget).w;
        int viewport = Math.max(0, DesktopCardGeometry.canonical(widget).h - TOP - BOTTOM);
        int content = FactoryDashboard.scrollContentHeight(widget, width - BAR);
        s.model.measure(content, viewport, s.model.offset());
        s.body.bounds(0, TOP * scale, width * scale, viewport * scale)
            .visible(enabled && s.model.maximum() > 0);
        s.extent.bounds(0, 0, Math.max(0, (width - BAR) * scale), content * scale);
        // Layout establishes Core's current maximum before restoring the retained offset.
    }

    static void restore(UiNode card, WidgetSpec widget) {
        State s = states.get(widget.id);
        if (s != null && s.body != null && !s.body.disposed()) s.body.scrollTo(s.model.offset() * s.scale);
    }

    static int offset(String id) {
        State s = states.get(id);
        return s == null ? 0 : s.model.offset();
    }

    static boolean overflowing(String id) {
        State s = states.get(id);
        return s != null && s.model.maximum() > 0;
    }

    static void retain(Iterable<WidgetSpec> widgets) {
        Set<String> live = new HashSet<>();
        for (WidgetSpec widget : widgets) live.add(widget.id);
        states.keySet()
            .retainAll(live);
    }

    static void clear() {
        states.clear();
    }

    /** Returns the complete card node id, including its card- prefix, or the empty string. */
    static String cardId(UiNode node) {
        for (; node != null; node = node.parent()) {
            if (node.id.startsWith("card-") && !node.id.startsWith("card-scroll-")) return node.id;
        }
        return "";
    }

    private static UiRect thumb(UiNode node) {
        UiRect b = node.bounds();
        double content = b.height + node.maxScrollY();
        // Track thickness follows its screen-space viewport scale, supplied by the extent width.
        double width = Math.max(
            1,
            b.width - node.children()
                .get(0)
                .bounds().width);
        double height = Math.min(b.height, Math.max(width * 1.5, b.height * b.height / Math.max(1, content)));
        double y = b.y + (b.height - height) * node.scrollY() / Math.max(1, node.maxScrollY());
        return new UiRect(b.right() - width, y, width, height);
    }

    private static void drag(State s, double y) {
        UiRect b = s.body.bounds(), thumb = thumb(s.body);
        double travel = Math.max(1, b.height - thumb.height);
        s.body.scrollTo((y - b.y - s.grab) / travel * s.body.maxScrollY());
        remember(s);
    }

    private static void remember(State s) {
        s.model.set((int) Math.round(s.body.scrollY() / s.scale));
    }
}
