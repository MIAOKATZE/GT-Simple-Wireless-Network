// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.assembly.pattern;

import static com.miaokatze.gtswn.common.assembly.pattern.OrderedPatternLayoutTest.assertRejected;
import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidStack;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.miaokatze.gtswn.test.FluidRegistryTestBootstrap;

import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import gregtech.api.metatileentity.implementations.MTEHatchInputBus;
import gregtech.api.util.GTRecipe.RecipeAssemblyLine;

public class AssemblyRecipeMatchTest {

    private static Field feather;
    private static Object previousFeather;

    @BeforeClass
    public static void fixtures() throws Exception {
        AssemblyPatternFixtures.initialize();
        // Native GTUtility uses vanilla feather only as the ItemStack damage accessor.
        feather = Items.class.getDeclaredField("feather");
        previousFeather = AssemblyPatternFixtures.setStatic(feather, AssemblyPatternFixtures.ingredient);
    }

    @AfterClass
    public static void restoreVanillaAccessor() throws Exception {
        if (feather != null) AssemblyPatternFixtures.setStatic(feather, previousFeather);
    }

    private static OrderedPatternLayout layout(int input, int output) throws Exception {
        return OrderedPatternLayout.decode(
            AssemblyPatternFixtures.standard(
                new IAEItemStack[] { AssemblyPatternFixtures.item(AssemblyPatternFixtures.input(input)) },
                AssemblyPatternFixtures.item(AssemblyPatternFixtures.output(output))));
    }

    private static RecipeAssemblyLine recipe(int input, int output) {
        return AssemblyPatternFixtures.recipe(
            new ItemStack[] { AssemblyPatternFixtures.input(input) },
            new FluidStack[0],
            AssemblyPatternFixtures.output(output));
    }

    @Test
    public void identicalInputsCannotSelectAnEarlierDifferentOutputOrOutputCount() throws Exception {
        OrderedPatternLayout layout = layout(2, 4);
        RecipeAssemblyLine wrongProduct = recipe(2, 4), wrongCount = recipe(2, 1), wanted = recipe(2, 4);
        wrongProduct.mOutput = new ItemStack(AssemblyPatternFixtures.anotherProduct, 4);
        AssemblyRecipeMatch match = AssemblyRecipeMatch.bind(layout, Arrays.asList(wrongProduct, wrongCount, wanted));
        assertSame(wanted, match.recipe());
        assertEquals(AssemblyRecipeKey.of(wanted), match.key());
        assertRejected(
            PatternValidationException.Reason.NO_AUTHORIZED_RECIPE,
            () -> AssemblyRecipeMatch.bind(layout, Collections.singletonList(wrongCount)));
    }

    @Test
    public void sameInputAndOutputWithDifferentNativeBehaviorIsAmbiguous() throws Exception {
        OrderedPatternLayout layout = layout(2, 1);
        RecipeAssemblyLine first = recipe(2, 1), second = recipe(2, 1);
        second.mDuration++;
        assertRejected(
            PatternValidationException.Reason.AMBIGUOUS_RECIPE,
            () -> AssemblyRecipeMatch.bind(layout, Arrays.asList(first, second)));
        assertEquals(
            AssemblyRecipeKey.of(first),
            AssemblyRecipeMatch.bind(layout, Arrays.asList(first, recipe(2, 1)))
                .key());
    }

    @Test
    public void firstNativeAlternativeWinsEvenWhenALaterAlternativeHasTheRequestedAmount() throws Exception {
        OrderedPatternLayout layout = layout(3, 1);
        RecipeAssemblyLine candidate = recipe(2, 1);
        candidate.mOreDictAlt[0] = new ItemStack[] { AssemblyPatternFixtures.input(2),
            AssemblyPatternFixtures.input(3) };
        assertRejected(
            PatternValidationException.Reason.NO_AUTHORIZED_RECIPE,
            () -> AssemblyRecipeMatch.bind(layout, Collections.singletonList(candidate)));
        candidate.mOreDictAlt[0] = new ItemStack[] { AssemblyPatternFixtures.input(3),
            AssemblyPatternFixtures.input(2) };
        assertSame(
            candidate,
            AssemblyRecipeMatch.bind(layout, Collections.singletonList(candidate))
                .recipe());
    }

    @Test
    public void zeroConsumptionIsAPresentItemAndIsExplicitlyRejected() throws Exception {
        OrderedPatternLayout layout = layout(1, 1);
        RecipeAssemblyLine candidate = recipe(0, 1);
        assertRejected(
            PatternValidationException.Reason.ZERO_CONSUMPTION,
            () -> AssemblyRecipeMatch.bind(layout, Collections.singletonList(candidate)));
        assertEquals(
            1,
            layout.items()
                .size());
    }

    @Test
    public void aConsistentMultiCopyPatternBindsTheSameNativeSingleCopyRecipe() throws Exception {
        OrderedPatternLayout layout = layout(4, 2);
        RecipeAssemblyLine candidate = recipe(2, 1);
        assertSame(
            candidate,
            AssemblyRecipeMatch.bind(layout, Collections.singletonList(candidate))
                .recipe());
        assertEquals(
            4L,
            layout.items()
                .get(0)
                .amount());
    }

    @Test
    public void aSingleNativePositionAbove64UsesItsRealRequirementWithoutForcingParallelism() throws Exception {
        OrderedPatternLayout declared = layout(128, 1);
        OrderedPatternLayout.Payload payload = declared.validateTable(
            AssemblyPatternFixtures.table(AssemblyPatternFixtures.item(AssemblyPatternFixtures.input(256))));
        RecipeAssemblyLine recipe = recipe(128, 1);
        assertEquals(2, payload.copies());
        assertSame(
            recipe,
            AssemblyRecipeMatch.bind(declared, payload, Collections.singletonList(recipe))
                .recipe());
        ArrayList<MTEHatchInputBus> buses = stocks(payload);
        int[] needs = RecipeAssemblyLine.getItemConsumptionAmountArray(buses, recipe);
        assertArrayEquals(new int[] { 128 }, needs);
        assertEquals(
            1.0,
            RecipeAssemblyLine.maxParallelCalculatedByInputItems(buses, 1, needs, Collections.emptyMap()),
            0.0);
        RecipeAssemblyLine.consumeInputItems(buses, 1, needs, Collections.emptyMap());
        assertEquals(
            128,
            buses.get(0)
                .getFirstStack().stackSize);
        RecipeAssemblyLine.consumeInputItems(buses, 1, needs, Collections.emptyMap());
        assertEquals(
            0,
            buses.get(0)
                .getFirstStack().stackSize);
    }

    @Test
    public void condensedThreeCopyCargoBindsThenNativeConsumptionTakesOneCopyAtATime() throws Exception {
        IAEItemStack rod = CondensedPatternDeliveryTest.item(1, 1);
        IAEItemStack wire = CondensedPatternDeliveryTest.item(3, 64);
        OrderedPatternLayout declared = CondensedPatternDeliveryTest.layout(rod, wire, wire.copy());
        IAEItemStack transportedWire = CondensedPatternDeliveryTest.item(3, 384);
        IAEItemStack transportedRod = CondensedPatternDeliveryTest.item(1, 3);
        OrderedPatternLayout.Payload payload = declared
            .validateDelivery(AssemblyPatternFixtures.table(transportedWire, transportedRod), () -> true);
        RecipeAssemblyLine recipe = AssemblyPatternFixtures.recipe(
            new ItemStack[] { rod.getItemStack(), wire.getItemStack(), wire.getItemStack() },
            new FluidStack[0],
            AssemblyPatternFixtures.output(1));
        assertEquals(3, payload.copies());
        assertSame(
            recipe,
            AssemblyRecipeMatch.bind(declared, payload, Collections.singletonList(recipe))
                .recipe());
        ArrayList<MTEHatchInputBus> buses = stocks(payload);
        for (int remaining = 3; remaining > 0; remaining--) {
            int[] needs = RecipeAssemblyLine.getItemConsumptionAmountArray(buses, recipe);
            assertArrayEquals(new int[] { 1, 64, 64 }, needs);
            assertEquals(
                1.0,
                RecipeAssemblyLine.maxParallelCalculatedByInputItems(buses, 1, needs, Collections.emptyMap()),
                0.0);
            RecipeAssemblyLine.consumeInputItems(buses, 1, needs, Collections.emptyMap());
            assertEquals(
                remaining - 1,
                buses.get(0)
                    .getFirstStack().stackSize);
            assertEquals(
                (remaining - 1) * 64,
                buses.get(1)
                    .getFirstStack().stackSize);
            assertEquals(
                (remaining - 1) * 64,
                buses.get(2)
                    .getFirstStack().stackSize);
        }
        assertEquals(384, transportedWire.getStackSize());
        assertEquals(3, transportedRod.getStackSize());
    }

    /** Actual GT requirement/consumption methods see real owned stacks, with only tile construction bypassed. */
    private static final class StockBus extends MTEHatchInputBus {

        ItemStack stock;

        private StockBus() {
            super("gtswn_recipe_contract", 6, new String[0], new gregtech.api.interfaces.ITexture[0][][]);
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        public ItemStack getFirstStack() {
            return stock;
        }
    }

    private static ArrayList<MTEHatchInputBus> stocks(OrderedPatternLayout.Payload payload) throws Exception {
        Field singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        sun.misc.Unsafe allocator = (sun.misc.Unsafe) singleton.get(null);
        ArrayList<MTEHatchInputBus> buses = new ArrayList<>();
        for (OrderedPatternLayout.Input input : payload.items()) {
            StockBus bus = (StockBus) allocator.allocateInstance(StockBus.class);
            bus.stock = input.itemStack();
            buses.add(bus);
        }
        return buses;
    }

    @Test
    public void mixedMultipleOutputsDecodeForGenericMachinesButCannotBindANativeAssemblyRecipe() throws Exception {
        IAEStack<?> input = AssemblyPatternFixtures.item(AssemblyPatternFixtures.input(2));
        IAEStack<?> product = AssemblyPatternFixtures.item(AssemblyPatternFixtures.output(1));
        IAEStack<?> fluid = AssemblyPatternFixtures.fluid(FluidRegistryTestBootstrap.water(50));
        IAEStack<?> byproduct = AssemblyPatternFixtures.item(new ItemStack(AssemblyPatternFixtures.anotherProduct, 3));
        OrderedPatternLayout layout = OrderedPatternLayout
            .decode(AssemblyPatternFixtures.ultimate(new IAEStack<?>[] { input }, product, fluid, byproduct));
        assertEquals(
            3,
            layout.outputs()
                .size());
        assertEquals(
            OrderedPatternLayout.Kind.ITEM,
            layout.outputs()
                .get(0)
                .kind());
        assertEquals(
            OrderedPatternLayout.Kind.FLUID,
            layout.outputs()
                .get(1)
                .kind());
        assertEquals(
            3,
            layout.outputs()
                .get(2)
                .amount());
        assertNull(layout.output());
        RecipeAssemblyLine candidate = recipe(2, 1);
        assertRejected(
            PatternValidationException.Reason.UNSUPPORTED_OUTPUT,
            () -> AssemblyRecipeMatch.bind(layout, Collections.singletonList(candidate)));
    }

    @Test
    public void nativeNbtMatchingAndExactMaterialCustodyRemainSeparate() throws Exception {
        ItemStack delivered = AssemblyPatternFixtures.input(2);
        delivered.setTagCompound(new NBTTagCompound());
        delivered.getTagCompound()
            .setString("variant", "received");
        OrderedPatternLayout layout = OrderedPatternLayout.decode(
            AssemblyPatternFixtures.standard(
                new IAEItemStack[] { AssemblyPatternFixtures.item(delivered) },
                AssemblyPatternFixtures.item(AssemblyPatternFixtures.output(1))));
        RecipeAssemblyLine candidate = recipe(2, 1);
        candidate.mInputs[0].setTagCompound(new NBTTagCompound());
        candidate.mInputs[0].getTagCompound()
            .setString("variant", "recipe");
        assertSame(
            candidate,
            AssemblyRecipeMatch.bind(layout, Collections.singletonList(candidate))
                .recipe());
        assertEquals(
            "received",
            layout.items()
                .get(0)
                .itemStack()
                .getTagCompound()
                .getString("variant"));
        assertEquals(
            "recipe",
            candidate.mInputs[0].getTagCompound()
                .getString("variant"));
    }
}
