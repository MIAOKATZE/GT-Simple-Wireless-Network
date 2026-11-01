package com.miaokatze.gtswn.common.assembly.pattern;

import static com.miaokatze.gtswn.common.assembly.pattern.PatternValidationException.Reason.*;

import java.util.Collection;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import com.miaokatze.gtswn.common.assembly.pattern.OrderedPatternLayout.Input;
import com.miaokatze.gtswn.common.assembly.pattern.OrderedPatternLayout.Payload;

import gregtech.api.util.GTRecipe.RecipeAssemblyLine;

/** Binds a single-copy processing declaration to a unique recipe in the current native authorization set. */
public final class AssemblyRecipeMatch {

    private enum Comparison {
        NO_MATCH,
        EXACT,
        MULTIPLE_COPIES,
        ZERO_CONSUMPTION,
        SPECIAL_FLUID_TAG
    }

    private final RecipeAssemblyLine recipe;
    private final AssemblyRecipeKey key;

    private AssemblyRecipeMatch(RecipeAssemblyLine recipe, AssemblyRecipeKey key) {
        this.recipe = recipe;
        this.key = key;
    }

    public static AssemblyRecipeMatch bind(OrderedPatternLayout layout, Collection<RecipeAssemblyLine> authorized) {
        return bind(layout, layout.declaredPayload(), authorized);
    }

    public static AssemblyRecipeMatch bind(OrderedPatternLayout layout, Payload payload,
        Collection<RecipeAssemblyLine> authorized) {
        if (!layout.owns(payload)) throw OrderedPatternLayout.rejected(TABLE_RESOURCE, -1);
        AssemblyRecipeMatch selected = null;
        PatternValidationException.Reason rejection = NO_AUTHORIZED_RECIPE;
        for (RecipeAssemblyLine candidate : authorized) {
            Comparison comparison = compare(layout, payload, candidate);
            if (comparison == Comparison.EXACT) {
                AssemblyRecipeKey key = AssemblyRecipeKey.of(candidate);
                if (selected != null && !selected.key.equals(key))
                    throw OrderedPatternLayout.rejected(AMBIGUOUS_RECIPE, -1);
                selected = new AssemblyRecipeMatch(candidate, key);
            } else if (comparison == Comparison.ZERO_CONSUMPTION) {
                rejection = ZERO_CONSUMPTION;
            } else if (comparison == Comparison.SPECIAL_FLUID_TAG && rejection != ZERO_CONSUMPTION) {
                rejection = SPECIAL_FLUID_TAG;
            } else if (comparison == Comparison.MULTIPLE_COPIES && rejection == NO_AUTHORIZED_RECIPE) {
                rejection = MULTIPLE_COPIES;
            }
        }
        if (selected == null) throw OrderedPatternLayout.rejected(rejection, -1);
        return selected;
    }

    public RecipeAssemblyLine recipe() {
        return recipe;
    }

    public AssemblyRecipeKey key() {
        return key;
    }

    private static Comparison compare(OrderedPatternLayout layout, Payload payload, RecipeAssemblyLine recipe) {
        if (recipe == null || recipe.getClass() != RecipeAssemblyLine.class
            || recipe.mInputs == null
            || recipe.mOreDictAlt == null
            || recipe.mOreDictAlt.length != recipe.mInputs.length
            || recipe.mFluidInputs == null
            || recipe.mOutput == null
            || recipe.mOutput.stackSize <= 0
            || recipe.mDuration <= 0
            || recipe.mEUt <= 0) return Comparison.NO_MATCH;
        List<Input> items = payload.items(), fluids = payload.fluids();
        if (recipe.mInputs.length != items.size() || recipe.mFluidInputs.length != fluids.size())
            return Comparison.NO_MATCH;
        ItemStack output = layout.output();
        if (!OrderedPatternLayout.sameItem(output, recipe.mOutput)) return Comparison.NO_MATCH;
        long copies = ratio(output.stackSize, recipe.mOutput.stackSize);
        if (copies < 1) return Comparison.NO_MATCH;
        boolean zeroConsumption = false;
        for (int i = 0; i < items.size(); i++) {
            ItemStack actual = items.get(i)
                .itemStack();
            ItemStack[] alternatives = recipe.mOreDictAlt[i];
            // Use the native helper: the first matching alternative determines consumption, including zero.
            int requirement = RecipeAssemblyLine.getMatchedIngredientAmount(actual, recipe.mInputs[i], alternatives);
            if (requirement < 0) return Comparison.NO_MATCH;
            if (requirement == 0) {
                zeroConsumption = true;
            } else if (ratio(actual.stackSize, requirement) != copies) {
                return Comparison.NO_MATCH;
            }
        }
        boolean specialFluid = false;
        for (int i = 0; i < fluids.size(); i++) {
            FluidStack expected = recipe.mFluidInputs[i], actual = fluids.get(i)
                .fluidStack();
            if (expected == null || expected.amount <= 0
                || actual.getFluid() != expected.getFluid()
                || ratio(actual.amount, expected.amount) != copies) return Comparison.NO_MATCH;
            if (expected.tag != null && !expected.tag.hasNoTags()) specialFluid = true;
            else if (!expected.isFluidEqual(actual)) return Comparison.NO_MATCH;
        }
        if (zeroConsumption) return Comparison.ZERO_CONSUMPTION;
        if (specialFluid) return Comparison.SPECIAL_FLUID_TAG;
        return copies == 1 ? Comparison.EXACT : Comparison.MULTIPLE_COPIES;
    }

    /** Division in long space avoids multiplication overflow and rejects every fractional ratio. */
    private static long ratio(long amount, long requirement) {
        return amount > 0 && requirement > 0 && amount % requirement == 0 ? amount / requirement : -1;
    }
}
