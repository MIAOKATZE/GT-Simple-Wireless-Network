package com.miaokatze.gtswn.common.assembly.pattern;

import static com.miaokatze.gtswn.common.assembly.pattern.OrderedPatternLayoutTest.assertRejected;
import static org.junit.Assert.*;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidStack;

import org.junit.Test;

import com.miaokatze.gtswn.test.FluidRegistryTestBootstrap;

/** Fixed AE2FC source-format fixtures; these do not claim an installed AE2FC runtime integration test. */
public class AssemblyFluidEncodingTest {

    @Test
    public void dropsUseTheGenericCountInMillibucketsWithoutAnExtraThousandFactor() {
        FluidStack water = FluidRegistryTestBootstrap.water(1);
        NBTTagCompound drop = new NBTTagCompound();
        drop.setString(
            "Fluid",
            water.getFluid()
                .getName());
        NBTTagCompound before = (NBTTagCompound) drop.copy();
        assertEquals(144, OrderedPatternLayout.decodeFluidDrop(drop, 144L, 2).amount);
        assertEquals(before, drop);
        assertRejected(
            PatternValidationException.Reason.AMOUNT_OVERFLOW,
            () -> OrderedPatternLayout.decodeFluidDrop(drop, Long.MAX_VALUE, 2));
        drop.setTag("FluidTag", new NBTTagCompound());
        assertRejected(
            PatternValidationException.Reason.SPECIAL_FLUID_TAG,
            () -> OrderedPatternLayout.decodeFluidDrop(drop, 144L, 2));
    }

    @Test
    public void packetsReadTheInnerLongAmountAndRejectAmbiguousOuterMultipliers() {
        NBTTagCompound packet = packet(144L);
        NBTTagCompound before = (NBTTagCompound) packet.copy();
        assertEquals(144, OrderedPatternLayout.decodeFluidPacket(packet, 1L, 0).amount);
        assertEquals(before, packet);
        assertRejected(
            PatternValidationException.Reason.MULTIPLE_FLUID_PACKETS,
            () -> OrderedPatternLayout.decodeFluidPacket(packet, 2L, 0));
        NBTTagCompound oversized = packet((1L << 32) + 144);
        assertRejected(
            PatternValidationException.Reason.AMOUNT_OVERFLOW,
            () -> OrderedPatternLayout.decodeFluidPacket(oversized, 1L, 0));
        NBTTagCompound special = packet(144L);
        special.getCompoundTag("FluidStack")
            .setTag("tag", new NBTTagCompound());
        assertRejected(
            PatternValidationException.Reason.SPECIAL_FLUID_TAG,
            () -> OrderedPatternLayout.decodeFluidPacket(special, 1L, 0));
        NBTTagCompound fractional = packet(144L);
        fractional.getCompoundTag("FluidStack")
            .setDouble("Amount", 144.5);
        assertRejected(
            PatternValidationException.Reason.INVALID_ENCODING,
            () -> OrderedPatternLayout.decodeFluidPacket(fractional, 1L, 0));
    }

    @Test
    public void zeroAndDisplayOnlyPacketsNeverBecomeRealInputs() {
        assertRejected(
            PatternValidationException.Reason.INVALID_AMOUNT,
            () -> OrderedPatternLayout.decodeFluidPacket(packet(0), 1L, 0));
        NBTTagCompound display = packet(1000);
        display.setBoolean("DisplayOnly", true);
        assertRejected(
            PatternValidationException.Reason.INVALID_ENCODING,
            () -> OrderedPatternLayout.decodeFluidPacket(display, 1L, 0));
    }

    private static NBTTagCompound packet(long amount) {
        NBTTagCompound result = new NBTTagCompound(), fluid = new NBTTagCompound();
        FluidRegistryTestBootstrap.water(1)
            .writeToNBT(fluid);
        fluid.setLong("Amount", amount);
        result.setTag("FluidStack", fluid);
        return result;
    }
}
