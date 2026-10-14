package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidStack;

import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class PortableMonitorIconTest {

    @Test
    public void unchangedStackReusesEncodingAndNbtChangeInvalidatesIt() throws Exception {
        ItemStack item = new ItemStack(fixtureItem(), 1, 1);
        item.setTagCompound(new NBTTagCompound());
        item.getTagCompound()
            .setInteger("color", 1);
        PortableMonitorIcon first = PortableMonitorIcon.cached(item);
        assertSame(first, PortableMonitorIcon.cached(item));
        item.getTagCompound()
            .setInteger("color", 2);
        PortableMonitorIcon changed = PortableMonitorIcon.cached(item);
        assertNotSame(first, changed);
        assertEquals(
            2,
            changed.item.getTagCompound()
                .getInteger("color"));
        assertEquals(
            1,
            first.item.getTagCompound()
                .getInteger("color"));
    }

    private static Item fixtureItem() throws Exception {
        String name = "minecraft:gtswn_portable_icon_fixture";
        Object registered = Item.itemRegistry.getObject(name);
        if (registered instanceof Item) return (Item) registered;
        Item item = new Item();
        java.lang.reflect.Method register = Item.itemRegistry.getClass()
            .getDeclaredMethod("addObjectRaw", int.class, String.class, Object.class);
        register.setAccessible(true);
        register.invoke(Item.itemRegistry, 31889, name, item);
        return item;
    }

    @Test
    public void itemIconKeepsRegistryMetadataAndNestedNbtWithUnitCount() throws Exception {
        ItemStack item = new ItemStack(fixtureItem(), 64, 27);
        NBTTagCompound tag = new NBTTagCompound(), display = new NBTTagCompound();
        display.setString("Name", "Named component");
        tag.setTag("display", display);
        tag.setInteger("color", 0x123456);
        item.setTagCompound(tag);
        ByteBuf bytes = Unpooled.buffer();
        try {
            PortableMonitorIcon.write(bytes, PortableMonitorIcon.of(item));
            PortableMonitorIcon decoded = PortableMonitorIcon.read(bytes);
            assertNotNull(decoded);
            assertSame(item.getItem(), decoded.item.getItem());
            assertEquals(27, decoded.item.getItemDamage());
            assertEquals(1, decoded.item.stackSize);
            assertEquals(tag, decoded.item.getTagCompound());
            assertNotSame(tag, decoded.item.getTagCompound());
            assertFalse(bytes.isReadable());
        } finally {
            bytes.release();
        }
    }

    @Test
    public void fluidIconKeepsFluidAndColorRelevantNbt() {
        FluidStack fluid = com.miaokatze.gtswn.test.FluidRegistryTestBootstrap.water(4000);
        fluid.tag = new NBTTagCompound();
        fluid.tag.setInteger("color", 0xaabbcc);
        PortableMonitorIcon icon = PortableMonitorIcon.of(fluid);
        assertNotNull(icon);
        assertSame(fluid.getFluid(), icon.fluid.getFluid());
        assertEquals(1000, icon.fluid.amount);
        assertEquals(fluid.tag, icon.fluid.tag);
    }

    @Test
    public void oversizedAndDeepItemNbtOmitIconInsteadOfChangingIdentity() throws Exception {
        ItemStack item = new ItemStack(fixtureItem());
        NBTTagCompound tag = new NBTTagCompound();
        tag.setByteArray("huge", new byte[PortableMonitorIcon.MAX_BYTES]);
        item.setTagCompound(tag);
        assertNull(PortableMonitorIcon.of(item));
        NBTTagCompound deep = new NBTTagCompound();
        for (int i = 0; i < 18; i++) {
            NBTTagCompound parent = new NBTTagCompound();
            parent.setTag("nested", deep);
            deep = parent;
        }
        item.setTagCompound(deep);
        assertNull(PortableMonitorIcon.of(item));
    }

    @Test
    public void forgedArrayLengthIsRejectedBeforeNativeNbtAllocation() throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(payload);
        out.writeByte(10);
        out.writeUTF("");
        out.writeByte(7);
        out.writeUTF("bad");
        out.writeInt(Integer.MAX_VALUE);
        ByteBuf bytes = Unpooled.buffer();
        try {
            bytes.writeShort(payload.size());
            bytes.writeBytes(payload.toByteArray());
            try {
                PortableMonitorIcon.read(bytes);
                fail("Unbounded allocation length accepted");
            } catch (IllegalArgumentException expected) {
                assertTrue(
                    expected.getMessage()
                        .contains("icon"));
            }
        } finally {
            bytes.release();
        }
    }
}
