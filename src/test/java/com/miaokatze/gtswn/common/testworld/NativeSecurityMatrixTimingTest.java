// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.testworld;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;
import com.miaokatze.gtswn.config.Config;

import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.api.util.IReadOnlyCollection;

/** Phase contract: disconnected native membership and the periodically updated reason are different facts. */
public class NativeSecurityMatrixTimingTest {

    @Test
    public void detachedEdgesWaitForNaturalPermissionReasonWithoutDeclaringPass() throws Exception {
        boolean previous = Config.quantumSafetyEnabled;
        try {
            Config.quantumSafetyEnabled = true;
            TestContext context = context();
            NativeSecurityMatrix matrix = waitingMatrix(false);
            matrix.tick(context);
            assertNull(context.outcome);
            assertEquals(7, field(NativeSecurityMatrix.class, "phase").getInt(matrix));
        } finally {
            Config.quantumSafetyEnabled = previous;
        }
    }

    @Test
    public void evenOneNativeEndpointMembershipFailsImmediatelyDespiteReasonLag() throws Exception {
        boolean previous = Config.quantumSafetyEnabled;
        try {
            Config.quantumSafetyEnabled = true;
            TestContext context = context();
            try {
                waitingMatrix(true).tick(context);
                fail("A still-attached old edge cannot use the reason synchronization wait");
            } catch (IllegalStateException expected) {
                assertEquals("FAIL", context.outcome);
                assertEquals("Old OFF edges cannot survive or reattach while ON", context.detail);
            }
        } finally {
            Config.quantumSafetyEnabled = previous;
        }
    }

    private static TestContext context() {
        TestWorldReport report = new TestWorldReport("mode-timing", 19871, "quantum.native_security_permissions");
        TestContext context = new TestContext(null, report.runId, report, new TestWorldReport.Result(report.selection));
        context.elapsed = 151;
        return context;
    }

    @SuppressWarnings("unchecked")
    private static NativeSecurityMatrix waitingMatrix(boolean secondEndpointAttached) throws Exception {
        NativeSecurityMatrix matrix = new NativeSecurityMatrix(null, null, null, null, null, null, null);
        field(NativeSecurityMatrix.class, "phase").setInt(matrix, 7);
        field(NativeSecurityMatrix.class, "phaseTick").setLong(matrix, 150);
        field(NativeSecurityMatrix.class, "node").set(matrix, new WaitingNode());
        IGridNode a = endpoint(false), b = endpoint(secondEndpointAttached);
        IGridConnection edge = (IGridConnection) Proxy.newProxyInstance(
            IGridConnection.class.getClassLoader(),
            new Class<?>[] { IGridConnection.class },
            (proxy, method, args) -> {
                if ("a".equals(method.getName())) return a;
                if ("b".equals(method.getName())) return b;
                return null;
            });
        ((List<IGridConnection>) field(NativeSecurityMatrix.class, "offEdges").get(matrix)).add(edge);
        return matrix;
    }

    private static IGridNode endpoint(boolean attached) {
        Object connections = Proxy.newProxyInstance(
            IReadOnlyCollection.class.getClassLoader(),
            new Class<?>[] { IReadOnlyCollection.class },
            (proxy, method, args) -> {
                if ("contains".equals(method.getName())) return attached;
                if ("iterator".equals(method.getName())) return Collections.emptyList()
                    .iterator();
                if ("size".equals(method.getName())) return 0;
                if ("isEmpty".equals(method.getName())) return !attached;
                return null;
            });
        return (IGridNode) Proxy.newProxyInstance(
            IGridNode.class.getClassLoader(),
            new Class<?>[] { IGridNode.class },
            (proxy, method, args) -> "getConnections".equals(method.getName()) ? connections : null);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static final class WaitingNode extends TileEntityNetworkQuantumNode {

        @Override
        public boolean isLinked() {
            return false;
        }

        @Override
        public OfflineReason getOfflineReason() {
            return OfflineReason.NONE;
        }
    }
}
