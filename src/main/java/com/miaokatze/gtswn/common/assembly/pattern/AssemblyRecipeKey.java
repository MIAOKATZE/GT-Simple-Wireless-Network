package com.miaokatze.gtswn.common.assembly.pattern;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.fluids.FluidStack;

import gregtech.api.util.GTRecipe.RecipeAssemblyLine;

/** Complete identity of the fields consumed by the fixed GT5U .265 assembly-line implementation. */
public final class AssemblyRecipeKey {

    private final byte[] contents;
    private final String value;

    private AssemblyRecipeKey(byte[] contents) {
        this.contents = contents;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(contents);
            StringBuilder result = new StringBuilder("v1:");
            for (byte part : digest) {
                result.append(Character.forDigit((part >>> 4) & 15, 16));
                result.append(Character.forDigit(part & 15, 16));
            }
            value = result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public static AssemblyRecipeKey of(RecipeAssemblyLine recipe) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeUTF("gtswn:assembly-line:gt5u-5.09.54.265:v1");
            writeItem(output, recipe.mResearchItem);
            output.writeInt(recipe.mResearchTime);
            output.writeInt(recipe.mResearchVoltage);
            writeItems(output, recipe.mInputs);
            if (recipe.mOreDictAlt == null) {
                output.writeInt(-1);
            } else {
                output.writeInt(recipe.mOreDictAlt.length);
                // Both position order and alternative order affect native matching.
                for (ItemStack[] alternatives : recipe.mOreDictAlt) writeItems(output, alternatives);
            }
            if (recipe.mFluidInputs == null) {
                output.writeInt(-1);
            } else {
                output.writeInt(recipe.mFluidInputs.length);
                for (FluidStack fluid : recipe.mFluidInputs) writeFluid(output, fluid);
            }
            writeItem(output, recipe.mOutput);
            output.writeInt(recipe.mDuration);
            output.writeInt(recipe.mEUt);
            output.flush();
            return new AssemblyRecipeKey(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot encode assembly recipe", impossible);
        }
    }

    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AssemblyRecipeKey && Arrays.equals(contents, ((AssemblyRecipeKey) other).contents);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    private static void writeItems(DataOutputStream output, ItemStack[] stacks) throws IOException {
        output.writeInt(stacks == null ? -1 : stacks.length);
        if (stacks != null) for (ItemStack stack : stacks) writeItem(output, stack);
    }

    private static void writeItem(DataOutputStream output, ItemStack stack) throws IOException {
        output.writeBoolean(stack != null);
        if (stack == null) return;
        String name = Item.itemRegistry.getNameForObject(stack.getItem());
        if (name == null) throw new IllegalArgumentException("UNREGISTERED_RECIPE_ITEM");
        output.writeUTF(name);
        output.writeInt(stack.getItemDamage());
        output.writeInt(stack.stackSize);
        writeTag(output, stack.getTagCompound());
    }

    private static void writeFluid(DataOutputStream output, FluidStack stack) throws IOException {
        output.writeBoolean(stack != null);
        if (stack == null) return;
        output.writeUTF(
            stack.getFluid()
                .getName());
        output.writeInt(stack.amount);
        writeTag(output, stack.tag);
    }

    /** Compound keys are canonical; every list, array, scalar type and null tag remains distinct. */
    private static void writeTag(DataOutputStream output, NBTBase tag) throws IOException {
        output.writeByte(tag == null ? -1 : tag.getId());
        if (tag == null) return;
        if (tag instanceof NBTTagCompound) {
            NBTTagCompound compound = (NBTTagCompound) tag;
            List<String> keys = new ArrayList<>();
            for (Object key : compound.func_150296_c()) keys.add((String) key);
            Collections.sort(keys);
            output.writeInt(keys.size());
            for (String key : keys) {
                output.writeUTF(key);
                writeTag(output, compound.getTag(key));
            }
        } else if (tag instanceof NBTTagList) {
            NBTTagList list = (NBTTagList) tag;
            output.writeByte(list.func_150303_d());
            output.writeInt(list.tagCount());
            NBTTagList copy = (NBTTagList) list.copy();
            NBTBase[] entries = new NBTBase[copy.tagCount()];
            for (int i = entries.length - 1; i >= 0; i--) entries[i] = copy.removeTag(i);
            for (NBTBase entry : entries) writeTag(output, entry);
        } else {
            NBTTagCompound scalar = new NBTTagCompound();
            scalar.setTag("value", tag);
            CompressedStreamTools.write(scalar, output);
        }
    }
}
