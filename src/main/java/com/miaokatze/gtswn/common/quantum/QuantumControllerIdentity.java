package com.miaokatze.gtswn.common.quantum;

/** A controller replacement must not inherit the registration of its previous tile. */
public interface QuantumControllerIdentity {

    String gtswn$getControllerIdentity();

    void gtswn$setControllerIdentity(String identity);

    boolean gtswn$isNetworkSafetyEnabled();

    void gtswn$setNetworkSafetyEnabled(boolean enabled);
}
