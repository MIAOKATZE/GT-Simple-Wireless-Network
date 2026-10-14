package com.miaokatze.gtswn.common.covers;

import static org.junit.Assert.*;

import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.ForgeDirection;

import org.junit.Test;

import com.miaokatze.gtswn.config.Config;

import gregtech.api.covers.CoverContext;

public class WirelessCoverCopyTest {

    private NBTTagCompound coverData(long energy) {
        NBTTagCompound outer = new NBTTagCompound();
        NBTTagCompound data = new NBTTagCompound();
        data.setLong("storedEU", energy);
        data.setDouble("fractionalEU", 0.8);
        data.setInteger("voltage", 32);
        data.setDouble("amperageDecimal", 0.15);
        data.setBoolean("configured", true);
        data.setLong("ticksSinceLastRefill", 599);
        outer.setTag("d", data);
        return outer;
    }

    @Test
    public void copiedConfigurationExcludesEnergyAndDoesNotMutateSource() {
        NBTTagCompound source = coverData(1234);
        NBTTagCompound copy = WirelessCoverCopy.configuration(source);
        NBTTagCompound data = copy.getCompoundTag("d");
        assertEquals(0, data.getLong("storedEU"));
        assertEquals(0, data.getDouble("fractionalEU"), 0);
        assertEquals(0, data.getLong("ticksSinceLastRefill"));
        assertEquals(32, data.getInteger("voltage"));
        assertEquals(0.15, data.getDouble("amperageDecimal"), 0);
        assertTrue(data.getBoolean("configured"));
        assertEquals(
            1234,
            source.getCompoundTag("d")
                .getLong("storedEU"));
        assertEquals(
            0.8,
            source.getCompoundTag("d")
                .getDouble("fractionalEU"),
            0);
    }

    @Test
    public void oldNativeToolClipboardPreservesOnlyDestinationsOwnEnergy() {
        NBTTagCompound copy = WirelessCoverCopy.configurationPreservingBuffer(coverData(900000), coverData(81));
        assertEquals(
            81,
            copy.getCompoundTag("d")
                .getLong("storedEU"));
        assertEquals(
            0.8,
            copy.getCompoundTag("d")
                .getDouble("fractionalEU"),
            0);
    }

    @Test
    public void fundedBufferRequiresFullAtomicDebitIncludingRoundedLoss() {
        AtomicReference<BigInteger> balance = new AtomicReference<>(BigInteger.valueOf(101));
        AtomicInteger transactions = new AtomicInteger();
        long result = WirelessCoverCopy.fundedBuffer(100, 0.005, amount -> {
            transactions.incrementAndGet();
            assertEquals(BigInteger.valueOf(101), amount);
            balance.set(
                balance.get()
                    .subtract(amount));
            return true;
        });
        assertEquals(100, result);
        assertEquals(BigInteger.ZERO, balance.get());
        assertEquals(1, transactions.get());
    }

    @Test
    public void insufficientBalanceDoesNotPartiallyFundOrWithdraw() {
        AtomicReference<BigInteger> balance = new AtomicReference<>(BigInteger.valueOf(100));
        assertEquals(0, WirelessCoverCopy.fundedBuffer(100, 0.005, amount -> {
            if (balance.get()
                .compareTo(amount) < 0) return false;
            balance.set(
                balance.get()
                    .subtract(amount));
            return true;
        }));
        assertEquals(BigInteger.valueOf(100), balance.get());
    }

    @Test
    public void destinationBufferIsPreservedAndOnlyTheMissingCapacityIsPurchased() {
        AtomicReference<BigInteger> balance = new AtomicReference<>(BigInteger.valueOf(66));
        assertEquals(100, WirelessCoverCopy.refillBuffer(40, 100, 0.1, amount -> {
            assertEquals(BigInteger.valueOf(66), amount);
            balance.set(
                balance.get()
                    .subtract(amount));
            return true;
        }));
        assertEquals(BigInteger.ZERO, balance.get());
        assertEquals(40, WirelessCoverCopy.refillBuffer(40, 100, 0.1, amount -> false));
        assertEquals(140, WirelessCoverCopy.refillBuffer(140, 100, 0.1, amount -> {
            fail("Existing energy above new capacity must remain owned by the destination");
            return false;
        }));
    }

    @Test
    public void configurationEqualityIgnoresPoolsClocksAndDerivedCapacity() {
        NBTTagCompound source = coverData(1234);
        NBTTagCompound target = coverData(9000);
        target.getCompoundTag("d")
            .setDouble("fractionalEU", 0.1);
        target.getCompoundTag("d")
            .setLong("ticksSinceLastRefill", 3);
        target.getCompoundTag("d")
            .setLong("capacity", 50000);
        assertEquals(WirelessCoverCopy.configuration(source), WirelessCoverCopy.configuration(target));
        target.getCompoundTag("d")
            .setDouble("amperageDecimal", 0.2);
        assertNotEquals(WirelessCoverCopy.configuration(source), WirelessCoverCopy.configuration(target));
    }

    @Test
    public void maximumCapacityUsesBigIntegerAndEmptyBuffersDoNotDebit() {
        assertEquals(Long.MAX_VALUE, WirelessCoverCopy.fundedBuffer(Long.MAX_VALUE, 1, amount -> {
            assertEquals(
                BigInteger.valueOf(Long.MAX_VALUE)
                    .shiftLeft(1),
                amount);
            return true;
        }));
        assertEquals(0, WirelessCoverCopy.fundedBuffer(0, 0, amount -> {
            fail("Empty capacity must not issue a transaction");
            return true;
        }));
    }

    @Test
    public void deprecatedFalseCannotDisableCopyAndWorldSaveStillRestoresEnergy() {
        boolean previous = Config.allowCopyPasteTool;
        try {
            Config.allowCopyPasteTool = false;
            GTswn_Cover_EnergyWireless energy = new GTswn_Cover_EnergyWireless(
                new CoverContext(null, ForgeDirection.NORTH, null));
            GTswn_Cover_DynamoWireless dynamo = new GTswn_Cover_DynamoWireless(
                new CoverContext(null, ForgeDirection.NORTH, null));
            assertTrue(energy.allowsCopyPasteTool());
            assertTrue(dynamo.allowsCopyPasteTool());
            energy.readFromNbt(coverData(1234));
            dynamo.readFromNbt(coverData(4567));
            assertEquals(
                1234,
                energy.writeToNBT(new NBTTagCompound())
                    .getCompoundTag("d")
                    .getLong("storedEU"));
            assertEquals(
                4567,
                dynamo.writeToNBT(new NBTTagCompound())
                    .getCompoundTag("d")
                    .getLong("storedEU"));
        } finally {
            Config.allowCopyPasteTool = previous;
        }
    }
}
