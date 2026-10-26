package com.miaokatze.gtswn.common.monitoring.ae;

import java.util.UUID;

import appeng.api.networking.IGridCache;

/** Server-authoritative, persisted monitoring identity; null while native topology is unsettled. */
public interface IMonitorIdentityCache extends IGridCache {

    UUID monitoringIdentity();
}
