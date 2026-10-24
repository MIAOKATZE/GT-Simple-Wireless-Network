package com.miaokatze.gtswn.common.panel;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.fluids.FluidStack;

import org.junit.BeforeClass;
import org.junit.Test;

import com.miaokatze.gtswn.network.PacketSyncAEMonitorData;
import com.miaokatze.gtswn.test.FluidRegistryTestBootstrap;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class AEMonitorIdentityTest {

    private static Item item;

    @BeforeClass
    public static void registerItem() throws Exception {
        item = new Item();
        // Reuse the existing headless registry fixture route; normal registration initializes Forge Loader.
        java.lang.reflect.Method register = Item.itemRegistry.getClass()
            .getDeclaredMethod("addObjectRaw", int.class, String.class, Object.class);
        register.setAccessible(true);
        register.invoke(Item.itemRegistry, 31017, "gtswn_test:ae_identity", item);
    }

    private static ItemStack stack(String variant, int count) {
        ItemStack stack = new ItemStack(item, count, 4);
        if (variant != null) {
            stack.setTagCompound(new NBTTagCompound());
            stack.getTagCompound()
                .setString("variant", variant);
        }
        return stack;
    }

    @Test
    public void quantitiesNeverChangeIdentityButTagsDo() {
        assertEquals(AEMonitorIdentity.key(stack("a", 1)), AEMonitorIdentity.key(stack("a", 64)));
        assertNotEquals(AEMonitorIdentity.key(stack("a", 1)), AEMonitorIdentity.key(stack("b", 1)));
        assertNotEquals(AEMonitorIdentity.key(stack(null, 1)), AEMonitorIdentity.key(stack("a", 1)));
        ItemStack emptyTag = stack(null, 1);
        emptyTag.setTagCompound(new NBTTagCompound());
        assertEquals(AEMonitorIdentity.key(stack(null, 1)), AEMonitorIdentity.key(emptyTag));
        assertTrue(AEMonitorIdentity.isLegacy("item:gtswn_test:ae_identity:4"));
        assertFalse(AEMonitorIdentity.isLegacy(AEMonitorIdentity.key(emptyTag)));
        assertEquals(
            72,
            AEMonitorIdentity.key(emptyTag)
                .length());
    }

    @Test
    public void compoundInsertionOrderIsStableIncludingHashBucketCollisions() {
        NBTTagCompound first = new NBTTagCompound();
        first.setInteger("Aa", 1);
        first.setString("BB", "value");
        NBTTagCompound second = new NBTTagCompound();
        second.setString("BB", "value");
        second.setInteger("Aa", 1);
        NBTTagList firstList = new NBTTagList(), secondList = new NBTTagList();
        firstList.appendTag(first);
        secondList.appendTag(second);
        NBTTagCompound left = new NBTTagCompound(), right = new NBTTagCompound();
        left.setTag("list", firstList);
        right.setTag("list", secondList);
        assertEquals(AEMonitorIdentity.tagDigest(left), AEMonitorIdentity.tagDigest(right));
        second.setString("BB", "different");
        assertNotEquals(AEMonitorIdentity.tagDigest(left), AEMonitorIdentity.tagDigest(right));
    }

    @Test
    public void nativeScalarTypesAndArrayContentsRemainDistinct() {
        NBTTagCompound first = new NBTTagCompound(), second = new NBTTagCompound();
        first.setInteger("value", 1);
        second.setLong("value", 1L);
        assertNotEquals(AEMonitorIdentity.tagDigest(first), AEMonitorIdentity.tagDigest(second));
        first.setByteArray("value", new byte[] { 1, 2 });
        second.setByteArray("value", new byte[] { 2, 1 });
        assertNotEquals(AEMonitorIdentity.tagDigest(first), AEMonitorIdentity.tagDigest(second));
        first.setIntArray("value", new int[] { 1, 2 });
        second.setIntArray("value", new int[] { 2, 1 });
        assertNotEquals(AEMonitorIdentity.tagDigest(first), AEMonitorIdentity.tagDigest(second));
    }

    @Test
    public void genericListTraversalPreservesOrderAndNeverMutatesTheOriginal() {
        NBTTagList forward = new NBTTagList(), reverse = new NBTTagList();
        forward.appendTag(new NBTTagInt(7));
        forward.appendTag(new NBTTagInt(9));
        reverse.appendTag(new NBTTagInt(9));
        reverse.appendTag(new NBTTagInt(7));
        NBTTagCompound first = new NBTTagCompound(), second = new NBTTagCompound();
        first.setTag("values", forward);
        second.setTag("values", reverse);
        NBTTagCompound before = (NBTTagCompound) first.copy();
        String digest = AEMonitorIdentity.tagDigest(first);
        assertNotEquals(digest, AEMonitorIdentity.tagDigest(second));
        assertEquals(before, first);
        assertEquals(2, forward.tagCount());
        assertEquals(digest, AEMonitorIdentity.tagDigest(first));
    }

    @Test
    public void perKeySamplingKeepsBothNbtVariantsAndDeduplicatesQuantity() {
        AEMonitorController.AEQuery query = new AEMonitorController.AEQuery() {

            public boolean isConnected() {
                return true;
            }

            public long itemAmount(ItemStack stack) {
                return "a".equals(
                    stack.getTagCompound()
                        .getString("variant")) ? 10L : 25L;
            }

            public long fluidAmount(FluidStack stack) {
                return 0L;
            }

            public Map<String, Long> bulkAmounts(java.util.List<ItemStack> items, java.util.List<FluidStack> fluids) {
                throw new AssertionError("Per-key query must remain precise");
            }
        };
        Map<String, Long> amounts = AESamplingStrategy.PerKey.INSTANCE
            .collect(query, Arrays.asList(stack("a", 1), stack("b", 1), stack("a", 64)), Collections.emptyList());
        assertEquals(2, amounts.size());
        assertEquals(Long.valueOf(10L), amounts.get(AEMonitorIdentity.key(stack("a", 1))));
        assertEquals(Long.valueOf(25L), amounts.get(AEMonitorIdentity.key(stack("b", 1))));
    }

    @Test
    public void fluidTagsAreQuantityIndependentAndDistinct() {
        FluidStack first = FluidRegistryTestBootstrap.water(1);
        first.tag = new NBTTagCompound();
        first.tag.setString("variant", "a");
        FluidStack second = first.copy();
        second.amount = 1000;
        assertEquals(AEMonitorIdentity.key(first), AEMonitorIdentity.key(second));
        second.tag.setString("variant", "b");
        assertNotEquals(AEMonitorIdentity.key(first), AEMonitorIdentity.key(second));
        assertEquals(
            73,
            AEMonitorIdentity.key(first)
                .length());
    }

    @Test
    public void roundTripPreservesDescriptorsAndUnattributedLegacyHistorySeparately() {
        AEMonitorDataSet data = new AEMonitorDataSet();
        String legacy = "item:gtswn_test:ae_identity:4";
        data.addSample(legacy, 99L, 100L, 0L);
        for (String variant : new String[] { null, "a", "b" }) {
            ItemStack stack = stack(variant, 64);
            String key = AEMonitorIdentity.key(stack);
            data.registerIdentity(key, AEMonitorIdentity.descriptor(stack));
            data.addSample(key, variant == null ? 1 : 2, 200L, 0L);
        }
        AEMonitorDataSet restored = new AEMonitorDataSet();
        restored.readFromNBT(data.toNBT());
        assertEquals(data.getKeys(), restored.getKeys());
        assertEquals(
            4,
            restored.getKeys()
                .size());
        assertEquals(99L, restored.newest(legacy).amount);
        assertNull(restored.identityDescriptor(legacy));
        assertTrue(restored.isLegacy(legacy));
        String key = AEMonitorIdentity.key(stack("a", 1));
        assertEquals(data.identityDescriptor(key), restored.identityDescriptor(key));
        restored.identityDescriptor(key)
            .setString("name", "mutated");
        assertEquals(data.identityDescriptor(key), restored.identityDescriptor(key));
        assertEquals(1, restored.size(key, AEMonitorDataSet.WINDOW_5_MIN));
    }

    @Test
    public void broadcastRoundTripKeepsBothCompleteIdentityKeys() {
        String first = AEMonitorIdentity.key(stack("a", 1));
        String second = AEMonitorIdentity.key(stack("b", 1));
        Map<String, AEMonitorSample> latest = new HashMap<>();
        latest.put(first, new AEMonitorSample(0L, 100L, 10L, 1D));
        latest.put(second, new AEMonitorSample(0L, 100L, 20L, 2D));
        Map<String, Double> rates = new HashMap<>();
        rates.put(first, 1D);
        rates.put(second, 2D);
        PacketSyncAEMonitorData packet = new PacketSyncAEMonitorData(
            1,
            2,
            3,
            first,
            Collections.singletonList(latest.get(first)),
            latest,
            rates);
        ByteBuf bytes = Unpooled.buffer();
        try {
            packet.toBytes(bytes);
            PacketSyncAEMonitorData restored = new PacketSyncAEMonitorData();
            restored.fromBytes(bytes);
            assertEquals(first, restored.getChartKey());
            assertEquals(
                2,
                restored.getMonitorLatest()
                    .size());
            assertEquals(
                10L,
                restored.getMonitorLatest()
                    .get(first).amount);
            assertEquals(
                20L,
                restored.getMonitorLatest()
                    .get(second).amount);
            assertEquals(rates, restored.getMonitorAvg300s());
            assertFalse(bytes.isReadable());
        } finally {
            bytes.release();
        }
    }
}
