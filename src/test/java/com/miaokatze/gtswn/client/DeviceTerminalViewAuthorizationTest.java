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
