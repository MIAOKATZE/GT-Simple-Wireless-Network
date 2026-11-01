package com.miaokatze.gtswn.common.assembly.pattern;

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

public class OrderedPatternLayoutTest {

    @BeforeClass
    public static void fixtures() throws Exception {
        AssemblyPatternFixtures.initialize();
    }

    @Test
    public void duplicateRawSlotsRemainIndependentAcrossBothCpuTableShapes() throws Exception {
        IAEItemStack first = AssemblyPatternFixtures.item(AssemblyPatternFixtures.input(2));
        IAEItemStack second = AssemblyPatternFixtures.item(AssemblyPatternFixtures.input(3));
        ICraftingPatternDetails details = AssemblyPatternFixtures.standard(
            new IAEItemStack[] { first, null, second },
            AssemblyPatternFixtures.item(AssemblyPatternFixtures.output(1)));
        OrderedPatternLayout layout = OrderedPatternLayout.decode(details);
        assertEquals(
            3,
            layout.rawInputs()
                .size());
        assertEquals(
            OrderedPatternLayout.Kind.EMPTY,
            layout.rawInputs()
                .get(1)
                .kind());
        assertEquals(
            2,
            layout.items()
                .size());
        assertEquals(
            2,
            layout.items()
                .get(1)
                .rawIndex());
        assertEquals(
            1,
            layout.items()
                .get(1)
                .transportIndex());
        assertEquals(
            1,
            layout.items()
                .get(1)
                .kindOrdinal());
        assertEquals(
            3L,
            layout.items()
                .get(1)
                .amount());
        OrderedPatternLayout.Payload compact = layout.validateTable(AssemblyPatternFixtures.table(first, second));
        OrderedPatternLayout.Payload raw = layout.validateTable(AssemblyPatternFixtures.table(first, null, second));
        assertEquals(
            1,
            compact.items()
                .get(1)
                .tableIndex());
        assertEquals(
            2,
            raw.items()
                .get(1)
                .tableIndex());
        assertNotSame(
            compact.items()
                .get(0)
                .itemStack(),
            compact.items()
                .get(1)
                .itemStack());
        compact.items()
            .get(0)
            .itemStack().stackSize = 99;
        assertEquals(
            2L,
            layout.items()
                .get(0)
                .amount());
        assertEquals(2L, first.getStackSize());
    }

    @Test
    public void interleavedFluidsHaveTheirOwnOrdinalWithoutConsumingAnItemPosition() throws Exception {
        IAEStack<?> water = AssemblyPatternFixtures.fluid(FluidRegistryTestBootstrap.water(144));
        IAEItemStack first = AssemblyPatternFixtures.item(AssemblyPatternFixtures.input(1));
        IAEItemStack second = AssemblyPatternFixtures.item(AssemblyPatternFixtures.input(2));
        OrderedPatternLayout layout = OrderedPatternLayout.decode(
            AssemblyPatternFixtures.ultimate(
                new IAEStack<?>[] { first, null, water, second },
                AssemblyPatternFixtures.item(AssemblyPatternFixtures.output(1))));
        assertEquals(
            1,
            layout.items()
                .get(1)
                .kindOrdinal());
        assertEquals(
            3,
            layout.items()
                .get(1)
                .rawIndex());
        assertEquals(
            0,
            layout.fluids()
                .get(0)
                .kindOrdinal());
        assertEquals(
            2,
            layout.fluids()
                .get(0)
                .rawIndex());
        assertEquals(
            144L,
            layout.validateTable(AssemblyPatternFixtures.table(first, water, second))
                .fluids()
                .get(0)
                .amount());
    }

    @Test
    public void acceptanceAndRejectionNeverChangeTheSourcePatternOrTheDeliveryObjects() throws Exception {
        ItemStack tagged = AssemblyPatternFixtures.input(2);
        tagged.setTagCompound(new NBTTagCompound());
        tagged.getTagCompound()
            .setString("variant", "received");
        IAEItemStack first = AssemblyPatternFixtures.item(tagged);
        ICraftingPatternDetails details = AssemblyPatternFixtures
            .standard(new IAEItemStack[] { first }, AssemblyPatternFixtures.item(AssemblyPatternFixtures.output(1)));
        NBTTagCompound before = (NBTTagCompound) details.getPattern()
            .getTagCompound()
            .copy();
        OrderedPatternLayout layout = OrderedPatternLayout.decode(details);
        MEInventoryCrafting table = AssemblyPatternFixtures.table(first);
        NBTTagCompound delivered = (NBTTagCompound) table.getStackInSlot(0)
            .getTagCompound()
            .copy();
        layout.validateTable(table)
            .items()
            .get(0)
            .itemStack()
            .getTagCompound()
            .setString("variant", "edited");
        assertEquals(
            before,
            details.getPattern()
                .getTagCompound());
        assertEquals(
            delivered,
            table.getStackInSlot(0)
                .getTagCompound());
        assertSame(first, table.getAEStackInSlot(0));
        first.setStackSize(3);
        assertRejected(PatternValidationException.Reason.TABLE_AMOUNT, () -> layout.validateTable(table));
        assertEquals(3L, first.getStackSize());
        assertEquals(
            before,
            details.getPattern()
                .getTagCompound());
    }

    @Test
    public void condensedAndMisorderedDeliveriesAreRejected() throws Exception {
        IAEItemStack first = AssemblyPatternFixtures.item(AssemblyPatternFixtures.input(2));
        IAEItemStack second = AssemblyPatternFixtures.item(AssemblyPatternFixtures.input(3));
        OrderedPatternLayout layout = OrderedPatternLayout.decode(
            AssemblyPatternFixtures.standard(
                new IAEItemStack[] { first, null, second },
                AssemblyPatternFixtures.item(AssemblyPatternFixtures.output(1))));
        MEInventoryCrafting condensed = AssemblyPatternFixtures
            .table(AssemblyPatternFixtures.item(AssemblyPatternFixtures.input(5)));
        assertRejected(PatternValidationException.Reason.TABLE_SHAPE, () -> layout.validateTable(condensed));
        MEInventoryCrafting reversed = AssemblyPatternFixtures.table(second, first);
        assertRejected(PatternValidationException.Reason.TABLE_AMOUNT, () -> layout.validateTable(reversed));
    }

    @Test
    public void longAmountsAreRejectedBeforeConversionCanClampThem() throws Exception {
        IAEItemStack large = AssemblyPatternFixtures.item(AssemblyPatternFixtures.input(1));
        large.setStackSize(1L << 32);
        ICraftingPatternDetails details = AssemblyPatternFixtures
            .standard(new IAEItemStack[] { large }, AssemblyPatternFixtures.item(AssemblyPatternFixtures.output(1)));
        assertRejected(PatternValidationException.Reason.AMOUNT_OVERFLOW, () -> OrderedPatternLayout.decode(details));
        assertEquals(1L << 32, large.getStackSize());
        IAEStack<?> fluid = AssemblyPatternFixtures.fluid(FluidRegistryTestBootstrap.water(1));
        fluid.setStackSize(Long.MAX_VALUE);
        ICraftingPatternDetails fluidDetails = AssemblyPatternFixtures.ultimate(
            new IAEStack<?>[] { AssemblyPatternFixtures.item(AssemblyPatternFixtures.input(1)), fluid },
            AssemblyPatternFixtures.item(AssemblyPatternFixtures.output(1)));
        assertRejected(
            PatternValidationException.Reason.AMOUNT_OVERFLOW,
            () -> OrderedPatternLayout.decode(fluidDetails));
    }

    @Test
    public void unsupportedPatternModesAreRejectedBeforeCallingTheWorldDecoder() throws Exception {
        ICraftingPatternDetails details = AssemblyPatternFixtures.standard(
            new IAEItemStack[] { AssemblyPatternFixtures.item(AssemblyPatternFixtures.input(1)) },
            AssemblyPatternFixtures.item(AssemblyPatternFixtures.output(1)));
        ItemStack source = details.getPattern();
        source.getTagCompound()
            .setBoolean("crafting", true);
        NBTTagCompound before = (NBTTagCompound) source.getTagCompound()
            .copy();
        assertRejected(
            PatternValidationException.Reason.CRAFTING_PATTERN,
            () -> OrderedPatternLayout.decode(source, null));
        assertEquals(before, source.getTagCompound());
        source.getTagCompound()
            .removeTag("crafting");
        source.getTagCompound()
            .setBoolean("substitute", true);
        assertRejected(
            PatternValidationException.Reason.SUBSTITUTE_PATTERN,
            () -> OrderedPatternLayout.decode(details));
        source.getTagCompound()
            .removeTag("substitute");
        source.getTagCompound()
            .setBoolean("tunnel", true);
        assertRejected(
            PatternValidationException.Reason.INPUT_ONLY_PATTERN,
            () -> OrderedPatternLayout.decode(details));
    }

    @Test
    public void encodedFluidTagsAreRejectedEvenWhenTheDeliveryViewHasNoTag() throws Exception {
        FluidStack nativeFluid = FluidRegistryTestBootstrap.water(144);
        nativeFluid.tag = new NBTTagCompound();
        nativeFluid.tag.setString("grade", "special");
        IAEStack<?> fluid = AssemblyPatternFixtures.fluid(FluidRegistryTestBootstrap.water(144));
        ICraftingPatternDetails details = AssemblyPatternFixtures.ultimate(
            new IAEStack<?>[] { AssemblyPatternFixtures.item(AssemblyPatternFixtures.input(1)), fluid },
            AssemblyPatternFixtures.item(AssemblyPatternFixtures.output(1)));
        details.getPattern()
            .getTagCompound()
            .getTagList("in", 10)
            .getCompoundTagAt(1)
            .setTag("tag", nativeFluid.tag.copy());
        assertRejected(PatternValidationException.Reason.SPECIAL_FLUID_TAG, () -> OrderedPatternLayout.decode(details));
    }

    static void assertRejected(PatternValidationException.Reason reason, Runnable operation) {
        try {
            operation.run();
            fail("Expected " + reason);
        } catch (PatternValidationException rejection) {
            assertEquals(reason, rejection.reason());
        }
    }
}
