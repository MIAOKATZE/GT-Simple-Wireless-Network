package com.miaokatze.gtswn.common.assembly;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import com.miaokatze.gtswn.assembly.nativeintegration.NativeAssemblySession;
import com.miaokatze.gtswn.common.assembly.pattern.OrderedPatternLayout;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;

/**
 * Diagnostic facts use actual validated layouts and never participate in material ownership or native authorization.
 */
public class MatrixDiagnosticsTest {

    @Test
    public void actualTableCoordinatesRetainRawGapsAndANewIntakeClearsPreviousConsumption() throws Exception {
        OrderedAssemblyGroup group = group();
        OrderedPatternLayout.Payload raw = rawPayload();
        group.recordAcceptedDiagnostics(raw);
        assertEquals(0, group.diagnosticTableIndex(false, 0));
        assertEquals(2, group.diagnosticTableIndex(false, 1));
        assertEquals(-1, group.diagnosticTableIndex(true, 0));
        observe(group, new long[] { 2, 3 }, new long[0]);
        assertTrue(group.diagnosticConsumptionObserved());
        assertEquals(3, group.diagnosticConsumed(false, 1));
        group.recordAcceptedDiagnostics(raw);
        assertFalse(group.diagnosticConsumptionObserved());
        assertEquals(0, group.diagnosticConsumed(false, 1));
        assertEquals(OrderedAssemblyGroup.Stage.STAGED, group.stage());
    }

    @Test
    public void observedDeltasRemainDetachedIncludingUnexpectedNegativeGrowth() throws Exception {
        OrderedAssemblyGroup group = group();
        group.recordAcceptedDiagnostics(rawPayload());
        long[] observed = { 2, -1 };
        observe(group, observed, new long[0]);
        observed[0] = 999;
        assertEquals(2, group.diagnosticConsumed(false, 0));
        assertEquals(-1, group.diagnosticConsumed(false, 1));
        assertEquals(OrderedAssemblyGroup.Stage.STAGED, group.stage());
        long[] excessive = new long[20];
        Arrays.fill(excessive, 7);
        observe(group, excessive, new long[] { 1, 2, 3, 4, 5 });
        assertEquals(7, group.diagnosticConsumed(false, 15));
        assertEquals(0, group.diagnosticConsumed(false, 16));
        assertEquals(4, group.diagnosticConsumed(true, 3));
        assertEquals(0, group.diagnosticConsumed(true, 4));
    }

    @Test
    public void ledgerRoundTripKeepsFactsAndOptionalDiagnosticCorruptionDoesNotAlterTheBatch() throws Exception {
        OrderedAssemblyGroup before = group();
        before.recordAcceptedDiagnostics(rawPayload());
        observe(before, new long[] { 2, -1 }, new long[0]);
        NBTTagCompound saved = before.saveLedger();
        OrderedAssemblyGroup restored = group();
        field("session").set(restored, field("session").get(before));
        field("memberIds").set(restored, field("memberIds").get(before));
        Method restore = OrderedAssemblyGroup.class.getDeclaredMethod("restore", NBTTagCompound.class);
        restore.setAccessible(true);
        restore.invoke(restored, saved);
        assertEquals(OrderedAssemblyGroup.Stage.STAGED, restored.stage());
        assertEquals(2, restored.diagnosticTableIndex(false, 1));
        assertTrue(restored.diagnosticConsumptionObserved());
        assertEquals(-1, restored.diagnosticConsumed(false, 1));
        saved.setString("diagnosticItemTable", "unknown display data");
        saved.setString("diagnosticItemConsumed", "unknown display data");
        restore.invoke(restored, saved);
        assertEquals(OrderedAssemblyGroup.Stage.STAGED, restored.stage());
        assertEquals(-1, restored.diagnosticTableIndex(false, 0));
        assertFalse(restored.diagnosticConsumptionObserved());
    }

    private static OrderedAssemblyGroup group() throws Exception {
        Field singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        OrderedAssemblyGroup group = (OrderedAssemblyGroup) ((sun.misc.Unsafe) singleton.get(null))
            .allocateInstance(OrderedAssemblyGroup.class);
        field("session").set(group, new NativeAssemblySession(null));
        field("stage").set(group, OrderedAssemblyGroup.Stage.STAGED);
        field("items").set(group, Arrays.asList(null, null));
        field("fluids").set(group, Collections.emptyList());
        field("memberIds").set(group, new ArrayList<>(Arrays.asList(UUID.randomUUID(), UUID.randomUUID())));
        field("memberPositions").set(group, new int[][] { { 0, 0, 0 }, { 1, 0, 0 } });
        field("recipeKey").set(group, "v1:" + String.join("", Collections.nCopies(64, "0")));
        field("patternSlot").setInt(group, 0);
        field("batch").set(group, UUID.randomUUID());
        field("reason").set(group, "");
        return group;
    }

    private static OrderedPatternLayout.Payload rawPayload() throws Exception {
        // Reuse the detached interface fixture; decode and table validation are the actual production methods.
        Class<?> fixtures = Class.forName("com.miaokatze.gtswn.common.assembly.pattern.AssemblyPatternFixtures");
        invoke(fixtures, "initialize", new Class<?>[0]);
        IAEItemStack first = (IAEItemStack) invoke(
            fixtures,
            "item",
            new Class<?>[] { ItemStack.class },
            invoke(fixtures, "input", new Class<?>[] { int.class }, 2));
        IAEItemStack second = (IAEItemStack) invoke(
            fixtures,
            "item",
            new Class<?>[] { ItemStack.class },
            invoke(fixtures, "input", new Class<?>[] { int.class }, 3));
        IAEItemStack output = (IAEItemStack) invoke(
            fixtures,
            "item",
            new Class<?>[] { ItemStack.class },
            invoke(fixtures, "output", new Class<?>[] { int.class }, 1));
        ICraftingPatternDetails details = (ICraftingPatternDetails) invoke(
            fixtures,
            "standard",
            new Class<?>[] { IAEItemStack[].class, IAEItemStack.class },
            new IAEItemStack[] { first, null, second },
            output);
        InventoryCrafting table = (InventoryCrafting) invoke(
            fixtures,
            "table",
            new Class<?>[] { IAEStack[].class },
            (Object) new IAEStack<?>[] { first, null, second });
        return OrderedPatternLayout.decode(details)
            .validateTable(table);
    }

    private static void observe(OrderedAssemblyGroup group, long[] items, long[] fluids) throws Exception {
        Method record = OrderedAssemblyGroup.class
            .getDeclaredMethod("recordConsumptionDiagnostics", long[].class, long[].class);
        record.setAccessible(true);
        record.invoke(group, items, fluids);
    }

    private static Object invoke(Class<?> type, String name, Class<?>[] parameters, Object... args) throws Exception {
        Method method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method.invoke(null, args);
    }

    private static Field field(String name) throws Exception {
        Field field = OrderedAssemblyGroup.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
