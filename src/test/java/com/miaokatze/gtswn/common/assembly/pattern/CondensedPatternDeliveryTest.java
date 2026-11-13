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

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.util.inv.MEInventoryCrafting;

/** Original pattern positions stay authoritative when a processing CPU transports condensed resources. */
public class CondensedPatternDeliveryTest {

    @BeforeClass
    public static void fixtures() throws Exception {
        System.out.println(
            "CONDENSED_LAYOUT_SOURCE=" + OrderedPatternLayout.class.getProtectionDomain()
                .getCodeSource());
        AssemblyPatternFixtures.initialize();
    }

    static IAEItemStack item(int meta, int amount) {
        return AssemblyPatternFixtures.item(new ItemStack(AssemblyPatternFixtures.ingredient, amount, meta));
    }

    static OrderedPatternLayout layout(IAEStack<?>... inputs) throws Exception {
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
    public void luvShapedOneCopyRestoresTwo64PositionsAndItsTwoFluidPositions() throws Exception {
        IAEStack<?> fluid = AssemblyPatternFixtures.fluid(FluidRegistryTestBootstrap.water(144));
        // Distinct metas stand for the rod, long rod, repeated wire and cable. Water has two independent positions.
        OrderedPatternLayout declared = layout(
            item(1, 1),
            null,
            item(2, 2),
            item(3, 64),
            item(3, 64),
            item(4, 2),
            fluid,
            AssemblyPatternFixtures.fluid(FluidRegistryTestBootstrap.water(250)));
        IAEItemStack wire = item(3, 128);
        IAEStack<?> water = AssemblyPatternFixtures.fluid(FluidRegistryTestBootstrap.water(394));
        MEInventoryCrafting table = AssemblyPatternFixtures.table(water, item(4, 2), wire, item(2, 2), item(1, 1));
        OrderedPatternLayout.Payload delivered = declared.validateTable(table);
        assertEquals(1, delivered.copies());
        assertEquals(
            5,
            delivered.items()
                .size());
        assertEquals(
            2,
            delivered.fluids()
                .size());
        assertArrayEquals(new long[] { 1, 2, 64, 64, 2 }, itemAmounts(delivered));
        assertEquals(
            3,
            delivered.items()
                .get(2)
                .rawIndex());
        assertEquals(
            4,
            delivered.items()
                .get(3)
                .rawIndex());
        assertEquals(
            2,
            delivered.items()
                .get(2)
                .kindOrdinal());
        assertEquals(
            3,
            delivered.items()
                .get(3)
                .kindOrdinal());
        assertEquals(
            2,
            delivered.items()
                .get(2)
                .tableIndex());
        assertEquals(
            2,
            delivered.items()
                .get(3)
                .tableIndex());
        assertEquals(
            144,
            delivered.fluids()
                .get(0)
                .amount());
        assertEquals(
            250,
            delivered.fluids()
                .get(1)
                .amount());
        assertEquals(
            0,
            delivered.fluids()
                .get(0)
                .tableIndex());
        assertEquals(
            0,
            delivered.fluids()
                .get(1)
                .tableIndex());
        assertSame(wire, table.getAEStackInSlot(2));
        assertEquals(128, wire.getStackSize());
        assertEquals(394, water.getStackSize());
    }

    @Test
    public void aUniformThreeCopyTransportKeepsEachPhysicalPositionSeparate() throws Exception {
        OrderedPatternLayout declared = layout(
            item(1, 1),
            item(3, 64),
            item(3, 64),
            AssemblyPatternFixtures.fluid(FluidRegistryTestBootstrap.water(100)));
        IAEItemStack wire = item(3, 384);
        IAEStack<?> water = AssemblyPatternFixtures.fluid(FluidRegistryTestBootstrap.water(300));
        MEInventoryCrafting table = AssemblyPatternFixtures.table(wire, water, item(1, 3));
        OrderedPatternLayout.Payload delivered = declared.validateTable(table);
        assertEquals(3, delivered.copies());
        assertArrayEquals(new long[] { 3, 192, 192 }, itemAmounts(delivered));
        assertEquals(
            300,
            delivered.fluids()
                .get(0)
                .amount());
        assertNotSame(
            delivered.items()
                .get(1)
                .itemStack(),
            delivered.items()
                .get(2)
                .itemStack());
        assertEquals(384, wire.getStackSize());
        assertEquals(300, water.getStackSize());
    }

    @Test
    public void fractionalAmountsAndMixedCopyRatiosAreRejectedWithoutChangingCargo() throws Exception {
        OrderedPatternLayout declared = layout(
            item(1, 1),
            item(3, 64),
            item(3, 64),
            AssemblyPatternFixtures.fluid(FluidRegistryTestBootstrap.water(100)));
        IAEItemStack shortWire = item(3, 127);
        MEInventoryCrafting shortTable = AssemblyPatternFixtures
            .table(item(1, 1), shortWire, AssemblyPatternFixtures.fluid(FluidRegistryTestBootstrap.water(100)));
        assertRejected(PatternValidationException.Reason.TABLE_AMOUNT, () -> declared.validateTable(shortTable));
        assertSame(shortWire, shortTable.getAEStackInSlot(1));
        assertEquals(127, shortWire.getStackSize());
        IAEItemStack wire = item(3, 256);
        IAEStack<?> water = AssemblyPatternFixtures.fluid(FluidRegistryTestBootstrap.water(100));
        MEInventoryCrafting mixed = AssemblyPatternFixtures.table(item(1, 2), wire, water);
        assertRejected(PatternValidationException.Reason.TABLE_AMOUNT, () -> declared.validateTable(mixed));
        assertEquals(256, wire.getStackSize());
        assertEquals(100, water.getStackSize());
    }

    @Test
    public void extraResourcesMissingResourcesAndChangedMetaAreRejected() throws Exception {
        OrderedPatternLayout declared = layout(item(1, 1), item(3, 64), item(3, 64));
        MEInventoryCrafting extra = AssemblyPatternFixtures.table(item(1, 1), item(3, 128), item(5, 1));
        assertRejected(PatternValidationException.Reason.TABLE_RESOURCE, () -> declared.validateTable(extra));
        MEInventoryCrafting missing = AssemblyPatternFixtures.table(item(3, 128));
        assertRejected(PatternValidationException.Reason.TABLE_RESOURCE, () -> declared.validateTable(missing));
        MEInventoryCrafting differentMeta = AssemblyPatternFixtures.table(item(1, 1), item(4, 128));
        assertRejected(PatternValidationException.Reason.TABLE_RESOURCE, () -> declared.validateTable(differentMeta));
        assertEquals(
            128,
            differentMeta.getAEStackInSlot(1)
                .getStackSize());
    }

    @Test
    public void taggedResourcesNeverMergeAcrossNbtAndDetachedResultsCannotMutateTheSource() throws Exception {
        ItemStack tagged = new ItemStack(AssemblyPatternFixtures.ingredient, 64, 3);
        tagged.setTagCompound(new NBTTagCompound());
        tagged.getTagCompound()
            .setString("grade", "exact");
        IAEItemStack part = AssemblyPatternFixtures.item(tagged);
        ICraftingPatternDetails details = AssemblyPatternFixtures.standard(
            new IAEItemStack[] { part, part.copy() },
            AssemblyPatternFixtures.item(AssemblyPatternFixtures.output(1)));
        NBTTagCompound original = (NBTTagCompound) details.getPattern()
            .getTagCompound()
            .copy();
        OrderedPatternLayout declared = OrderedPatternLayout.decode(details);
        IAEItemStack cargo = part.copy()
            .setStackSize(128);
        MEInventoryCrafting table = AssemblyPatternFixtures.table(cargo);
        OrderedPatternLayout.Payload accepted = declared.validateTable(table);
        accepted.items()
            .get(0)
            .itemStack()
            .getTagCompound()
            .setString("grade", "edited");
        assertEquals(
            "exact",
            cargo.getItemStack()
                .getTagCompound()
                .getString("grade"));
        assertEquals(
            original,
            details.getPattern()
                .getTagCompound());
        assertSame(cargo, table.getAEStackInSlot(0));
        ItemStack wrong = tagged.copy();
        wrong.stackSize = 128;
        wrong.getTagCompound()
            .setString("grade", "other");
        MEInventoryCrafting wrongTable = AssemblyPatternFixtures.table(AssemblyPatternFixtures.item(wrong));
        assertRejected(PatternValidationException.Reason.TABLE_RESOURCE, () -> declared.validateTable(wrongTable));
        assertEquals(
            original,
            details.getPattern()
                .getTagCompound());
    }

    @Test
    public void differentlyNamedStacksRemainSeparateWhileOtherRepeatedResourcesAreCondensed() throws Exception {
        ItemStack left = new ItemStack(AssemblyPatternFixtures.ingredient, 64, 3);
        ItemStack right = left.copy();
        left.setStackDisplayName("wire-left");
        right.setStackDisplayName("wire-right");
        assertSame(left.getItem(), right.getItem());
        assertEquals(left.getItemDamage(), right.getItemDamage());
        assertFalse(ItemStack.areItemStackTagsEqual(left, right));
        IAEItemStack leftCargo = AssemblyPatternFixtures.item(left);
        IAEItemStack rightCargo = AssemblyPatternFixtures.item(right);
        OrderedPatternLayout declared = layout(leftCargo, rightCargo, item(4, 1), item(4, 1));
        // The two named wires stay distinct; only the other repeated resource condenses. Reordering exercises
        // the same transport restoration path as ECO without treating the wire names as interchangeable.
        MEInventoryCrafting table = AssemblyPatternFixtures.table(item(4, 2), rightCargo, leftCargo);
        OrderedPatternLayout.Payload accepted = declared.validateTable(table);
        assertEquals(1, accepted.copies());
        assertArrayEquals(new long[] { 64, 64, 1, 1 }, itemAmounts(accepted));
        assertEquals(
            left.getTagCompound(),
            accepted.items()
                .get(0)
                .itemStack()
                .getTagCompound());
        assertEquals(
            right.getTagCompound(),
            accepted.items()
                .get(1)
                .itemStack()
                .getTagCompound());
        assertEquals(
            2,
            accepted.items()
                .get(0)
                .tableIndex());
        assertEquals(
            1,
            accepted.items()
                .get(1)
                .tableIndex());
        assertEquals(
            0,
            accepted.items()
                .get(2)
                .tableIndex());
        assertEquals(
            0,
            accepted.items()
                .get(3)
                .tableIndex());
        accepted.items()
            .get(0)
            .itemStack()
            .setStackDisplayName("detached-edit");
        assertEquals(
            left.getTagCompound(),
            leftCargo.getItemStack()
                .getTagCompound());
        assertEquals(
            right.getTagCompound(),
            rightCargo.getItemStack()
                .getTagCompound());
        assertSame(leftCargo, table.getAEStackInSlot(2));
        assertSame(rightCargo, table.getAEStackInSlot(1));
        assertEquals(64, leftCargo.getStackSize());
        assertEquals(64, rightCargo.getStackSize());
    }

    @Test
    public void taggedFluidAndTransportOverflowRemainRejectedBeforeAnyConversion() throws Exception {
        OrderedPatternLayout declared = layout(
            item(1, 1),
            item(1, 1),
            AssemblyPatternFixtures.fluid(FluidRegistryTestBootstrap.water(100)));
        FluidStack tagged = FluidRegistryTestBootstrap.water(100);
        tagged.tag = new NBTTagCompound();
        tagged.tag.setString("grade", "not_ordinary");
        MEInventoryCrafting special = AssemblyPatternFixtures.table(item(1, 2), AssemblyPatternFixtures.fluid(tagged));
        assertRejected(PatternValidationException.Reason.SPECIAL_FLUID_TAG, () -> declared.validateTable(special));
        IAEItemStack huge = item(1, 1).setStackSize((long) Integer.MAX_VALUE + 1);
        MEInventoryCrafting overflow = AssemblyPatternFixtures
            .table(huge, AssemblyPatternFixtures.fluid(FluidRegistryTestBootstrap.water(100)));
        assertRejected(PatternValidationException.Reason.AMOUNT_OVERFLOW, () -> declared.validateTable(overflow));
        assertEquals((long) Integer.MAX_VALUE + 1, huge.getStackSize());
        assertSame(huge, overflow.getAEStackInSlot(0));
    }

    @Test
    public void canonicalCondensationDoesNotAcceptSwappedDuplicatePositionalAmounts() throws Exception {
        OrderedPatternLayout declared = layout(item(1, 2), item(1, 3));
        IAEItemStack first = item(1, 3), second = item(1, 2);
        MEInventoryCrafting positional = AssemblyPatternFixtures.table(first, second);
        assertRejected(PatternValidationException.Reason.TABLE_AMOUNT, () -> declared.validateTable(positional));
        assertSame(first, positional.getAEStackInSlot(0));
        assertSame(second, positional.getAEStackInSlot(1));
        assertEquals(3, first.getStackSize());
        assertEquals(2, second.getStackSize());
    }

    private static long[] itemAmounts(OrderedPatternLayout.Payload payload) {
        long[] result = new long[payload.items()
            .size()];
        for (int i = 0; i < result.length; i++) result[i] = payload.items()
            .get(i)
            .amount();
        return result;
    }
}
