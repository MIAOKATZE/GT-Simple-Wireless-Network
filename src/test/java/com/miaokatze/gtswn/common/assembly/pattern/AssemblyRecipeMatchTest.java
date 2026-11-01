package com.miaokatze.gtswn.common.assembly.pattern;

import static com.miaokatze.gtswn.common.assembly.pattern.OrderedPatternLayoutTest.assertRejected;
import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidStack;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import appeng.api.storage.data.IAEItemStack;
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
    public void aConsistentMultiCopyPatternIsRejectedBeforeAcceptingAnyMaterial() throws Exception {
        OrderedPatternLayout layout = layout(4, 2);
        RecipeAssemblyLine candidate = recipe(2, 1);
        assertRejected(
            PatternValidationException.Reason.MULTIPLE_COPIES,
            () -> AssemblyRecipeMatch.bind(layout, Collections.singletonList(candidate)));
        assertEquals(
            4L,
            layout.items()
                .get(0)
                .amount());
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
