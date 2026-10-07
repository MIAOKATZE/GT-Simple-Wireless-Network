package com.miaokatze.gtswn.common.quantum;

/** Persistent per-tile identity supplied by the Minecraft TileEntity mixin. */
public interface QuantumIncorporationIdentity {

    String gtswn$getIncorporationIdentity();

    void gtswn$setIncorporationIdentity(String identity);
}
