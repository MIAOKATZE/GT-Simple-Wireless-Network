package com.miaokatze.gtswn.client;

import static org.junit.Assert.assertNotNull;

import org.junit.Test;

public class DeviceTerminalAnchorRendererTest {

    @Test
    public void clientRegistrationCanInitializeTheRendererSingleton() {
        assertNotNull(DeviceTerminalAnchorRenderer.INSTANCE);
    }
}
