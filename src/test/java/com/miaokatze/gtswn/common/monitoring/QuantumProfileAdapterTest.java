package com.miaokatze.gtswn.common.monitoring;

import static org.junit.Assert.*;

import org.junit.Test;

public class QuantumProfileAdapterTest {

    @Test
    public void locatorsAreFixedCoordinatesNotCredentialsOrQueries() {
        assertArrayEquals(new int[] { -1, -20, 64, 0 }, QuantumProfileAdapter.parse("quantum:-1:-20:64:0"));
        assertNull(QuantumProfileAdapter.parse("quantum:0:1:256:2"));
        assertNull(QuantumProfileAdapter.parse("quantum:0:1:-1:2"));
        assertNull(QuantumProfileAdapter.parse("quantum:0:1:64:2:key"));
        assertNull(QuantumProfileAdapter.parse("quantum:0:99999999999999:64:2"));
    }

    @Test
    public void managedMetadataNeverFallsBackToLegacyWithoutActorAuthority() {
        net.minecraft.item.ItemStack stack = new net.minecraft.item.ItemStack(new net.minecraft.item.Item());
        assertTrue(QuantumProfileAdapter.profilePermits(null, stack, false));
        stack.stackTagCompound = new net.minecraft.nbt.NBTTagCompound();
        stack.stackTagCompound.setString(MonitoringProfiles.NBT_PROFILE, "malformed");
        assertFalse(QuantumProfileAdapter.profilePermits(null, stack, false));
        assertFalse(QuantumProfileAdapter.profilePermits(null, stack, true));
        stack.stackTagCompound.setString(
            MonitoringProfiles.NBT_PROFILE,
            java.util.UUID.randomUUID()
                .toString());
        assertFalse(QuantumProfileAdapter.profilePermits(null, stack, false));
    }

    /** Real native ISecurityProvider API fixture, with no EntityPlayer or terminal credential. */
    private static final class NativeAcl {

        int owner = 7;
        final java.util.Map<Integer, java.util.EnumSet<appeng.api.config.SecurityPermissions>> grants = new java.util.HashMap<>();
        boolean unavailable;
        final appeng.api.networking.security.ISecurityProvider provider = (appeng.api.networking.security.ISecurityProvider) java.lang.reflect.Proxy
            .newProxyInstance(
                appeng.api.networking.security.ISecurityProvider.class.getClassLoader(),
                new Class<?>[] { appeng.api.networking.security.ISecurityProvider.class },
                (proxy, method, args) -> {
                    if ("getOwner".equals(method.getName())) return owner;
                    if ("readPermissions".equals(method.getName())) {
                        if (unavailable) throw new IllegalStateException("Native authority unavailable");
                        ((java.util.Map<Integer, java.util.EnumSet<appeng.api.config.SecurityPermissions>>) args[0])
                            .putAll(grants);
                        return null;
                    }
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
                });

        void grant(int id, appeng.api.config.SecurityPermissions permission) {
            grants.put(id, java.util.EnumSet.of(permission));
        }
    }

    @Test
    public void offlineNativeAclRevocationIsRecheckedWithoutPlayerOrSavedKey() {
        NativeAcl acl = new NativeAcl();
        java.util.List<appeng.api.networking.security.ISecurityProvider> providers = java.util.Collections
            .singletonList(acl.provider);
        int offlineUuidMappedId = 42;
        assertEquals(
            MonitorProfile.Status.NO_PERMISSION,
            QuantumProfileAdapter.providerAuthority(offlineUuidMappedId, providers));
        acl.grant(offlineUuidMappedId, appeng.api.config.SecurityPermissions.BUILD);
        assertEquals(
            MonitorProfile.Status.NO_PERMISSION,
            QuantumProfileAdapter.providerAuthority(offlineUuidMappedId, providers));
        acl.grant(offlineUuidMappedId, appeng.api.config.SecurityPermissions.SECURITY);
        assertEquals(
            MonitorProfile.Status.LIVE,
            QuantumProfileAdapter.providerAuthority(offlineUuidMappedId, providers));
        acl.grants.clear();
        assertEquals(
            MonitorProfile.Status.NO_PERMISSION,
            QuantumProfileAdapter.providerAuthority(offlineUuidMappedId, providers));
        acl.grant(-1, appeng.api.config.SecurityPermissions.SECURITY);
        assertEquals(
            MonitorProfile.Status.LIVE,
            QuantumProfileAdapter.providerAuthority(offlineUuidMappedId, providers));
        acl.grant(offlineUuidMappedId, appeng.api.config.SecurityPermissions.BUILD);
        assertEquals(
            MonitorProfile.Status.NO_PERMISSION,
            QuantumProfileAdapter.providerAuthority(offlineUuidMappedId, providers));
    }

    @Test
    public void everyNativeProviderMustAuthorizeAndMissingAuthorityNeverBecomesPublic() {
        NativeAcl first = new NativeAcl(), second = new NativeAcl();
        first.grant(42, appeng.api.config.SecurityPermissions.SECURITY);
        java.util.List<appeng.api.networking.security.ISecurityProvider> providers = java.util.Arrays
            .asList(first.provider, second.provider);
        assertEquals(MonitorProfile.Status.NO_PERMISSION, QuantumProfileAdapter.providerAuthority(42, providers));
        second.grant(42, appeng.api.config.SecurityPermissions.SECURITY);
        assertEquals(MonitorProfile.Status.LIVE, QuantumProfileAdapter.providerAuthority(42, providers));
        second.unavailable = true;
        assertEquals(MonitorProfile.Status.JOB_PAUSED, QuantumProfileAdapter.providerAuthority(42, providers));
        second.unavailable = false;
        second.owner = -1;
        assertEquals(MonitorProfile.Status.NO_PERMISSION, QuantumProfileAdapter.providerAuthority(42, providers));
        assertEquals(MonitorProfile.Status.JOB_PAUSED, QuantumProfileAdapter.providerAuthority(-1, providers));
        assertNull(
            "Unsecured grid still needs actual loaded controller ownership",
            QuantumProfileAdapter.providerAuthority(42, java.util.Collections.emptyList()));
        assertEquals(
            MonitorProfile.Status.LIVE,
            QuantumProfileAdapter.providerAuthority(7, java.util.Collections.singletonList(first.provider)));
    }
}
