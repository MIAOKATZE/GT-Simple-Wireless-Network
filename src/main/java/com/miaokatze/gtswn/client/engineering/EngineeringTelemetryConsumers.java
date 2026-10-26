package com.miaokatze.gtswn.client.engineering;

/** The union of actual rendering hosts, not an F1-only network switch. */
public final class EngineeringTelemetryConsumers {

    private EngineeringTelemetryConsumers() {}

    public static boolean visible(boolean workbench, boolean dashboard, boolean worldOverlay) {
        return workbench || dashboard || worldOverlay;
    }
}
