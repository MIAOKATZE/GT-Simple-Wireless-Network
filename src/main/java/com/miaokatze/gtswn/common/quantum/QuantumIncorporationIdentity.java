// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.quantum;

/** Persistent per-tile identity supplied by the Minecraft TileEntity mixin. */
public interface QuantumIncorporationIdentity {

    String gtswn$getIncorporationIdentity();

    void gtswn$setIncorporationIdentity(String identity);
}
