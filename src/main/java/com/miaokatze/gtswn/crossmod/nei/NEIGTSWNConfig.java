package com.miaokatze.gtswn.crossmod.nei;

import com.miaokatze.gtswn.Tags;

import codechicken.nei.api.API;
import codechicken.nei.api.IConfigureNEI;

/** NEI discovers this client-only configuration after all mods have registered their items. */
public final class NEIGTSWNConfig implements IConfigureNEI {

    @Override
    public void loadConfig() {
        MonitorBatteryRecipeHandler handler = new MonitorBatteryRecipeHandler();
        API.registerRecipeHandler(handler);
        API.registerUsageHandler(handler);
    }

    @Override
    public String getName() {
        return "GT Simple Wireless Network";
    }

    @Override
    public String getVersion() {
        return Tags.VERSION;
    }
}
