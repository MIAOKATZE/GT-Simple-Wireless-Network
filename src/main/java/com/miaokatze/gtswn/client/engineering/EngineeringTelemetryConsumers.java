// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.client.engineering;

/** The union of actual rendering hosts, not an F1-only network switch. */
public final class EngineeringTelemetryConsumers {

    private EngineeringTelemetryConsumers() {}

    public static boolean visible(boolean workbench, boolean dashboard, boolean worldOverlay) {
        return workbench || dashboard || worldOverlay;
    }
}
