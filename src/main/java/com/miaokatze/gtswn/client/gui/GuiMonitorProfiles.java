package com.miaokatze.gtswn.client.gui;

import java.util.UUID;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;

import com.miaokatze.gtswn.common.monitoring.MonitorProfile;
import com.miaokatze.gtswn.network.C2SPacketMonitorProfileAction;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.S2CPacketMonitorProfiles;
import com.miaokatze.gtswn.network.S2CPacketMonitorProfiles.Entry;
import com.miaokatze.gtswn.network.telemetry.MonitorCapabilities;

/** Server-authoritative recoverable profile manager, accessible without the lost terminal. */
public final class GuiMonitorProfiles extends GuiScreen {

    private static long sequence = 1;
    private S2CPacketMonitorProfiles state;
    private UUID selected;
    private UUID webConfirmation;
    private GuiTextField reader, interval;
    private String status = "";
    private int left, top, panelWidth, pendingTicks, readerIndex;
    private C2SPacketMonitorProfileAction pending;
    private GuiButton recover, share, revoke, offline, web, refresh, importHeld, rename;

    public GuiMonitorProfiles(S2CPacketMonitorProfiles initial) {
        state = initial;
        status = initial.message;
        if (!initial.entries.isEmpty()) selected = initial.entries.get(0).id;
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal("gtswn.profiles." + key);
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        buttonList.clear();
        panelWidth = Math.max(280, Math.min(490, width - 16));
        left = (width - panelWidth) / 2;
        top = Math.max(4, (height - 228) / 2);
        int third = (panelWidth - 8) / 3, half = (panelWidth - 4) / 2;
        for (int i = 0; i < S2CPacketMonitorProfiles.PAGE_SIZE; i++)
            buttonList.add(new GuiButton(100 + i, left, top + 19 + i * 16, panelWidth, 16, ""));
        rename = new GuiButton(9, left + panelWidth - 68, top + 100, 68, 13, tr("rename"));
        buttonList.add(rename);
        reader = new GuiTextField(fontRendererObj, left, top + 115, panelWidth - 150, 18);
        reader.setMaxStringLength(36);
        interval = new GuiTextField(fontRendererObj, left + panelWidth - 66, top + 115, 66, 18);
        interval.setMaxStringLength(5);
        interval.setText("20");
        share = add(3, left, top + 137, third, tr("share"));
        revoke = add(4, left + third + 4, top + 137, third, tr("revoke"));
        recover = add(2, left + (third + 4) * 2, top + 137, third, tr("recover"));
        offline = add(5, left, top + 159, half, "");
        web = add(6, left + half + 4, top + 159, half, "");
        importHeld = add(1, left, top + 181, third, tr("import"));
        refresh = add(0, left + third + 4, top + 181, third, tr("refresh"));
        add(7, left + (third + 4) * 2, top + 181, (third - 4) / 2, "<");
        add(8, left + (third + 4) * 2 + (third + 4) / 2, top + 181, (third - 4) / 2, ">");
        if (selected() != null) interval.setText(Integer.toString(selected().interval));
        updateButtons();
    }

    private GuiButton add(int id, int x, int y, int w, String label) {
        GuiButton button = new GuiButton(id, x, y, w, 20, label);
        buttonList.add(button);
        return button;
    }

    private Entry selected() {
        for (Entry entry : state.entries) if (entry.id.equals(selected)) return entry;
        return null;
    }

    private void updateButtons() {
        Entry entry = selected();
        boolean ready = pending == null && MonitorCapabilities.clientSupports(MonitorCapabilities.PROFILES);
        for (Object item : buttonList) {
            GuiButton button = (GuiButton) item;
            if (button.id >= 100) {
                int row = button.id - 100;
                button.visible = row < state.entries.size();
                if (button.visible) {
                    Entry value = state.entries.get(row);
                    button.displayString = (value.id.equals(selected) ? "> " : "")
                        + fontRendererObj.trimStringToWidth(
                            value.name.isEmpty() ? value.id.toString()
                                .substring(0, 8) : value.name,
                            Math.max(65, panelWidth - 190))
                        + "  "
                        + tr(value.editable ? "owner" : "reader")
                        + "  "
                        + tr(
                            "status." + MonitorProfile.Status.values()[value.status].name()
                                .toLowerCase(java.util.Locale.ROOT));
                }
            } else button.enabled = ready;
            if (button.id == 7) button.enabled &= state.page > 0;
            if (button.id == 8) button.enabled &= (state.page + 1) * S2CPacketMonitorProfiles.PAGE_SIZE < state.total;
        }
        recover.enabled = ready && entry != null;
        rename.enabled = share.enabled = revoke.enabled = offline.enabled = web.enabled = ready && entry != null
            && entry.editable;
        offline.displayString = tr("offline") + ": " + tr(entry != null && entry.offline ? "on" : "off");
        web.displayString = entry != null && entry.id.equals(webConfirmation) ? tr("confirmWeb")
            : tr("web") + ": " + tr(entry != null && entry.web ? "on" : "off");
        refresh.enabled = ready || pending != null && pendingTicks >= 100;
        refresh.displayString = tr(pending != null && pendingTicks >= 100 ? "retry" : "refresh");
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id >= 100) {
            selected = state.entries.get(button.id - 100).id;
            webConfirmation = null;
            readerIndex = 0;
            interval.setText(Integer.toString(selected().interval));
            updateButtons();
            return;
        }
        if (pending != null) {
            if (button.id == 0 && pendingTicks >= 100
                && MonitorCapabilities.clientSupports(MonitorCapabilities.PROFILES)) {
                GTSWNPacketHandler.NETWORK.sendToServer(pending);
                pendingTicks = 0;
                status = tr("waiting");
            }
            return;
        }
        if (!MonitorCapabilities.clientSupports(MonitorCapabilities.PROFILES)) {
            status = tr("unsupported");
            return;
        }
        Entry entry = selected();
        if (button.id == 9 && entry != null && entry.editable) {
            mc.displayGuiScreen(new RenameScreen(this, entry));
            return;
        }
        if (button.id == 6 && entry != null && !entry.web && !entry.id.equals(webConfirmation)) {
            webConfirmation = entry.id;
            status = tr("webWarning");
            updateButtons();
            return;
        }
        webConfirmation = null;
        UUID target = null;
        int ticks = entry == null ? 20 : entry.interval;
        try {
            if (button.id == 3 || button.id == 4) {
                target = UUID.fromString(
                    reader.getText()
                        .trim());
                if (!target.toString()
                    .equalsIgnoreCase(
                        reader.getText()
                            .trim()))
                    throw new IllegalArgumentException();
            }
            if (button.id == 5) {
                ticks = Integer.parseInt(
                    interval.getText()
                        .trim());
                if (ticks < 20 || ticks > 72000) throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) {
            status = tr("invalid");
            return;
        }
        int page = state.page + (button.id == 7 ? -1 : button.id == 8 ? 1 : 0);
        int action = button.id >= 7 ? C2SPacketMonitorProfileAction.LIST : button.id;
        boolean enable = entry != null && (button.id == 5 ? !entry.offline : !entry.web);
        pending = new C2SPacketMonitorProfileAction(sequence++, action, page, selected, target, enable, ticks);
        pendingTicks = 0;
        status = tr("waiting");
        GTSWNPacketHandler.NETWORK.sendToServer(pending);
        updateButtons();
    }

    public void receive(S2CPacketMonitorProfiles response) {
        if (pending == null || response.requestId != pending.requestId) return;
        boolean imported = pending.action == C2SPacketMonitorProfileAction.IMPORT_HELD;
        pending = null;
        state = response;
        status = response.message;
        if (imported && !state.entries.isEmpty()) selected = state.entries.get(state.entries.size() - 1).id;
        if (selected() == null) selected = state.entries.isEmpty() ? null : state.entries.get(0).id;
        Entry entry = selected();
        if (entry != null) interval.setText(Integer.toString(entry.interval));
        updateButtons();
    }

    @Override
    public void updateScreen() {
        reader.updateCursorCounter();
        interval.updateCursorCounter();
        if (pending != null && ++pendingTicks == 100) status = tr("timeout");
        updateButtons();
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        super.mouseClicked(x, y, button);
        reader.mouseClicked(x, y, button);
        interval.mouseClicked(x, y, button);
    }

    @Override
    protected void keyTyped(char key, int code) {
        if (code == Keyboard.KEY_TAB && selected() != null && !selected().readerIds.isEmpty()) {
            reader.setText(
                selected().readerIds.get(readerIndex++ % selected().readerIds.size())
                    .toString());
            reader.setFocused(true);
            interval.setFocused(false);
            status = tr("readerHint");
            return;
        }
        if (code == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(null);
            return;
        }
        if (reader.textboxKeyTyped(key, code) || interval.textboxKeyTyped(key, code)) return;
        super.keyTyped(key, code);
    }

    @Override
    public void drawScreen(int x, int y, float partial) {
        drawDefaultBackground();
        drawCenteredString(
            fontRendererObj,
            tr("title") + "  "
                + (state.page + 1)
                + "/"
                + Math.max(
                    1,
                    (state.total + S2CPacketMonitorProfiles.PAGE_SIZE - 1) / S2CPacketMonitorProfiles.PAGE_SIZE),
            width / 2,
            top + 3,
            0xffffff);
        Entry entry = selected();
        String detail = entry == null ? tr("empty")
            : tr("sources") + ": " + entry.sources + "  " + tr("readers") + ": " + entry.readers;
        drawString(
            fontRendererObj,
            fontRendererObj.trimStringToWidth(detail, panelWidth - 74),
            left,
            top + 102,
            0xcccccc);
        drawString(fontRendererObj, tr("ticks"), left + panelWidth - 145, top + 121, 0xcccccc);
        reader.drawTextBox();
        interval.drawTextBox();
        if (reader.getText()
            .isEmpty() && !reader.isFocused()) drawString(fontRendererObj, tr("uuid"), left + 4, top + 121, 0x888888);
        drawString(fontRendererObj, fontRendererObj.trimStringToWidth(status, panelWidth), left, top + 205, 0xffdd88);
        drawString(
            fontRendererObj,
            fontRendererObj.trimStringToWidth(tr(webConfirmation == null ? "hint" : "webSetup"), panelWidth),
            left,
            top + 217,
            0xaaaaaa);
        super.drawScreen(x, y, partial);
        if (recover != null && x >= recover.xPosition
            && x < recover.xPosition + recover.width
            && y >= recover.yPosition
            && y < recover.yPosition + recover.height) {
            java.util.List<String> help = new java.util.ArrayList<>(
                fontRendererObj.listFormattedStringToWidth(tr("recoverNativePairing"), Math.min(300, panelWidth - 12)));
            help.addAll(
                fontRendererObj.listFormattedStringToWidth(tr("recoverNativeBinding"), Math.min(300, panelWidth - 12)));
            drawHoveringText(help, x, y, fontRendererObj);
        }
        if (x >= left && x < left + panelWidth && y >= top + 19 && y < top + 99) {
            int row = (y - top - 19) / 16;
            if (row < state.entries.size()) {
                Entry hover = state.entries.get(row);
                drawHoveringText(
                    java.util.Arrays
                        .asList(hover.name, hover.id.toString(), tr("owner") + ": " + hover.owner, tr("readerHint")),
                    x,
                    y,
                    fontRendererObj);
            }
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    private static final class RenameScreen extends GuiScreen {

        private final GuiMonitorProfiles parent;
        private final Entry profile;
        private GuiTextField name;

        RenameScreen(GuiMonitorProfiles parent, Entry profile) {
            this.parent = parent;
            this.profile = profile;
        }

        @Override
        public void initGui() {
            Keyboard.enableRepeatEvents(true);
            buttonList.clear();
            name = new GuiTextField(fontRendererObj, width / 2 - 130, height / 2 - 18, 260, 20);
            name.setMaxStringLength(MonitorProfile.MAX_NAME_LENGTH);
            name.setText(profile.name);
            name.setFocused(true);
            buttonList.add(new GuiButton(0, width / 2 - 130, height / 2 + 12, 128, 20, tr("saveName")));
            buttonList.add(new GuiButton(1, width / 2 + 2, height / 2 + 12, 128, 20, tr("cancel")));
        }

        @Override
        protected void actionPerformed(GuiButton button) {
            if (button.id == 1) {
                mc.displayGuiScreen(parent);
                return;
            }
            if (!MonitorCapabilities.clientSupports(MonitorCapabilities.PROFILES)) {
                mc.displayGuiScreen(parent);
                return;
            }
            String value;
            try {
                value = MonitorProfile.normalizeName(name.getText());
            } catch (IllegalArgumentException invalid) {
                return;
            }
            mc.displayGuiScreen(parent);
            parent.pending = C2SPacketMonitorProfileAction.rename(sequence++, parent.state.page, profile.id, value);
            parent.pendingTicks = 0;
            parent.status = tr("waiting");
            GTSWNPacketHandler.NETWORK.sendToServer(parent.pending);
            parent.updateButtons();
        }

        @Override
        protected void keyTyped(char key, int code) {
            if (code == Keyboard.KEY_ESCAPE) {
                mc.displayGuiScreen(parent);
                return;
            }
            if (code == Keyboard.KEY_RETURN || code == Keyboard.KEY_NUMPADENTER) {
                actionPerformed((GuiButton) buttonList.get(0));
                return;
            }
            name.textboxKeyTyped(key, code);
        }

        @Override
        protected void mouseClicked(int x, int y, int button) {
            super.mouseClicked(x, y, button);
            name.mouseClicked(x, y, button);
        }

        @Override
        public void updateScreen() {
            name.updateCursorCounter();
        }

        @Override
        public void drawScreen(int x, int y, float partial) {
            drawDefaultBackground();
            drawCenteredString(fontRendererObj, tr("rename"), width / 2, height / 2 - 48, 0xffffff);
            drawCenteredString(fontRendererObj, tr("nameHint"), width / 2, height / 2 - 34, 0xaaaaaa);
            name.drawTextBox();
            super.drawScreen(x, y, partial);
        }

        @Override
        public boolean doesGuiPauseGame() {
            return false;
        }

        @Override
        public void onGuiClosed() {
            Keyboard.enableRepeatEvents(false);
        }
    }

}
