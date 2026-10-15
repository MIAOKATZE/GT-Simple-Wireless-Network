package com.miaokatze.gtswn.client;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.miaokatze.gtswn.common.machine.MTEWirelessEnergyMonitor;
import com.miaokatze.gtswn.common.util.GTTierUtil;

import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchDynamo;
import gregtech.api.metatileentity.implementations.MTEHatchEnergy;
import tectech.thing.metaTileEntity.hatch.MTEHatchDynamoTunnel;
import tectech.thing.metaTileEntity.hatch.MTEHatchEnergyTunnel;

public class LinkNodeVoltageTierTest {

    @Test
    public void energyAndDynamoMachinesUseTheirStaticTierAcrossThePalette() {
        for (int tier = 0; tier < GTTierUtil.TIER_NAMES.length; tier++) {
            assertEquals(
                GTTierUtil.tierRgb(tier),
                LinkNodeVoltageTier.rgb(tile(new MTEHatchEnergy("energy", tier, new String[0], null))));
            assertEquals(
                GTTierUtil.tierRgb(tier),
                LinkNodeVoltageTier.rgb(tile(new MTEHatchDynamo("dynamo", tier, new String[0], null))));
        }
    }

    @Test
    public void laserSourceKeepsItsTierEvenThoughOrdinaryOutputVoltageIsZero() {
        BaseMetaTileEntity source = tile(new MTEHatchDynamoTunnel("laser_source", 8, 64, new String[0], null));
        assertEquals(0, source.getOutputVoltage());
        assertEquals(GTTierUtil.tierRgb(8), LinkNodeVoltageTier.rgb(source));
        assertEquals(
            GTTierUtil.tierRgb(6),
            LinkNodeVoltageTier.rgb(tile(new MTEHatchEnergyTunnel("laser_target", 6, 64, new String[0], null))));
    }

    @Test
    public void unresolvedAndUnknownMachinesNeverBecomeUlvOrSentinelMax() {
        BaseMetaTileEntity unresolved = new BaseMetaTileEntity();
        assertEquals(Integer.MAX_VALUE, unresolved.getInputVoltage());
        assertEquals(-1, LinkNodeVoltageTier.rgb(unresolved));
        assertEquals(-1, LinkNodeVoltageTier.rgb(null));
        assertEquals(-1, LinkNodeVoltageTier.rgb(new Object()));
        MTEWirelessEnergyMonitor unknown = new MTEWirelessEnergyMonitor("unknown_tier", new String[0]);
        assertEquals(-1, LinkNodeVoltageTier.tier(unknown));
        assertEquals(-1, LinkNodeVoltageTier.rgb(tile(unknown)));
        assertEquals(-1, LinkNodeVoltageTier.rgb(tile(new MTEHatchEnergy("invalid_tier", 15, new String[0], null))));
    }

    private static BaseMetaTileEntity tile(MetaTileEntity machine) {
        BaseMetaTileEntity tile = new BaseMetaTileEntity();
        machine.setBaseMetaTileEntity(tile);
        return tile;
    }
}
