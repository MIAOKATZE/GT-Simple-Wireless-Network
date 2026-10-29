package com.miaokatze.gtswn.crossmod.mm;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import com.miaokatze.gtswn.common.items.GTSwnCoverDynamoWireless;
import com.miaokatze.gtswn.common.items.GTSwnCoverEnergyWireless;

public class WirelessCoverMaterialsTest {

    private final Item energy = new GTSwnCoverEnergyWireless();
    private final Item dynamo = new GTSwnCoverDynamoWireless();
    private final Item ordinary = new Item();

    @Test
    public void newLaserCoversRequireOneTubePerOccupiedWirelessSideOnly() {
        ItemStack[] expected = { new ItemStack(energy), new ItemStack(dynamo), new ItemStack(ordinary), null, null,
            new ItemStack(energy) };
        assertEquals(3, WirelessCoverMaterials.requiredTubes(true, expected, null));
        assertEquals(0, WirelessCoverMaterials.requiredTubes(false, expected, null));
        for (ItemStack stack : expected) if (stack != null) assertEquals(1, stack.stackSize);
    }

    @Test
    public void existingWirelessCoversAreFreeToReconfigureAndRepeatedApplicationDoesNotRepurchase() {
        ItemStack[] expected = { new ItemStack(energy), new ItemStack(dynamo) };
        ItemStack[] existing = { new ItemStack(energy), new ItemStack(dynamo) };
        assertEquals(0, WirelessCoverMaterials.requiredTubes(true, expected, existing));
        assertEquals(0, WirelessCoverMaterials.requiredTubes(true, expected, existing));
    }

    @Test
    public void replacingTheCoverKindAndAddingAnotherSideEachRequireOneTube() {
        ItemStack[] expected = { new ItemStack(dynamo), new ItemStack(energy), new ItemStack(ordinary) };
        ItemStack[] existing = { new ItemStack(energy), null, new ItemStack(energy) };
        assertEquals(2, WirelessCoverMaterials.requiredTubes(true, expected, existing));
    }

    @Test
    public void removingCoversAndOrdinaryCoversNeverRequireVacuumPipes() {
        assertEquals(0, WirelessCoverMaterials.requiredTubes(true, null, new ItemStack[] { new ItemStack(energy) }));
        assertEquals(
            0,
            WirelessCoverMaterials.requiredTubes(
                true,
                new ItemStack[] { null, new ItemStack(ordinary) },
                new ItemStack[] { new ItemStack(energy) }));
        assertFalse(WirelessCoverMaterials.isWireless(null));
        assertFalse(WirelessCoverMaterials.isWireless(new ItemStack(ordinary)));
    }

    @Test
    public void virtualCoverSalvageCannotBecomeRealInventoryOrEstimatorCredit() {
        ItemStack physical = new ItemStack(ordinary, 23, 7);
        physical.setTagCompound(new NBTTagCompound());
        physical.getTagCompound().setString("owner", "preserved");
        List<ItemStack> original = Arrays.asList(new ItemStack(energy), physical, new ItemStack(dynamo), null);

        List<ItemStack> filtered = WirelessCoverMaterials.withoutVirtualCovers(original, ItemStack::getItem);

        assertEquals(2, filtered.size());
        assertSame(physical, filtered.get(0));
        assertNull(filtered.get(1));
        assertEquals(23, physical.stackSize);
        assertEquals(7, physical.getItemDamage());
        assertEquals("preserved", physical.getTagCompound().getString("owner"));
        assertEquals(4, original.size());
        assertEquals(1, original.get(0).stackSize);
        assertEquals(1, original.get(2).stackSize);
    }

    @Test
    public void salvageFilteringIsIdempotentAndDoesNotMutateSharedInputLists() {
        List<ItemStack> virtual = Arrays.asList(new ItemStack(energy), new ItemStack(dynamo));
        assertTrue(WirelessCoverMaterials.withoutVirtualCovers(virtual, ItemStack::getItem).isEmpty());
        assertEquals(2, virtual.size());
        List<ItemStack> physical = Arrays.asList(new ItemStack(ordinary));
        List<ItemStack> filtered = WirelessCoverMaterials.withoutVirtualCovers(physical, ItemStack::getItem);
        assertEquals(filtered, WirelessCoverMaterials.withoutVirtualCovers(filtered, ItemStack::getItem));
        filtered.clear();
        assertEquals(1, physical.size());
    }
}
