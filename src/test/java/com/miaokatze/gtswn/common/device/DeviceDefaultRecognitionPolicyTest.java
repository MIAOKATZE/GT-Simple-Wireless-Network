package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.*;

import org.junit.Test;

public class DeviceDefaultRecognitionPolicyTest {

    private static class MTEBasicBatteryBuffer {
    }

    private static class MTECharger extends MTEBasicBatteryBuffer {
    }

    private static class MTETurboCharger extends MTECharger {
    }

    private static class MTETeslaCoil extends MTEBasicBatteryBuffer {
    }

    private static class MTEEnergyBuffer {
    }

    private static class MTETransformer {
    }

    private static class MTEWetTransformer extends MTETransformer {
    }

    private static class MTETransformerHiAmp extends MTETransformer {
    }

    private static class MTEBasicHull {
    }

    private static class MTEDiode extends MTEBasicHull {
    }

    private static class MTEBasicHullNonElectric extends MTEBasicHull {
    }

    private static class MTELongDistancePipelineBase extends MTEBasicHullNonElectric {
    }

    private static class MTEActiveTransformer {
    }

    private static class MTELapotronicSuperCapacitor {
    }

    private static class MTEPowerSubStation {
    }

    private static class MTETeslaTower {
    }

    private static class MTEWirelessCharger {
    }

    private static class MTEQuantumForceTransformer {
    }

    private static class MTETesseractGenerator {
    }

    private static class MTEFusionComputer {
    }

    private static class MTEBECGenerator {
    }

    private static class AntimatterGenerator {
    }

    private static class MTEEyeOfHarmony {
    }

    private static class MTEHatchCapacitor {
    }

    @Test
    public void preciseDistributionFamiliesAndTheirChildrenAreDefaultIgnored() {
        for (Class<?> type : new Class<?>[] { MTEBasicBatteryBuffer.class, MTECharger.class, MTETurboCharger.class,
            MTETeslaCoil.class, MTEEnergyBuffer.class, MTETransformer.class, MTEWetTransformer.class,
            MTETransformerHiAmp.class, MTEBasicHull.class, MTEDiode.class, MTEActiveTransformer.class,
            MTELapotronicSuperCapacitor.class, MTEPowerSubStation.class, MTETeslaTower.class,
            MTEWirelessCharger.class })
            assertTrue(type.getSimpleName(), DeviceDefaultRecognitionPolicy.isPassiveClass(type));
    }

    @Test
    public void processingAndNonElectricBranchesCannotBeIgnoredFromTheirNames() {
        for (Class<?> type : new Class<?>[] { MTEBasicHullNonElectric.class, MTELongDistancePipelineBase.class,
            MTEQuantumForceTransformer.class, MTETesseractGenerator.class, MTEFusionComputer.class,
            MTEBECGenerator.class, AntimatterGenerator.class, MTEEyeOfHarmony.class, MTEHatchCapacitor.class })
            assertFalse(type.getSimpleName(), DeviceDefaultRecognitionPolicy.isPassiveClass(type));
        assertFalse(DeviceDefaultRecognitionPolicy.isPassiveClass(null));
    }
}
