// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.assembly.pattern;

import static com.miaokatze.gtswn.common.assembly.pattern.OrderedPatternLayoutTest.assertRejected;
import static org.junit.Assert.*;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidStack;

import org.junit.BeforeClass;
import org.junit.Test;

import com.miaokatze.gtswn.test.FluidRegistryTestBootstrap;

import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.util.inv.MEInventoryCrafting;

/** Validation of mixed and sparse positional delivery tables. */
public class PositionedPatternDeliveryTest {

    @BeforeClass
    public static void fixtures() throws Exception {
        AssemblyPatternFixtures.initialize();
    }

    private static IAEItemStack item(int meta, int amount) {
        return AssemblyPatternFixtures.item(new ItemStack(AssemblyPatternFixtures.ingredient, amount, meta));
    }

    private static OrderedPatternLayout layout(IAEStack<?>... inputs) throws Exception {
        return OrderedPatternLayout.decode(
            AssemblyPatternFixtures.ultimate(inputs, AssemblyPatternFixtures.item(AssemblyPatternFixtures.output(1))));
    }

    @Test
    public void aSinglePositionRequiring128ItemsIsOneCopyNotTwo() throws Exception {
        OrderedPatternLayout declared = layout(item(3, 128));
        IAEItemStack cargo = item(3, 128);
        MEInventoryCrafting table = AssemblyPatternFixtures.table(cargo);
        OrderedPatternLayout.Payload delivered = declared.validateTable(table);
        assertEquals(1, delivered.copies());
        assertEquals(
            1,
            delivered.items()
                .size());
        assertEquals(
            128,
            delivered.items()
                .get(0)
                .amount());
        assertSame(cargo, table.getAEStackInSlot(0));
        assertEquals(128, cargo.getStackSize());
    }

    @Test
    public void sparseMixedDeliveryKeepsTheOriginalPositionsOfRepeatedItemsAndFluids() throws Exception {
        IAEItemStack wire = item(3, 64);
        IAEStack<?> water1 = AssemblyPatternFixtures.fluid(FluidRegistryTestBootstrap.water(144));
        IAEStack<?> water2 = AssemblyPatternFixtures.fluid(FluidRegistryTestBootstrap.water(250));
        IAEStack<?>[] slots = { item(1, 1), null, item(2, 2), wire, wire.copy(), item(4, 2), water1, water2 };
        OrderedPatternLayout declared = layout(slots);
        MEInventoryCrafting table = AssemblyPatternFixtures.table(slots);
        OrderedPatternLayout.Payload delivery = declared.validateTable(table);
        assertEquals(1, delivery.copies());
        assertArrayEquals(
            new int[] { 0, 2, 3, 4, 5 },
            delivery.items()
                .stream()
                .mapToInt(OrderedPatternLayout.Input::tableIndex)
                .toArray());
        assertArrayEquals(
            new int[] { 6, 7 },
            delivery.fluids()
                .stream()
                .mapToInt(OrderedPatternLayout.Input::tableIndex)
                .toArray());
        assertEquals(64, wire.getStackSize());
        assertEquals(144, water1.getStackSize());
        assertEquals(250, water2.getStackSize());
    }

    @Test
    public void swappedNamedItemsRemainDistinct() throws Exception {
        ItemStack left = new ItemStack(AssemblyPatternFixtures.ingredient, 64, 3);
        ItemStack right = left.copy();
        left.setStackDisplayName("wire-left");
        right.setStackDisplayName("wire-right");
        IAEItemStack leftCargo = AssemblyPatternFixtures.item(left);
        IAEItemStack rightCargo = AssemblyPatternFixtures.item(right);
        OrderedPatternLayout declared = layout(leftCargo, rightCargo);
        OrderedPatternLayout.Payload accepted = declared
            .validateTable(AssemblyPatternFixtures.table(leftCargo, rightCargo));
        assertEquals(1, accepted.copies());
        assertEquals(
            "wire-left",
            accepted.items()
                .get(0)
                .itemStack()
                .getDisplayName());
        assertEquals(
            "wire-right",
            accepted.items()
                .get(1)
                .itemStack()
                .getDisplayName());
        MEInventoryCrafting swapped = AssemblyPatternFixtures.table(rightCargo, leftCargo);
        assertRejected(PatternValidationException.Reason.TABLE_RESOURCE, () -> declared.validateTable(swapped));
        assertSame(rightCargo, swapped.getAEStackInSlot(0));
        assertSame(leftCargo, swapped.getAEStackInSlot(1));
    }

    @Test
    public void taggedFluidAndOversizedItemAmountsRemainRejectedBeforeConversion() throws Exception {
        IAEItemStack part = item(1, 1);
        IAEStack<?> water = AssemblyPatternFixtures.fluid(FluidRegistryTestBootstrap.water(100));
        OrderedPatternLayout declared = layout(part, part.copy(), water);

        FluidStack tagged = FluidRegistryTestBootstrap.water(100);
        tagged.tag = new NBTTagCompound();
        tagged.tag.setString("grade", "special");
        MEInventoryCrafting special = AssemblyPatternFixtures
            .table(part, part.copy(), AssemblyPatternFixtures.fluid(tagged));
        assertRejected(PatternValidationException.Reason.SPECIAL_FLUID_TAG, () -> declared.validateTable(special));

        IAEItemStack huge = part.copy()
            .setStackSize((long) Integer.MAX_VALUE + 1);
        MEInventoryCrafting overflow = AssemblyPatternFixtures.table(huge, part.copy(), water);
        assertRejected(PatternValidationException.Reason.AMOUNT_OVERFLOW, () -> declared.validateTable(overflow));
        assertEquals((long) Integer.MAX_VALUE + 1, huge.getStackSize());
        assertSame(huge, overflow.getAEStackInSlot(0));
    }
}
