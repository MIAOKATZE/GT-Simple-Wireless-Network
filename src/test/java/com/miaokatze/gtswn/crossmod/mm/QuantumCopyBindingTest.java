package com.miaokatze.gtswn.crossmod.mm;

import static org.junit.Assert.*;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import org.junit.Test;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;

public class QuantumCopyBindingTest {

    @Test
    public void existingPlayerRestoreEntryPointRetainsItsVoidBinarySignature() throws Exception {
        assertSame(
            Void.TYPE,
            QuantumCopy.class.getMethod("restore", TileEntity.class, NBTTagCompound.class, EntityPlayer.class)
                .getReturnType());
    }

    @Test
    public void incorporationRequiresAllFourTypedCoordinatesBeforeItCanBeBilled() {
        assertFalse(QuantumCopy.hasIncorporationBinding(null));
        NBTTagCompound data = new NBTTagCompound(), binding = new NBTTagCompound();
        data.setTag("incorporation", binding);
        String[] coordinates = { ItemNetworkQuantumTerminal.NBT_ANCHOR_DIM, ItemNetworkQuantumTerminal.NBT_ANCHOR_X,
            ItemNetworkQuantumTerminal.NBT_ANCHOR_Y, ItemNetworkQuantumTerminal.NBT_ANCHOR_Z };
        for (String coordinate : coordinates) {
            assertFalse(QuantumCopy.hasIncorporationBinding(data));
            binding.setInteger(coordinate, -13);
        }
        assertTrue(QuantumCopy.hasIncorporationBinding(data));
        binding.setString(ItemNetworkQuantumTerminal.NBT_ANCHOR_DIM, "0");
        assertFalse(QuantumCopy.hasIncorporationBinding(data));
    }

    @Test
    public void nodeBindingDoesNotRequestAnIncorporationAnchor() {
        NBTTagCompound node = new NBTTagCompound();
        node.setBoolean("node", true);
        node.setInteger("dim", 0);
        node.setInteger("x", 0);
        node.setInteger("y", 64);
        node.setInteger("z", 0);
        assertFalse(QuantumCopy.hasIncorporationBinding(node));
        assertFalse(QuantumCopy.needsIncorporation(null, node));
    }
}
