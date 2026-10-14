package com.miaokatze.gtswn.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTSizeTracker;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidStack;

import io.netty.buffer.ByteBuf;

/** Small, uncompressed, structurally bounded icon identity. Decoded once per snapshot, never per frame. */
public final class PortableMonitorIcon {

    public static final int MAX_BYTES = 768;
    private static final Map<Object, CachedIcon> CACHE = new WeakHashMap<>();
    private final byte[] data;
    private final long fingerprint;
    public final ItemStack item;
    public final FluidStack fluid;

    private PortableMonitorIcon(byte[] data, ItemStack item, FluidStack fluid) {
        this.data = data;
        long signature = 0xcbf29ce484222325L;
        for (byte value : data) signature = (signature ^ (value & 255)) * 0x100000001b3L;
        this.fingerprint = signature;
        this.item = item;
        this.fluid = fluid;
    }

    public long fingerprint() {
        return fingerprint;
    }

    public static PortableMonitorIcon cached(ItemStack item) {
        if (item == null) return null;
        long fingerprint = System.identityHashCode(item.getItem()) * 31L + item.getItemDamage();
        fingerprint = fingerprint * 31 + (item.hasTagCompound() ? item.getTagCompound()
            .hashCode() : 0);
        CachedIcon previous = CACHE.get(item);
        if (previous != null && previous.fingerprint == fingerprint
            && (previous.icon == null || previous.icon.item != null && item.isItemEqual(previous.icon.item)
                && ItemStack.areItemStackTagsEqual(item, previous.icon.item)))
            return previous.icon;
        return remember(item, fingerprint, of(item));
    }

    public static PortableMonitorIcon cached(FluidStack fluid) {
        if (fluid == null) return null;
        long fingerprint = System.identityHashCode(fluid.getFluid()) * 31L
            + (fluid.tag == null ? 0 : fluid.tag.hashCode());
        CachedIcon previous = CACHE.get(fluid);
        if (previous != null && previous.fingerprint == fingerprint
            && (previous.icon == null || previous.icon.fluid != null && fluid.isFluidEqual(previous.icon.fluid)))
            return previous.icon;
        return remember(fluid, fingerprint, of(fluid));
    }

    private static PortableMonitorIcon remember(Object source, long fingerprint, PortableMonitorIcon icon) {
        // Entries never retain the original stack/world; this extra ceiling also bounds active-world use.
        if (CACHE.size() >= 2048) CACHE.clear();
        CACHE.put(source, new CachedIcon(fingerprint, icon));
        return icon;
    }

    private static final class CachedIcon {

        final long fingerprint;
        final PortableMonitorIcon icon;

        CachedIcon(long fingerprint, PortableMonitorIcon icon) {
            this.fingerprint = fingerprint;
            this.icon = icon;
        }
    }

    public static PortableMonitorIcon of(ItemStack item) {
        if (item == null || item.getItem() == null) return null;
        Object name = Item.itemRegistry.getNameForObject(item.getItem());
        if (name == null) return null;
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("Item", name.toString());
        tag.setInteger("Meta", item.getItemDamage());
        if (item.hasTagCompound()) tag.setTag("Tag", item.getTagCompound());
        return encode(tag);
    }

    public static PortableMonitorIcon of(FluidStack fluid) {
        if (fluid == null || fluid.getFluid() == null) return null;
        NBTTagCompound tag = new NBTTagCompound();
        fluid.writeToNBT(tag);
        tag.setInteger("Amount", 1000);
        return encode(tag);
    }

    private static PortableMonitorIcon encode(NBTTagCompound tag) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            OutputStream bounded = new OutputStream() {

                @Override
                public void write(int value) throws IOException {
                    if (bytes.size() >= MAX_BYTES) throw new IOException("Portable icon too large");
                    bytes.write(value);
                }
            };
            CompressedStreamTools.write(tag, new DataOutputStream(bounded));
            return decode(bytes.toByteArray());
        } catch (IOException | RuntimeException ignored) {
            // Preserve exact identity or omit the icon; never drop significant NBT to invent an icon.
            return null;
        }
    }

    public static void write(ByteBuf buf, PortableMonitorIcon icon) {
        buf.writeShort(icon == null ? 0 : icon.data.length);
        if (icon != null) buf.writeBytes(icon.data);
    }

    public static PortableMonitorIcon read(ByteBuf buf) {
        int size = PacketSyncPortableMonitor.count(buf.readUnsignedShort(), MAX_BYTES);
        if (size == 0) return null;
        byte[] bytes = new byte[size];
        buf.readBytes(bytes);
        try {
            return decode(bytes);
        } catch (IOException | RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid portable icon", invalid);
        }
    }

    private static PortableMonitorIcon decode(byte[] bytes) throws IOException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
        if (input.readUnsignedByte() != 10) throw new IOException("Icon root must be compound");
        input.readUTF();
        scan(input, 10, 0, new int[] { 0 });
        if (input.available() != 0) throw new IOException("Trailing icon data");
        NBTTagCompound tag = CompressedStreamTools
            .func_152456_a(new DataInputStream(new ByteArrayInputStream(bytes)), new NBTSizeTracker(32768));
        ItemStack item = null;
        FluidStack fluid = null;
        if (tag.hasKey("Item", 8)) {
            Object registered = Item.itemRegistry.getObject(tag.getString("Item"));
            if (registered instanceof Item) {
                item = new ItemStack((Item) registered, 1, tag.getInteger("Meta"));
                if (tag.hasKey("Tag", 10)) item.setTagCompound(tag.getCompoundTag("Tag"));
            }
        } else {
            fluid = FluidStack.loadFluidStackFromNBT(tag);
        }
        return item == null && fluid == null ? null : new PortableMonitorIcon(bytes, item, fluid);
    }

    /** Reject allocation lengths and deep/list-heavy trees before the native NBT reader allocates. */
    private static void scan(DataInputStream input, int type, int depth, int[] nodes) throws IOException {
        if (depth > 16 || ++nodes[0] > 128) throw new IOException("Icon structure bound");
        switch (type) {
            case 1:
                input.readByte();
                break;
            case 2:
                input.readShort();
                break;
            case 3:
                input.readInt();
                break;
            case 4:
                input.readLong();
                break;
            case 5:
                input.readFloat();
                break;
            case 6:
                input.readDouble();
                break;
            case 7:
            case 11:
                int count = input.readInt();
                if (count < 0 || count > MAX_BYTES / (type == 11 ? 4 : 1)) throw new IOException("Icon array bound");
                int size = count * (type == 11 ? 4 : 1);
                if (size > input.available()) throw new IOException("Truncated icon array");
                input.skipBytes(size);
                break;
            case 8:
                input.readUTF();
                break;
            case 9:
                int element = input.readUnsignedByte(), length = input.readInt();
                if (length < 0 || length > 128 || element > 11 || element == 0 && length != 0)
                    throw new IOException("Icon list bound");
                for (int i = 0; i < length; i++) scan(input, element, depth + 1, nodes);
                break;
            case 10:
                int child;
                while ((child = input.readUnsignedByte()) != 0) {
                    input.readUTF();
                    scan(input, child, depth + 1, nodes);
                }
                break;
            default:
                throw new IOException("Invalid icon tag type");
        }
    }
}
