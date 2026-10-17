package com.miaokatze.gtswn.crossmod.mm;

import static org.junit.Assert.*;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

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
}
