// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.monitoring;

/** Optional persisted marker on actual native tiles; absence fails profile authorization closed. */
public interface MonitorDeviceIdentity {

    String gtswn$getMonitorDeviceIdentity();

    void gtswn$setMonitorDeviceIdentity(String identity);
}
