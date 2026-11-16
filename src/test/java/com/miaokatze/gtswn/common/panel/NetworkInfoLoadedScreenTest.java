// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.panel;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraftforge.common.DimensionManager;

import org.junit.Test;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;

import gregtech.common.misc.spaceprojects.SpaceProjectManager;

/** Real bridge/scheduler/dataset calls against in-memory worlds; no server or chunk is started. */
public class NetworkInfoLoadedScreenTest {

    @Test
    @SuppressWarnings("unchecked")
    public void tickingUnobservedScreenCoversPollingGapsThenExpiresWithoutLegacyLease() throws Exception {
        AEMonitorSamplingCostTest.Fixture fixture = new AEMonitorSamplingCostTest.Fixture(false);
        UUID owner = UUID.randomUUID();
        TileEntityNetworkInfoPanel panel = new TileEntityNetworkInfoPanel() {

            @Override
            public UUID getOwnerUUID() {
                return owner;
            }

            @Override
            public void markDirty() {}

            @Override
            public void broadcastEULiveData() {}
        };
        panel.setWorldObj(fixture.world);
        EUCacheBridge bridge = new EUCacheBridge(panel, new PanelConfigStore(() -> {}));
        WorldServer root = AEMonitorSamplingCostTest.allocate(WorldServer.class);
        AEMonitorSamplingCostTest.field(World.class, "perWorldStorage")
            .set(root, new MapStorage(null));
        DedicatedServer server = AEMonitorSamplingCostTest.allocate(DedicatedServer.class);
        server.worldServers = new WorldServer[] { root };
        Field singleton = null;
        for (Field field : MinecraftServer.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == MinecraftServer.class) {
                singleton = field;
                singleton.setAccessible(true);
                break;
            }
        }
        assertNotNull(singleton);
        Map<Integer, WorldServer> loaded = (Map<Integer, WorldServer>) AEMonitorSamplingCostTest
            .field(DimensionManager.class, "worlds")
            .get(null);
        Object previousServer = singleton.get(null);
        WorldServer previousRoot = loaded.get(0);
        UUID previousLeader = SpaceProjectManager.spaceTeams.put(owner, owner);
        try {
            singleton.set(null, server);
            loaded.put(0, root);
            NetworkInfoDataStore histories = NetworkInfoDataStore.get(root);
            for (int tick = 100; tick <= 600; tick++) {
                fixture.world.tick = tick;
                if ((tick - 100) % 101 == 0) bridge.tickServer(tick);
                if (tick % 100 == 0) {
                    assertEquals(
                        1,
                        histories.getActiveEntries(tick)
                            .size());
                    assertTrue(
                        NetworkInfoMonitorScheduler.sampleNativeEntry(
                            histories.getActiveEntries(tick)
                                .get(0),
                            tick,
                            tick * 50L,
                            id -> id,
                            id -> BigInteger.valueOf(fixture.world.tick)));
                }
            }
            NetworkInfoDataSet data = histories.getNativeIfPresent(owner);
            assertEquals(
                6,
                data.query(WindowChain.WINDOW_5_MIN)
                    .size());
            assertEquals(600L, data.newest().tick);
            // Last real tile poll was 504; without more ticks, its 120-tick demand ends at 624.
            assertTrue(data.isRequestActive(623));
            assertFalse(data.isRequestActive(624));
            assertTrue(
                histories.getActiveEntries(624)
                    .isEmpty());
            assertEquals(0, fixture.players.scans);
        } finally {
            singleton.set(null, previousServer);
            if (previousRoot == null) loaded.remove(0);
            else loaded.put(0, previousRoot);
            if (previousLeader == null) SpaceProjectManager.spaceTeams.remove(owner);
            else SpaceProjectManager.spaceTeams.put(owner, previousLeader);
        }
    }
}
