package com.miaokatze.gtswn.common.panel;

import static org.junit.Assert.*;

import java.util.Collections;

import org.junit.Test;

public class AEAssemblyCacheTest {

    @Test
    public void controllerReloadCannotReuseOldAssemblyWithResetLocalVersions() {
        AEMonitorDataSet persistent = new AEMonitorDataSet();
        Object firstController = new Object(), reloadedController = new Object();
        String key = "same-panel#window0#binding1#revision2";
        AEAssemblyCache.Assembly beforeUnload = assembly(100);
        AEAssemblyCache.store(firstController, persistent, key, beforeUnload);
        assertSame(beforeUnload, AEAssemblyCache.lookup(firstController, persistent, key));

        // WorldSavedData survives chunk unload; the new controller's local counters restart.
        assertNull(AEAssemblyCache.lookup(reloadedController, persistent, key));
        AEAssemblyCache.Assembly afterReload = assembly(200);
        AEAssemblyCache.store(reloadedController, persistent, key, afterReload);
        assertSame(afterReload, AEAssemblyCache.lookup(reloadedController, persistent, key));
    }

    @Test
    public void unchangedControllerStillReusesAssemblyButReplacementDataDoesNot() {
        Object controller = new Object();
        AEMonitorDataSet data = new AEMonitorDataSet();
        AEAssemblyCache.Assembly assembly = assembly(100);
        String key = "same-controller";
        AEAssemblyCache.store(controller, data, key, assembly);
        assertSame(assembly, AEAssemblyCache.lookup(controller, data, key));
        assertNull(AEAssemblyCache.lookup(controller, new AEMonitorDataSet(), key));
    }

    private static AEAssemblyCache.Assembly assembly(long amount) {
        return new AEAssemblyCache.Assembly(
            "item",
            Collections.singletonList(new AEMonitorSample(0, 100, amount, 0)),
            Collections.emptyMap(),
            Collections.emptyMap());
    }
}
