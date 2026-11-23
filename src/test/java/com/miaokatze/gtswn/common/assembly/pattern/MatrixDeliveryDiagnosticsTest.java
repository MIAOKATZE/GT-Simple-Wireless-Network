// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.assembly.pattern;

import static com.miaokatze.gtswn.common.assembly.pattern.AssemblyPatternFixtures.*;
import static com.miaokatze.gtswn.common.assembly.pattern.OrderedPatternLayoutTest.assertRejected;
import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.junit.BeforeClass;
import org.junit.Test;

import com.miaokatze.gtswn.test.FluidRegistryTestBootstrap;

import appeng.api.storage.data.IAEStack;
import appeng.util.TunnelPatternExpander;
import appeng.util.inv.MEInventoryCrafting;

public class MatrixDeliveryDiagnosticsTest {

    @BeforeClass
    public static void fixtures() throws Exception {
        AssemblyPatternFixtures.initialize();
    }

    @Test
    public void nativeAeExpansionKeepsDuplicatePositionsAndRejectedCondensationLeavesCargoIntact() throws Exception {
        IAEStack<?> first = item(input(64));
        IAEStack<?> second = item(input(32));
        IAEStack<?> liquid = fluid(FluidRegistryTestBootstrap.water(144));
        IAEStack<?>[] sparse = { first, null, second, liquid };
        OrderedPatternLayout layout = OrderedPatternLayout.decode(ultimate(sparse, item(output(1))));

        List<IAEStack<?>> expanded = TunnelPatternExpander.expandInputs(
            layout.details()
                .getAEInputs(),
            null,
            null);
        assertEquals(Arrays.asList(first, second, liquid), expanded);
        OrderedPatternLayout.Payload accepted = layout.validateTable(table(expanded.toArray(new IAEStack<?>[0])));
        assertEquals(
            64L,
            accepted.items()
                .get(0)
                .amount());
        assertEquals(
            32L,
            accepted.items()
                .get(1)
                .amount());
        assertEquals(
            144L,
            accepted.fluids()
                .get(0)
                .amount());

        IAEStack<?> combined = item(input(96));
        MEInventoryCrafting delivery = table(combined, liquid);
        assertRejected(PatternValidationException.Reason.TABLE_SHAPE, () -> layout.validateTable(delivery));
        assertEquals(
            Arrays.asList(
                "0=item/gtswn_assembly_test:ingredient@0#- x64",
                "2=item/gtswn_assembly_test:ingredient@0#- x32",
                "3=fluid/water#- x144"),
            MatrixDeliveryDiagnostics.expectedSlots(layout.rawInputs()));
        assertEquals(
            Arrays.asList("item/gtswn_assembly_test:ingredient@0#- x96", "fluid/water#- x144"),
            MatrixDeliveryDiagnostics.actualSlots(delivery));
        assertSame(combined, delivery.getAEStackInSlot(0));
        assertEquals(96L, combined.getStackSize());
        assertEquals(144L, liquid.getStackSize());
    }

    @Test
    public void snapshotUsesFullAeAmountAndSummarizesTagsWithoutDumpingContents() throws Exception {
        ItemStack tagged = new ItemStack(ingredient, 1, 5);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("privateName", "custom display value");
        tagged.setTagCompound(tag);
        long amount = (long) Integer.MAX_VALUE + 1;
        IAEStack<?> stack = item(tagged).setStackSize(amount);
        MEInventoryCrafting delivery = table(stack);

        assertEquals(
            Collections.singletonList(
                "item/gtswn_assembly_test:ingredient@5#" + Integer.toHexString(tag.hashCode()) + " x" + amount),
            MatrixDeliveryDiagnostics.actualSlots(delivery));
        assertSame(stack, delivery.getAEStackInSlot(0));
        assertEquals(amount, stack.getStackSize());
    }
}
