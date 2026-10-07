package com.miaokatze.gtswn.crossmod.nei;

import com.miaokatze.gtswn.Tags;
import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;

import codechicken.nei.api.API;
import codechicken.nei.api.IConfigureNEI;
import codechicken.nei.event.NEIRegisterHandlerInfosEvent;
import codechicken.nei.recipe.HandlerInfo;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** NEI discovers this client-only configuration after all mods have registered their items. */
public final class NEIGTSWNConfig implements IConfigureNEI {

    @Override
    public void loadConfig() {
        MonitorBatteryRecipeHandler handler = new MonitorBatteryRecipeHandler();
        API.registerRecipeHandler(handler);
        API.registerUsageHandler(handler);
    }

    @SubscribeEvent
    public void onHandlerInfo(NEIRegisterHandlerInfosEvent event) {
        event.registerHandlerInfo(
            new HandlerInfo.Builder(MonitorBatteryRecipeHandler.class, getName(), "gtswn")
                .setDisplayStack(GTSWNItemList.Portable_Wireless_Network_Monitor.get(1))
                .setHeight(86)
                .setMaxRecipesPerPage(2)
                .setShowOverlayButton(false)
                .build());
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
