// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.assembly.pattern;

import static com.miaokatze.gtswn.common.assembly.pattern.CondensedPatternDeliveryTest.*;
import static com.miaokatze.gtswn.common.assembly.pattern.OrderedPatternLayoutTest.assertRejected;
import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.BeforeClass;
import org.junit.Test;

import appeng.api.storage.data.IAEItemStack;
import appeng.util.inv.MEInventoryCrafting;

/** Contract tests for the pinned execution boundary; no Forge loader or real CPU is constructed. */
public class NeoEcoCondensedDeliveryTest {

    private static final String MATRIX = "com.miaokatze.gtswn.common.assembly.";
    private static final String CPU = "cn.dancingsnow.neoecoae.crafting.execution.ECOCraftingCPULogic";
    private static final String PROVIDER = "cn.dancingsnow.neoecoae.platform.ae2.crafting.NativeCraftingProvider";

    @BeforeClass
    public static void fixtures() throws Exception {
        AssemblyPatternFixtures.initialize();
    }

    @Test
    public void bothDirectMatrixMembersAcceptTheVerifiedFastPlannedTickChain() {
        assertTrue(NeoEcoCondensedDelivery.matches(chain("MatrixItemInputBus")));
        assertTrue(NeoEcoCondensedDelivery.matches(chain("MatrixFluidInputHatch")));
    }

    @Test
    public void installationAloneAndUnknownCallersNeverAuthorizeRestoration() {
        assertFalse(NeoEcoCondensedDelivery.active(() -> true, () -> new StackTraceElement[0]));
        assertFalse(
            NeoEcoCondensedDelivery.matches(
                Thread.currentThread()
                    .getStackTrace()));
        assertFalse(NeoEcoCondensedDelivery.matches(new StackTraceElement[] { frame(PROVIDER, "pushPattern") }));
        assertFalse(
            NeoEcoCondensedDelivery
                .active(() -> false, () -> { throw new AssertionError("An absent mod must not capture a trace"); }));
    }

    @Test
    public void nativeAeAndNeoEcoVanillaExecutorsStayOutsideTheBoundary() {
        for (String executor : new String[] { "appeng.me.cluster.implementations.CraftingCPUCluster",
            "cn.dancingsnow.neoecoae.crafting.execution.ECOVanillaCraftingExecutor" }) {
            StackTraceElement[] trace = chain("MatrixItemInputBus");
            trace[5] = frame(executor, "executeCrafting");
            assertFalse(executor, NeoEcoCondensedDelivery.matches(trace));
        }
    }

    @Test
    public void forwardersAndNestedOrdinaryPushesCannotBorrowAnOlderFastAncestor() {
        List<StackTraceElement> trace = new ArrayList<>(Arrays.asList(chain("MatrixItemInputBus")));
        trace.add(5, frame("other.mod.ForwardingMedium", "pushPattern"));
        assertFalse(NeoEcoCondensedDelivery.matches(trace.toArray(new StackTraceElement[0])));
        trace = new ArrayList<>(Arrays.asList(chain("MatrixItemInputBus")));
        trace.add(6, frame("appeng.me.cluster.implementations.CraftingCPUCluster", "executeCrafting"));
        trace.add(6, frame(PROVIDER, "pushPattern"));
        assertFalse(NeoEcoCondensedDelivery.matches(trace.toArray(new StackTraceElement[0])));
        trace = new ArrayList<>(Arrays.asList(chain("MatrixItemInputBus")));
        trace.remove(trace.size() - 1);
        assertFalse(
            "A direct public executeNormalCrafting call lacks the real tick",
            NeoEcoCondensedDelivery.matches(trace.toArray(new StackTraceElement[0])));
    }

    @Test
    public void onlyFailedPositionalValidationConsultsTheOptionalCompatibilityGate() throws Exception {
        OrderedPatternLayout declared = layout(item(3, 32), item(3, 32));
        AtomicInteger probes = new AtomicInteger();
        declared.validateDelivery(AssemblyPatternFixtures.table(item(3, 32), item(3, 32)), () -> {
            probes.incrementAndGet();
            return false;
        });
        assertEquals(0, probes.get());
        MEInventoryCrafting condensed = AssemblyPatternFixtures.table(item(3, 64));
        assertRejected(PatternValidationException.Reason.TABLE_SHAPE, () -> declared.validateDelivery(condensed, () -> {
            probes.incrementAndGet();
            return false;
        }));
        assertEquals(1, probes.get());
    }

    @Test
    public void identicalCondensedCargoRequiresFastPlanningAndRestores32Plus32And64Plus32() throws Exception {
        for (int first : new int[] { 32, 64 }) {
            OrderedPatternLayout declared = layout(item(3, first), item(3, 32));
            IAEItemStack cargo = item(3, first + 32);
            MEInventoryCrafting table = AssemblyPatternFixtures.table(cargo);
            assertRejected(
                PatternValidationException.Reason.TABLE_SHAPE,
                () -> declared.validateDelivery(table, () -> false));
            OrderedPatternLayout.Payload payload = declared.validateDelivery(
                table,
                () -> NeoEcoCondensedDelivery.active(() -> true, () -> chain("MatrixItemInputBus")));
            assertEquals(1, payload.copies());
            assertEquals(
                first,
                payload.items()
                    .get(0)
                    .amount());
            assertEquals(
                32,
                payload.items()
                    .get(1)
                    .amount());
            assertEquals(
                0,
                payload.items()
                    .get(0)
                    .tableIndex());
            assertEquals(
                0,
                payload.items()
                    .get(1)
                    .tableIndex());
            assertSame(cargo, table.getAEStackInSlot(0));
            assertEquals(first + 32, cargo.getStackSize());
        }
    }

    private static StackTraceElement[] chain(String member) {
        return new StackTraceElement[] { frame(OrderedPatternLayout.class.getName(), "validateTable"),
            frame(MATRIX + "OrderedAssemblyGroup", "acceptInstalled"), frame(MATRIX + "OrderedAssemblyGroup", "accept"),
            frame(MATRIX + "MatrixAssemblyPort", "pushPattern"), frame(MATRIX + member, "pushPattern"),
            frame(PROVIDER, "pushPattern"), frame(CPU, "invokeNormalProvider"),
            frame("cn.dancingsnow.neoecoae.crafting.execution.ECOCraftingTaskScheduler", "execute"),
            frame(CPU, "executeNormalCrafting"), frame(CPU, "executeCrafting"), frame(CPU, "tickCraftingLogic") };
    }

    private static StackTraceElement frame(String owner, String method) {
        return new StackTraceElement(owner, method, null, -1);
    }
}
