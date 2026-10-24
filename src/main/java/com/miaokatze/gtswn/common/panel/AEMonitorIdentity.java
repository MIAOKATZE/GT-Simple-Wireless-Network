package com.miaokatze.gtswn.common.panel;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.fluids.FluidStack;

/** Quantity-independent, persistent AE identities. Old unversioned keys remain unidentified legacy history. */
public final class AEMonitorIdentity {

    private AEMonitorIdentity() {}

    public static String key(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return null;
        String name = Item.itemRegistry.getNameForObject(stack.getItem());
        return name == null ? null : "v2:item:" + tagDigest(descriptor(stack));
    }

    public static String key(FluidStack stack) {
        if (stack == null || stack.getFluid() == null) return null;
        return "v2:fluid:" + tagDigest(descriptor(stack));
    }

    public static boolean isLegacy(String key) {
        return key != null && !key.startsWith("v2:item:") && !key.startsWith("v2:fluid:");
    }

    public static NBTTagCompound descriptor(ItemStack stack) {
        NBTTagCompound tag = descriptor(
            "item",
            Item.itemRegistry.getNameForObject(stack.getItem()),
            stack.getTagCompound());
        tag.setInteger("meta", stack.getItemDamage());
        return tag;
    }

    public static NBTTagCompound descriptor(FluidStack stack) {
        return descriptor(
            "fluid",
            stack.getFluid()
                .getName(),
            stack.tag);
    }

    private static NBTTagCompound descriptor(String kind, String name, NBTTagCompound contents) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("kind", kind);
        tag.setString("name", name);
        if (contents != null && !contents.hasNoTags()) tag.setTag("tag", contents.copy());
        return tag;
    }

    /** AE normalizes an empty root tag to null. Nested tags retain their type and contents. */
    static String tagDigest(NBTTagCompound tag) {
        if (tag == null || tag.hasNoTags()) return "none";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            DataOutputStream output = new DataOutputStream(new DigestOutputStream(new OutputStream() {

                @Override
                public void write(int value) {}
            }, digest));
            writeCanonical(tag, output);
            byte[] bytes = digest.digest();
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                result.append(Character.forDigit((value >>> 4) & 15, 16));
                result.append(Character.forDigit(value & 15, 16));
            }
            return result.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot encode AE monitor identity", e);
        }
    }

    /** Sort compound keys; preserve list order/type. Vanilla encodes all scalar and array payloads. */
    private static void writeCanonical(NBTBase tag, DataOutputStream output) throws IOException {
        output.writeByte(tag.getId());
        if (tag instanceof NBTTagCompound) {
            NBTTagCompound compound = (NBTTagCompound) tag;
            List<String> keys = new ArrayList<>();
            for (Object key : compound.func_150296_c()) keys.add((String) key);
            Collections.sort(keys);
            output.writeInt(keys.size());
            for (String key : keys) {
                output.writeUTF(key);
                writeCanonical(compound.getTag(key), output);
            }
        } else if (tag instanceof NBTTagList) {
            NBTTagList list = (NBTTagList) tag;
            output.writeByte(list.func_150303_d());
            output.writeInt(list.tagCount());
            // 1.7.10 has no generic read accessor. Pop a private copy from the tail in linear time,
            // then encode the original order without changing the caller's tags.
            NBTTagList copy = (NBTTagList) list.copy();
            NBTBase[] elements = new NBTBase[copy.tagCount()];
            for (int i = elements.length - 1; i >= 0; i--) elements[i] = copy.removeTag(i);
            for (NBTBase element : elements) writeCanonical(element, output);
        } else {
            NBTTagCompound scalar = new NBTTagCompound();
            scalar.setTag("value", tag);
            CompressedStreamTools.write(scalar, output);
        }
    }
}
