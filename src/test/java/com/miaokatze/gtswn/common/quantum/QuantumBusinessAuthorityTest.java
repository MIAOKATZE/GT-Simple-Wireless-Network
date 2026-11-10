package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.EnumSet;
import java.util.HashMap;

import net.minecraft.entity.player.EntityPlayerMP;

import org.junit.Test;

import appeng.api.config.SecurityPermissions;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.networking.security.ISecurityProvider;

/** Policy contract with explicit native verdicts; this does not replace real AE station/card evidence. */
public class QuantumBusinessAuthorityTest {

    @Test
    public void operatorFlagsCannotReplaceTheNativeVerdictOrMatchingTerminalKey() throws Exception {
        OperatorActor actor = operator();
        assertTrue(actor.canCommandSenderUseCommand(2, "gtswn"));
        assertTrue(actor.canCommandSenderUseCommand(4, "gtswn"));
        actor.queries = 0;
        EnumSet<SecurityPermissions> grants = EnumSet.noneOf(SecurityPermissions.class);
        ISecurityGrid nativeVerdict = security(grants);
        assertFalse(QuantumTerminalAccess.permitsEnrolled(nativeVerdict, true, actor, false, 8));
        assertFalse(QuantumTerminalAccess.permitsEnrolled(nativeVerdict, true, actor, true, 8));
        grants.add(SecurityPermissions.BUILD);
        assertTrue(QuantumTerminalAccess.permitsEnrolled(nativeVerdict, true, actor, false, 8));
        assertFalse(QuantumTerminalAccess.permitsEnrolled(nativeVerdict, true, actor, true, 8));
        grants.clear();
        grants.add(SecurityPermissions.SECURITY);
        assertFalse(QuantumTerminalAccess.permitsEnrolled(nativeVerdict, true, actor, false, 8));
        assertTrue(QuantumTerminalAccess.permitsEnrolled(nativeVerdict, true, actor, true, 8));
        assertFalse(QuantumTerminalAccess.permitsEnrolled(nativeVerdict, false, actor, true, 8));
        grants.add(SecurityPermissions.BUILD);
        assertFalse(QuantumTerminalAccess.permitsEnrolled(nativeVerdict, false, actor, false, 8));
        assertEquals(0, actor.queries);
    }

    @Test
    public void currentCardGrantSeparatesBuildBridgingFromSafetyRecoveryAndRevokesImmediately() throws Exception {
        OperatorActor actor = operator();
        EnumSet<SecurityPermissions> grants = EnumSet.of(SecurityPermissions.BUILD);
        ISecurityGrid nativeVerdict = security(grants);
        ISecurityProvider provider = provider(grants);
        assertTrue(QuantumConnectionSecurity.permitsProvider(provider, 8));
        assertFalse(QuantumTerminalAccess.permitsSafetyProvider(provider, 8));
        grants.clear();
        assertFalse(QuantumTerminalAccess.permitsEnrolled(nativeVerdict, true, actor, false, 8));
        assertFalse(QuantumConnectionSecurity.permitsProvider(provider, 8));
        grants.add(SecurityPermissions.SECURITY);
        assertTrue(QuantumTerminalAccess.permitsSafetyProvider(provider, 8));
        assertFalse(QuantumConnectionSecurity.permitsProvider(provider, 8));
        assertFalse(QuantumTerminalAccess.permitsEnrolled(nativeVerdict, false, actor, true, 8));
        assertTrue(QuantumTerminalAccess.permitsSafetyProvider(provider, 7));
        grants.clear();
        assertFalse(QuantumTerminalAccess.permitsSafetyProvider(provider, 8));
        assertFalse(QuantumTerminalAccess.permitsEnrolled(nativeVerdict, true, actor, true, 8));
        assertEquals(0, actor.queries);
    }

    private static ISecurityGrid security(EnumSet<SecurityPermissions> grants) {
        return (ISecurityGrid) Proxy.newProxyInstance(
            ISecurityGrid.class.getClassLoader(),
            new Class<?>[] { ISecurityGrid.class },
            (proxy, method, args) -> {
                if ("getOwner".equals(method.getName())) return 7;
                if ("isAvailable".equals(method.getName())) return true;
                if ("hasPermission".equals(method.getName())) return grants.contains(args[1]);
                return null;
            });
    }

    @SuppressWarnings("unchecked")
    private static ISecurityProvider provider(EnumSet<SecurityPermissions> grants) {
        return (ISecurityProvider) Proxy.newProxyInstance(
            ISecurityProvider.class.getClassLoader(),
            new Class<?>[] { ISecurityProvider.class },
            (proxy, method, args) -> {
                if ("getOwner".equals(method.getName())) return 7;
                if ("readPermissions".equals(method.getName()))
                    ((HashMap<Integer, EnumSet<SecurityPermissions>>) args[0]).put(8, EnumSet.copyOf(grants));
                return null;
            });
    }

    private static OperatorActor operator() throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field field = unsafe.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (OperatorActor) unsafe.getMethod("allocateInstance", Class.class)
            .invoke(field.get(null), OperatorActor.class);
    }

    private static final class OperatorActor extends EntityPlayerMP {

        int queries;

        private OperatorActor() {
            super(null, null, null, null);
        }

        @Override
        public boolean canCommandSenderUseCommand(int level, String command) {
            queries++;
            return true;
        }
    }
}
