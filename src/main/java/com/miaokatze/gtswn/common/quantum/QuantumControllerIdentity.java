// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.quantum;

/** A controller replacement must not inherit the registration of its previous tile. */
public interface QuantumControllerIdentity {

    String gtswn$getControllerIdentity();

    void gtswn$setControllerIdentity(String identity);

    boolean gtswn$isNetworkSafetyEnabled();

    void gtswn$setNetworkSafetyEnabled(boolean enabled);
}
