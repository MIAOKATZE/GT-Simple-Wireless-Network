package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.*;

import org.junit.Test;

public class DeviceSteamCapabilitiesTest {

    private static class MTEBoiler {
    }

    private static class MTEAdvancedBoilerLV extends MTEBoiler {
    }

    private static class MTEHeatExchanger {
    }

    private static class MTEExtremeHeatExchanger {
    }

    private static class MTESteamTurbine {
    }

    private static class MTELargeTurbineHPSteam {
    }

    private static class MTEXLTurbineSCSteam {
    }

    private static class MTESupercriticalFluidTurbineLegacy {
    }

    private static class MTESteamMultiBlockBase {
    }

    private static class ElectricSteamRecipeMachine {
    }

    private static class PretendBoilerSteamTurbine {
    }

    @Test
    public void exactFamiliesAndTheirSubclassesRetainIdleSteamRoles() {
        assertEquals(8, DeviceSteamAdapter.classSteamRoles(MTEAdvancedBoilerLV.class));
        assertEquals(8, DeviceSteamAdapter.classSteamRoles(MTEHeatExchanger.class));
        assertEquals(8, DeviceSteamAdapter.classSteamRoles(MTEExtremeHeatExchanger.class));
        assertEquals(6, DeviceSteamAdapter.classSteamRoles(MTESteamTurbine.class));
        assertEquals(14, DeviceSteamAdapter.classSteamRoles(MTELargeTurbineHPSteam.class));
        assertEquals(14, DeviceSteamAdapter.classSteamRoles(MTEXLTurbineSCSteam.class));
        assertEquals(14, DeviceSteamAdapter.classSteamRoles(MTESupercriticalFluidTurbineLegacy.class));
        assertEquals(4, DeviceSteamAdapter.classSteamRoles(MTESteamMultiBlockBase.class));
        assertEquals(0, DeviceSteamAdapter.classSteamRoles(ElectricSteamRecipeMachine.class));
        assertEquals(0, DeviceSteamAdapter.classSteamRoles(PretendBoilerSteamTurbine.class));
        assertEquals(0, DeviceSteamAdapter.classSteamRoles(null));
    }

    @Test
    public void actualRecipeSteamCannotPromoteAnElectricMachine() {
        byte roles = DeviceSteamAdapter
            .observedCapabilities((byte) 1, 100, 0, 200, 300, new double[] { 100, 0, 200, 300 });
        assertEquals(1, roles);
        assertEquals(DeviceSteamAdapter.EU, DeviceSteamAdapter.energyKind(roles));
    }

    @Test
    public void genuineMixedChannelsRetainEveryIndependentDirection() {
        byte roles = DeviceSteamAdapter.observedCapabilities((byte) 6, 10, 20, 30, 40, new double[] { 10, 20, 30, 40 });
        assertEquals(15, roles);
        assertEquals(DeviceSteamAdapter.MIXED, DeviceSteamAdapter.energyKind(roles));
        assertEquals(DeviceSteamAdapter.STEAM, DeviceSteamAdapter.energyKind((byte) 8));
        assertEquals(6, DeviceSteamAdapter.legacyCapabilityMask(DeviceSteamAdapter.MIXED, (byte) 1));
    }
}
