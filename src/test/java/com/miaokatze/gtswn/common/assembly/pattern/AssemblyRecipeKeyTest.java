package com.miaokatze.gtswn.common.assembly.pattern;

import static org.junit.Assert.*;

import java.util.function.Consumer;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.fluids.FluidStack;

import org.junit.BeforeClass;
import org.junit.Test;

import com.miaokatze.gtswn.test.FluidRegistryTestBootstrap;

import gregtech.api.util.GTRecipe.RecipeAssemblyLine;

public class AssemblyRecipeKeyTest {

    @BeforeClass
    public static void fixtures() throws Exception {
        AssemblyPatternFixtures.initialize();
    }

    private static RecipeAssemblyLine recipe() {
        return AssemblyPatternFixtures.recipe(
            new ItemStack[] { AssemblyPatternFixtures.input(2), AssemblyPatternFixtures.input(3) },
            new FluidStack[] { FluidRegistryTestBootstrap.water(144) },
            AssemblyPatternFixtures.output(1));
    }

    @Test
    public void everyBehaviorFieldChangesTheCompleteKey() {
        assertChanged(r -> r.mResearchItem = new ItemStack(AssemblyPatternFixtures.anotherProduct));
        assertChanged(r -> r.mResearchItem.stackSize++);
        assertChanged(r -> r.mResearchTime++);
        assertChanged(r -> r.mResearchVoltage++);
        assertChanged(r -> r.mInputs[0].stackSize++);
        assertChanged(r -> r.mInputs[0].setItemDamage(1));
        assertChanged(r -> r.mFluidInputs[0].amount++);
        assertChanged(r -> r.mOutput = new ItemStack(AssemblyPatternFixtures.anotherProduct));
        assertChanged(r -> r.mOutput.stackSize++);
        assertChanged(r -> r.mDuration++);
        assertChanged(r -> r.mEUt++);
        assertChanged(r -> r.mOreDictAlt[0] = new ItemStack[] { AssemblyPatternFixtures.input(2) });
        assertChanged(r -> {
            r.mFluidInputs[0].tag = new NBTTagCompound();
            r.mFluidInputs[0].tag.setString("grade", "special");
        });
        assertEquals(
            67,
            AssemblyRecipeKey.of(recipe())
                .value()
                .length());
    }

    @Test
    public void alternativeOrderAndInputPositionAreNotSorted() {
        RecipeAssemblyLine first = recipe(), second = recipe();
        first.mOreDictAlt[0] = new ItemStack[] { AssemblyPatternFixtures.input(2), AssemblyPatternFixtures.input(3) };
        second.mOreDictAlt[0] = new ItemStack[] { AssemblyPatternFixtures.input(3), AssemblyPatternFixtures.input(2) };
        assertNotEquals(AssemblyRecipeKey.of(first), AssemblyRecipeKey.of(second));
        second = recipe();
        ItemStack swap = second.mInputs[0];
        second.mInputs[0] = second.mInputs[1];
        second.mInputs[1] = swap;
        assertNotEquals(AssemblyRecipeKey.of(recipe()), AssemblyRecipeKey.of(second));
    }

    @Test
    public void canonicalNbtIgnoresCompoundInsertionOrderButPreservesListOrderAndNumericType() {
        RecipeAssemblyLine first = recipe(), second = recipe();
        NBTTagCompound left = new NBTTagCompound(), right = new NBTTagCompound();
        left.setInteger("Aa", 1);
        left.setString("BB", "value");
        right.setString("BB", "value");
        right.setInteger("Aa", 1);
        first.mOutput.setTagCompound(left);
        second.mOutput.setTagCompound(right);
        assertEquals(AssemblyRecipeKey.of(first), AssemblyRecipeKey.of(second));
        NBTTagCompound before = (NBTTagCompound) left.copy();
        AssemblyRecipeKey original = AssemblyRecipeKey.of(first);
        assertEquals(before, left);
        left.setLong("Aa", 1L);
        assertNotEquals(original, AssemblyRecipeKey.of(first));
        NBTTagList forward = new NBTTagList(), reverse = new NBTTagList();
        forward.appendTag(new NBTTagInt(1));
        forward.appendTag(new NBTTagInt(2));
        reverse.appendTag(new NBTTagInt(2));
        reverse.appendTag(new NBTTagInt(1));
        first.mOutput.setTagCompound(new NBTTagCompound());
        second.mOutput.setTagCompound(new NBTTagCompound());
        first.mOutput.getTagCompound()
            .setTag("list", forward);
        second.mOutput.getTagCompound()
            .setTag("list", reverse);
        before = (NBTTagCompound) first.mOutput.getTagCompound()
            .copy();
        assertNotEquals(AssemblyRecipeKey.of(first), AssemblyRecipeKey.of(second));
        assertEquals(before, first.mOutput.getTagCompound());
        assertEquals(2, forward.tagCount());
    }

    @Test
    public void aCachedNativeHashDoesNotReplaceRecipeContents() {
        RecipeAssemblyLine first = recipe(), second = recipe();
        first.setPersistentHash(7);
        second.setPersistentHash(7);
        second.mDuration++;
        assertNotEquals(AssemblyRecipeKey.of(first), AssemblyRecipeKey.of(second));
        AssemblyRecipeKey captured = AssemblyRecipeKey.of(first);
        first.mOutput.stackSize++;
        assertNotEquals(captured, AssemblyRecipeKey.of(first));
    }

    private static void assertChanged(Consumer<RecipeAssemblyLine> change) {
        RecipeAssemblyLine changed = recipe();
        AssemblyRecipeKey original = AssemblyRecipeKey.of(changed);
        change.accept(changed);
        assertNotEquals(original, AssemblyRecipeKey.of(changed));
    }
}
