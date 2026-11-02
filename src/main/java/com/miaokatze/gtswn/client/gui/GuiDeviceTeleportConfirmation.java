package com.miaokatze.gtswn.client.gui;

import java.util.Locale;

import net.minecraft.util.StatCollector;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.factory.ClientGUI;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.SecondaryPanel;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.Dialog;
import com.miaokatze.gtswn.common.device.DeviceTerminalTeleport.PaymentSource;
import com.miaokatze.gtswn.common.gui.GtswnMuiTheme;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketDeviceTeleportRequest;
import com.miaokatze.gtswn.network.PacketSyncDeviceTeleportQuote;

/** One native modal shared by the list, world marker and any existing caller of the server travel service. */
public final class GuiDeviceTeleportConfirmation extends GtswnMuiScreen {

    private static State active;

    private GuiDeviceTeleportConfirmation(State state) {
        super("device_teleport_confirmation", 320, 174);
        state.owner = this;
        populate(getMainPanel(), state);
    }

    /** Called on the client thread, after the proxy verifies the receiving world is still current. */
    public static void open(PacketSyncDeviceTeleportQuote quote) {
        if (!quote.valid()) return;
        if (quote.finished) {
            if (active != null && active.quote.token == quote.token && active.panel != null) active.panel.closeIfOpen();
            return;
        }
        ModularScreen current = ModularScreen.getCurrent();
        if (active != null && active.owner == current) {
            if (active.quote.token == quote.token) return;
            if (active.panel != null && active.panel.isClosing()) {
                active.pendingQuote = quote;
                return;
            }
            active.quote = quote;
            active.submitted = false;
            if (active.modal != null) active.modal.openPanel();
            return;
        }
        State state = new State(quote);
        active = state;
        if (current == null) ClientGUI.open(new GuiDeviceTeleportConfirmation(state));
        else {
            state.owner = current;
            state.modal = new SecondaryPanel(current.getMainPanel(), (parent, player) -> {
                ModularPanel panel = GtswnMuiTheme.fit(
                    new Dialog<>("device_teleport_confirmation").size(320, 174)
                        .background(GtswnMuiTheme.PANEL));
                populate(panel, state);
                return panel;
            }, true);
            state.modal.openPanel();
            current.registerFrameUpdateListener(state.panel, state::update, true);
        }
    }

    public static void clear() {
        active = null;
    }

    private static void populate(ModularPanel panel, State state) {
        state.panel = panel;
        panel.child(
            IKey.lang("gtswn.device.teleport.confirm.title")
                .color(GtswnMuiTheme.TEXT_TITLE)
                .asWidget()
                .pos(12, 12)
                .size(296, 16)
                .alignment(Alignment.Center));
        panel.child(
            IKey.dynamic(
                () -> StatCollector
                    .translateToLocalFormatted("gtswn.device.teleport.confirm.target", state.quote.targetKey))
                .asWidget()
                .pos(12, 40)
                .size(296, 16)
                .alignment(Alignment.CenterLeft));
        panel.child(
            IKey.dynamic(
                () -> StatCollector.translateToLocal(
                    "gtswn.device.teleport.confirm.source." + state.quote.source.name()
                        .toLowerCase(Locale.ROOT)))
                .asWidget()
                .pos(12, 62)
                .size(296, 16)
                .alignment(Alignment.CenterLeft));
        panel.child(
            IKey.dynamic(
                () -> StatCollector.translateToLocalFormatted(
                    "gtswn.device.teleport.confirm.amount."
                        + (state.quote.source == PaymentSource.EXPERIENCE ? "experience"
                            : state.quote.source == PaymentSource.FREE ? "free" : "anchor"),
                    state.quote.amount))
                .asWidget()
                .pos(12, 84)
                .size(296, 16)
                .alignment(Alignment.CenterLeft));
        panel.child(
            IKey.dynamic(
                () -> state.quote.changed ? StatCollector.translateToLocal("gtswn.device.teleport.confirm.changed")
                    : "")
                .color(GtswnMuiTheme.STATE_OFFLINE)
                .asWidget()
                .pos(12, 106)
                .size(296, 24)
                .alignment(Alignment.TopLeft));
        // A cached MUI2 panel must receive release before it closes: press callbacks run
        // before the framework records mouse.held, and a closed panel misses that release.
        panel.child(
            new ButtonWidget<>().name("device.teleport.confirm")
                .pos(12, 140)
                .size(140, 22)
                .background(GtswnMuiTheme.BUTTON)
                .hoverBackground(GtswnMuiTheme.BUTTON_HOVER)
                .overlay(IKey.lang("gui.yes"))
                .onMouseTapped(button -> {
                    if (button != 0) return false;
                    state.submit(true, panel);
                    return true;
                }));
        panel.child(
            new ButtonWidget<>().name("device.teleport.cancel")
                .pos(168, 140)
                .size(140, 22)
                .background(GtswnMuiTheme.BUTTON)
                .hoverBackground(GtswnMuiTheme.BUTTON_HOVER)
                .overlay(IKey.lang("gui.cancel"))
                .onMouseTapped(button -> {
                    if (button != 0) return false;
                    state.submit(false, panel);
                    return true;
                }));
        panel.onCloseAction(() -> state.submit(false, null));
    }

    private static final class State {

        PacketSyncDeviceTeleportQuote quote;
        PacketSyncDeviceTeleportQuote pendingQuote;
        ModularScreen owner;
        ModularPanel panel;
        SecondaryPanel modal;
        boolean submitted;

        State(PacketSyncDeviceTeleportQuote quote) {
            this.quote = quote;
        }

        void submit(boolean accepted, ModularPanel panel) {
            if (submitted) return;
            submitted = true;
            GTSWNPacketHandler.NETWORK.sendToServer(PacketDeviceTeleportRequest.confirm(quote.token, accepted));
            if (!accepted && panel != null) panel.closeIfOpen();
        }

        void update() {
            if (pendingQuote == null || panel.isOpen()) return;
            quote = pendingQuote;
            pendingQuote = null;
            submitted = false;
            modal.openPanel();
        }
    }
}
