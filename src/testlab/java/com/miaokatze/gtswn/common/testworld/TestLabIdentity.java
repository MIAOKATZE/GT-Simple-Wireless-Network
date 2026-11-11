// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.testworld;

import java.util.Map;

import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;
import com.miaokatze.miaokatzecore.api.testlab.ArtifactIdentity;

public final class TestLabIdentity {

    public static final Map<String, String> ARTIFACT = ArtifactIdentity.capture(GTSimpleWirelessNetwork.class)
        .asMap();
    public static final Map<String, String> TESTLAB_ARTIFACT = ArtifactIdentity.capture(TestLabIdentity.class)
        .asMap();

    private TestLabIdentity() {}
}
