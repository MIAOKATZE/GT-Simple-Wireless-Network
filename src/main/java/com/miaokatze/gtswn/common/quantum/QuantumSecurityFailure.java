// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.quantum;

/** Request-local diagnosis. Contains no saved authority or permission verdict cache. */
public enum QuantumSecurityFailure {

    NONE(null),
    INVALID_TARGET("target_invalid"),
    UNLOADED("anchor_unloaded"),
    NETWORK_NOT_READY("network_not_ready"),
    GRID_SPLIT("grid_split"),
    NO_PROVIDER("security_missing"),
    MULTIPLE_PROVIDERS("security_multiple"),
    PROVIDER_UNAVAILABLE("security_not_ready"),
    TOPOLOGY_DISCONNECTED("security_topology_unsafe"),
    SEARCH_LIMIT("security_search_limit"),
    PAIRING_REQUIRED("pairing_required"),
    PAIRING_MISMATCH("pairing_mismatch"),
    BUILD_REQUIRED("build_required"),
    SECURITY_REQUIRED("manage_permission_required"),
    ACTOR_UNKNOWN("node_actor_unknown");

    private final String key;

    QuantumSecurityFailure(String suffix) {
        key = suffix == null ? null : "gtswn.chat.quantum." + suffix;
    }

    public String key() {
        return key;
    }
}
