package com.miaokatze.gtswn.common.engineering;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot.Cell;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot.Power;

/** One bounded region observation; the session owns comparisons and baseline lifetime. */
public final class EngineeringPowerResult {

    public final Power total;
    public final List<Cell> cells;
    public final Set<String> monitoredKeys;
    public final Map<String, Power> samples;
    public final int unknown, totalBound;
    public final boolean truncated, boundOnly;
    public final int loadedChunks, totalChunks, scannedChunks, modeled;
    public final boolean discovering;

    public EngineeringPowerResult(Power total, List<Cell> cells, Map<String, Power> samples, int unknown,
        int totalBound, boolean truncated, boolean boundOnly, int loadedChunks, int totalChunks, int scannedChunks,
        int modeled, boolean discovering) {
        this.total = total;
        this.cells = Collections.unmodifiableList(new ArrayList<>(cells));
        this.samples = Collections.unmodifiableMap(new HashMap<>(samples));
        this.monitoredKeys = Collections.unmodifiableSet(new HashSet<>(samples.keySet()));
        this.unknown = unknown;
        this.totalBound = totalBound;
        this.truncated = truncated;
        this.boundOnly = boundOnly;
        this.loadedChunks = loadedChunks;
        this.totalChunks = totalChunks;
        this.scannedChunks = scannedChunks;
        this.modeled = modeled;
        this.discovering = discovering;
    }

}
