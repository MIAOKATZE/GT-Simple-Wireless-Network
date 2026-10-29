package com.miaokatze.gtswn.common.tile;

import static org.junit.Assert.*;

import java.lang.reflect.Field;

import org.junit.Test;

import com.miaokatze.gtswn.common.panel.NetworkScreen;
import com.miaokatze.gtswn.common.tile.screen.ScreenStructure;

public class PanelProxyLifecycleTest {

    @Test
    public void unloadRequiresProxyReadyAgainEvenWhenTheSameTileIsReused() throws Exception {
        TileEntityNetworkInfoPanel panel = new TileEntityNetworkInfoPanel();
        Field ready = readyFlag();
        for (int cycle = 0; cycle < 3; cycle++) {
            ready.setBoolean(panel, true);
            panel.onChunkUnload();
            panel.onChunkUnload();
            panel.validate();
            assertFalse("Native proxy unload clears readiness; next server tick must call onReady again",
                ready.getBoolean(panel));
        }
    }

    @Test
    public void invalidateRequiresProxyReadyAgainAndValidateCannotReuseTheOldLatch() throws Exception {
        TileEntityNetworkInfoPanel panel = new TileEntityNetworkInfoPanel();
        Field ready = readyFlag();
        for (int cycle = 0; cycle < 3; cycle++) {
            ready.setBoolean(panel, true);
            panel.invalidate();
            panel.validate();
            assertFalse(ready.getBoolean(panel));
        }
    }

    @Test
    public void dormantTileRetainsSavedBoundsButSchedulesFreshTopologyDiscovery() throws Exception {
        TileEntityNetworkInfoPanel panel = new TileEntityNetworkInfoPanel();
        Field structureField = TileEntityNetworkInfoPanel.class.getDeclaredField("structure");
        structureField.setAccessible(true);
        ScreenStructure structure = (ScreenStructure) structureField.get(panel);
        NetworkScreen saved = new NetworkScreen();
        structure.setScreen(saved);
        Field initialized = ScreenStructure.class.getDeclaredField("screenInitialized");
        initialized.setAccessible(true);
        for (int cycle = 0; cycle < 3; cycle++) {
            initialized.setBoolean(structure, true);
            panel.onChunkUnload();
            panel.validate();
            assertFalse(structure.isInitialized());
            assertSame(saved, structure.getScreen());
            initialized.setBoolean(structure, true);
            panel.invalidate();
            panel.validate();
            assertFalse(structure.isInitialized());
            assertSame(saved, structure.getScreen());
        }
    }

    private static Field readyFlag() throws Exception {
        Field ready = TileEntityNetworkInfoPanel.class.getDeclaredField("aeProxyReady");
        ready.setAccessible(true);
        return ready;
    }
}
