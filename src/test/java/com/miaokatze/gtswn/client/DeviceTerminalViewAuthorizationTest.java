package com.miaokatze.gtswn.client;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.miaokatze.gtswn.client.gui.GuiDeviceInfoTerminal;
import com.miaokatze.gtswn.common.device.DeviceTerminalDeltaCodec;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalDelta;

/** Authorization replacement is independent of numeric snapshot versions and renderer tick order. */
public class DeviceTerminalViewAuthorizationTest {

    private final UUID terminal = UUID.randomUUID();
    private final DeviceTerminalAnchorRenderer anchors = DeviceTerminalAnchorRenderer.INSTANCE;

    @Before
    @After
    public void reset() {
        DeviceTerminalClientCache.clear();
        anchors.synchronizeAuthorization(null);
    }

    @Test
    public void revocationInvalidatesVisibleLabelsAndTheUnfinishedScan() throws Exception {
        publish(1, "old");
        anchors.synchronizeAuthorization(terminal);
        DeviceTerminalAnchorRenderer.Anchor anchor = anchor();
        anchors.observedDevices().add(anchor);
        field(anchors, "scanning").set(anchors, DeviceTerminalClientCache.getSnapshot(terminal));
        field(anchors, "target").set(anchors, anchor);
        assertFalse(anchors.viewReady());
        long generation = anchors.dataEpoch();

        revoke(2);
        // A render callback between network receipt and the next client tick is already denied.
        assertFalse(anchors.authorizationCurrent(terminal));
        anchors.synchronizeAuthorization(terminal);
        assertTrue(anchors.observedDevices().isEmpty());
        assertTrue(anchors.viewReady());
        assertFalse(anchors.hasCurrentTarget());
        assertTrue(anchors.dataEpoch() > generation);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void renewedFreshAuthorityCannotRenderOldHeatOrResumeItsBuild() throws Exception {
        publish(1, "old");
        anchors.synchronizeAuthorization(terminal);
        DeviceEnergyWorldRenderer heat = new DeviceEnergyWorldRenderer();
        heat.synchronizeAuthorization(terminal);
        DeviceTerminalAnchorRenderer.Anchor anchor = anchor();
        ((List<DeviceEnergyWorldRenderer.Visual>) field(heat, "visible").get(heat))
            .add(new DeviceEnergyWorldRenderer.Visual(anchor, false, 32D, false));
        field(heat, "source").set(heat, Collections.singletonList(anchor));

        revoke(2);
        publish(3, "new");
        assertTrue(DeviceTerminalClientCache.isFresh(terminal, System.currentTimeMillis()));
        assertFalse(heat.authorizationCurrent());
        heat.synchronizeAuthorization(terminal);
        assertTrue(((List<?>) field(heat, "visible").get(heat)).isEmpty());
        assertNull(field(heat, "source").get(heat));
        // Heat ticks first: it must wait until the anchor renderer has also dropped its old source.
        assertFalse(anchors.authorizationCurrent(terminal));
        anchors.synchronizeAuthorization(terminal);
        assertTrue(anchors.authorizationCurrent(terminal));
        assertFalse(anchors.authorizationCurrent(UUID.randomUUID()));
    }

    @Test
    public void unchangedAuthorityKeepsTheExistingBoundedRenderWork() throws Exception {
        publish(1, "old");
        anchors.synchronizeAuthorization(terminal);
        DeviceTerminalAnchorRenderer.Anchor anchor = anchor();
        anchors.observedDevices().add(anchor);
        field(anchors, "scanning").set(anchors, DeviceTerminalClientCache.getSnapshot(terminal));
        anchors.synchronizeAuthorization(terminal);
        assertSame(anchor, anchors.observedDevices().get(0));
        assertFalse(anchors.viewReady());
        anchors.synchronizeAuthorization(UUID.randomUUID());
        assertTrue(anchors.observedDevices().isEmpty());
        assertTrue(anchors.viewReady());
    }

    @Test
    public void clientSessionCacheResetInvalidatesOldMarkers() {
        publish(1, "old");
        anchors.synchronizeAuthorization(terminal);
        anchors.observedDevices().add(anchor());
        DeviceTerminalClientCache.clear();
        assertFalse(anchors.authorizationCurrent(terminal));
        anchors.synchronizeAuthorization(terminal);
        assertTrue(anchors.observedDevices().isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void nativeListReplacesRowsWithTheSameVersionAndCountAfterEpochChange() throws Exception {
        publish(1, "old");
        GuiDeviceInfoTerminal gui = allocate(GuiDeviceInfoTerminal.class);
        field(gui, "anchor").set(gui, terminal);
        field(gui, "sortedEntries").set(gui, Collections.emptyList());
        Method refresh = GuiDeviceInfoTerminal.class.getDeclaredMethod("refreshEntries");
        refresh.setAccessible(true);
        refresh.invoke(gui);
        List<Entry> oldRows = (List<Entry>) field(gui, "sortedEntries").get(gui);
        assertEquals("old", oldRows.get(0).name);
        refresh.invoke(gui);
        assertSame(oldRows, field(gui, "sortedEntries").get(gui));

        // Source/permission revisions reset the transport epoch without changing data.version.
        publish(2, "new");
        refresh.invoke(gui);
        List<Entry> newRows = (List<Entry>) field(gui, "sortedEntries").get(gui);
        assertNotSame(oldRows, newRows);
        assertEquals("new", newRows.get(0).name);
        revoke(3);
        refresh.invoke(gui);
        assertTrue(((List<?>) field(gui, "sortedEntries").get(gui)).isEmpty());
    }

    @Test
    public void authorizationReplacementClearsSelectionAndOpenEditorWithoutNullSnapshot() throws Exception {
        publish(1, "old");
        GuiDeviceInfoTerminal gui = gui();
        Entry before = DeviceTerminalClientCache.getSnapshot(terminal).entries.get(0);
        field(gui, "selectedDesktopEntry").set(gui, before);
        Object editor = editor(gui, before, 1);
        publish(2, "replacement");
        refresh(gui);
        assertNull(field(gui, "selectedDesktopEntry").get(gui));
        assertNull(field(editor, "entry").get(editor));
    }

    @Test
    public void editorInputRejectsChangedAuthorityBeforeNextGuiTick() throws Exception {
        publish(1, "old");
        GuiDeviceInfoTerminal gui = gui();
        Object editor = editor(gui, DeviceTerminalClientCache.getSnapshot(terminal).entries.get(0), 1);
        publish(2, "replacement");
        Method save = editor.getClass().getDeclaredMethod("save", boolean.class);
        save.setAccessible(true);
        // The stale editor closes before touching its text field or sending a network request.
        save.invoke(editor, false);
        assertNull(field(editor, "entry").get(editor));
    }

    @Test
    public void ordinarySnapshotRefreshesSelectedEntryAndRemovalClearsIt() throws Exception {
        Entry first = new Entry("0:0:0:0", "first", (byte) 1, (byte) 0, 0L, 0L, 0D, 0D, 0, 0, 0, 0, "", "");
        DeviceTerminalClientCache.receivePage(terminal, 1, 0, 1, Collections.singletonList(first));
        GuiDeviceInfoTerminal gui = gui();
        field(gui, "selectedDesktopEntry").set(gui, first);
        Entry newest = new Entry("0:0:0:0", "newest", (byte) 1, (byte) 0, 0L, 0L, 0D, 0D, 0, 0, 0, 0, "", "");
        DeviceTerminalClientCache.receivePage(terminal, 2, 0, 1, Collections.singletonList(newest));
        refresh(gui);
        assertSame(newest, field(gui, "selectedDesktopEntry").get(gui));
        DeviceTerminalClientCache.receivePage(terminal, 3, 0, 1, Collections.emptyList());
        refresh(gui);
        assertNull(field(gui, "selectedDesktopEntry").get(gui));
    }

    private GuiDeviceInfoTerminal gui() throws Exception {
        GuiDeviceInfoTerminal gui = allocate(GuiDeviceInfoTerminal.class);
        field(gui, "anchor").set(gui, terminal);
        field(gui, "sortedEntries").set(gui, Collections.emptyList());
        refresh(gui);
        return gui;
    }

    private static void refresh(GuiDeviceInfoTerminal gui) throws Exception {
        Method refresh = GuiDeviceInfoTerminal.class.getDeclaredMethod("refreshEntries");
        refresh.setAccessible(true);
        refresh.invoke(gui);
    }

    private Object editor(GuiDeviceInfoTerminal gui, Entry entry, long epoch) throws Exception {
        Class<?> type = Class.forName("com.miaokatze.gtswn.client.gui.GuiDeviceNameEditor");
        Object editor = allocate(type);
        field(editor, "host").set(editor, gui);
        field(editor, "entry").set(editor, entry);
        field(editor, "terminal").set(editor, terminal);
        field(editor, "authorizationEpoch").setLong(editor, epoch);
        Class<?> handler = field(editor, "handler").getType();
        field(editor, "handler").set(editor, java.lang.reflect.Proxy.newProxyInstance(
            handler.getClassLoader(), new Class<?>[] { handler }, (proxy, method, args) -> null));
        field(gui, "nameEditor").set(gui, editor);
        return editor;
    }

    private DeviceTerminalAnchorRenderer.Anchor anchor() {
        return new DeviceTerminalAnchorRenderer.Anchor(
            DeviceTerminalClientCache.getSnapshot(terminal).entries.get(0),
            0);
    }

    private void publish(long epoch, String name) {
        Entry entry = new Entry("0:0:0:0", name, (byte) 1, (byte) 0, 32L, 0L, 32D, 0D, 0, 0, 0, 0, "", "");
        DeviceTerminalClientCache.receiveDelta(
            new PacketSyncDeviceTerminalDelta(
                terminal,
                DeviceTerminalClientCache.compactRequestMetadata(terminal).getSession(),
                epoch,
                -1,
                7,
                PacketSyncDeviceTerminalDelta.FULL,
                0,
                1,
                1,
                DeviceTerminalDeltaCodec.encode(null, Collections.singletonList(entry)).bytes));
    }

    private void revoke(long epoch) {
        DeviceTerminalClientCache.receiveDelta(
            new PacketSyncDeviceTerminalDelta(
                terminal,
                DeviceTerminalClientCache.compactRequestMetadata(terminal).getSession(),
                epoch,
                -1,
                0,
                PacketSyncDeviceTerminalDelta.REVOKED,
                0,
                1,
                0,
                new byte[0]));
    }

    private static Field field(Object instance, String name) throws Exception {
        Field field = instance.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field field = unsafeType.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (T) unsafeType.getMethod("allocateInstance", Class.class)
            .invoke(field.get(null), type);
    }
}
