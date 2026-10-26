package com.miaokatze.gtswn.common.monitoring;

import static org.junit.Assert.*;

import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.Test;

import com.miaokatze.gtswn.api.monitor.HistorySample;
import com.miaokatze.gtswn.api.monitor.HistoryWindow;
import com.miaokatze.gtswn.api.monitor.NetworkSnapshot;

import appeng.api.config.SecurityPermissions;
import appeng.api.networking.security.ISecurityProvider;

public class PortableProfileAdapterTest {

    private ISecurityProvider provider(int owner, int member, SecurityPermissions... grants) {
        return (ISecurityProvider) Proxy.newProxyInstance(
            ISecurityProvider.class.getClassLoader(),
            new Class<?>[] { ISecurityProvider.class },
            (proxy, method, args) -> {
                if ("getOwner".equals(method.getName())) return owner;
                if ("readPermissions".equals(method.getName())) {
                    EnumSet<SecurityPermissions> permissions = EnumSet.noneOf(SecurityPermissions.class);
                    permissions.addAll(Arrays.asList(grants));
                    ((Map<Integer, EnumSet<SecurityPermissions>>) args[0]).put(member, permissions);
                    return null;
                }
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == int.class) return 0;
                if (method.getReturnType() == long.class) return 0L;
                return null;
            });
    }

    @Test
    public void nativeProviderMustGrantManagementNotJustBuildOrPanelOwnership() {
        assertTrue(PortableProfileAdapter.permitsProvider(provider(7, 8), 7));
        assertFalse(PortableProfileAdapter.permitsProvider(provider(7, 8), 8));
        assertFalse(PortableProfileAdapter.permitsProvider(provider(7, 8, SecurityPermissions.BUILD), 8));
        assertTrue(PortableProfileAdapter.permitsProvider(provider(7, 8, SecurityPermissions.SECURITY), 8));
        assertTrue(PortableProfileAdapter.permitsProvider(provider(7, -1, SecurityPermissions.SECURITY), 8));
        assertFalse(PortableProfileAdapter.permitsProvider(provider(-1, 8, SecurityPermissions.SECURITY), 8));
    }

    @Test
    public void nativeAeUnavailableCannotAuthorizeCachedPrivateSnapshot() {
        MonitoringProfiles profiles = new MonitoringProfiles("test");
        UUID actor = UUID.randomUUID();
        SourceKey source = new SourceKey(
            SourceKey.Kind.AE,
            profiles.epoch(),
            UUID.randomUUID()
                .toString(),
            1);
        MonitorProfile profile = profiles
            .create(actor, null, Collections.singletonMap("panel|0:1:64:2|" + UUID.randomUUID(), source));
        profiles.sourceAccessForTest((a, b, c, d) -> MonitorProfile.Status.SOURCE_UNLOADED);
        assertFalse(profiles.canRead(actor, profile.id));
        assertFalse(profiles.maySample(profile.id));
        assertEquals(MonitorProfile.Status.SOURCE_UNLOADED, profile.status());
    }

    @Test
    public void quantumPauseDoesNotBecomeZeroOrAuthorization() {
        MonitoringProfiles profiles = new MonitoringProfiles("test");
        UUID actor = UUID.randomUUID();
        SourceKey source = new SourceKey(SourceKey.Kind.QUANTUM, profiles.epoch(), "native-controller", 1);
        MonitorProfile profile = profiles.create(actor, null, Collections.singletonMap("0:1:64:2", source));
        profiles.sourceAccessForTest((a, b, c, d) -> MonitorProfile.Status.JOB_PAUSED);
        assertFalse(profiles.canRead(actor, profile.id));
        assertEquals(MonitorProfile.Status.JOB_PAUSED, profile.status());
        try {
            profiles.setOffline(actor, profile.id, true, 100);
            fail("Unprovable credentials must not create live job");
        } catch (IllegalArgumentException expected) {
            assertFalse(profile.offlineEnabled());
        }
    }

    @Test
    public void euOnlyPanelCannotExpandToConnectedAEAfterImport() {
        final boolean[] connected = { false };
        com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel panel = new com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel() {

            @Override
            public Object getAEMonitorSourceIdentity() {
                return connected[0] ? this : null;
            }
        };
        assertEquals(MonitorProfile.Status.LIVE, PortableProfileAdapter.euPanelStatus(panel));
        connected[0] = true;
        assertEquals(MonitorProfile.Status.SOURCE_REPLACED, PortableProfileAdapter.euPanelStatus(panel));
    }

    @Test
    public void unpoweredPanelWithCachedAEDataCannotMasqueradeAsEUOnly() {
        com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel panel = new com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel() {

            @Override
            public Object getAEMonitorSourceIdentity() {
                return null;
            }

            @Override
            public java.util.Map<String, com.miaokatze.gtswn.common.panel.AEMonitorSample> getAEMonitorLatest() {
                return Collections
                    .singletonMap("private-item", new com.miaokatze.gtswn.common.panel.AEMonitorSample(1, 1, 1, 0));
            }
        };
        assertTrue(PortableProfileAdapter.hasAEState(panel));
        assertEquals(MonitorProfile.Status.SOURCE_REPLACED, PortableProfileAdapter.euPanelStatus(panel));
    }

    @Test
    public void optionalMissingHistoryIsEmptyNotFabricatedZero() {
        assertEquals(0, PortableProfileAdapter.history(Optional.empty()).length);
        HistoryWindow window = new HistoryWindow(
            0,
            Arrays.asList(new HistorySample(1, 1, BigInteger.TEN, 0), new HistorySample(2, 2, BigInteger.ZERO, 0)));
        assertArrayEquals(new double[] { 10, 0 }, PortableProfileAdapter.history(Optional.of(window)), 0);
    }

    @Test
    public void fixedNativeEuExportCannotBorrowPublicApisLegacyOwnerArchive() {
        assertEquals(
            0,
            PortableProfileAdapter.nativeEUHistory(
                Optional.empty(),
                () -> {
                    throw new AssertionError(
                        "Without a native sample, do not even query the legacy-compatible history API");
                }).length);
        NetworkSnapshot latest = new NetworkSnapshot(UUID.randomUUID(), 100, 1000, BigInteger.TEN, 0, false);
        HistoryWindow current = new HistoryWindow(
            0,
            Collections.singletonList(new HistorySample(100, 1000, BigInteger.TEN, 0)));
        assertArrayEquals(
            new double[] { 10 },
            PortableProfileAdapter.nativeEUHistory(Optional.of(latest), () -> Optional.of(current)),
            0);
    }
}
