package com.miaokatze.gtswn.common.testworld;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Run-owned observations, never a background sampler. No MXBean is acquired until a window is opened. */
public final class TestWorldPerformance {

    public static final int SAMPLE_LIMIT = 2048;
    static final int OPERATION_LIMIT = 32;
    private final Sources sources;
    private final long started;
    private final Resources before;
    private final long nativeAttemptsBefore;
    private final Samples naturalWall = new Samples();
    private final Samples naturalCpu = new Samples();
    private final Samples managerWall = new Samples();
    private final Samples managerCpu = new Samples();
    private final Map<String, Samples> operations = new LinkedHashMap<>();
    private Snapshot completed;
    private long droppedOperationNames;

    public TestWorldPerformance() {
        this(new JvmSources());
    }

    TestWorldPerformance(Sources sources) {
        this.sources = sources;
        started = sources.nanoTime();
        before = sources.resources();
        nativeAttemptsBefore = sources.nativeAeConnectionAttempts();
    }

    long cpuTime() {
        return sources.cpuTime();
    }

    void tick(long wallNanos, long cpuNanos, long workNanos, long workCpuNanos) {
        if (completed != null) return;
        if (wallNanos >= 0) naturalWall.add(wallNanos);
        if (cpuNanos >= 0) naturalCpu.add(cpuNanos);
        managerWall.add(workNanos);
        if (workCpuNanos >= 0) managerCpu.add(workCpuNanos);
    }

    void operation(String name, long nanos) {
        if (completed != null || name == null || name.isEmpty() || name.length() > 96 || nanos < 0) return;
        Samples samples = operations.get(name);
        if (samples == null) {
            if (operations.size() >= OPERATION_LIMIT) {
                droppedOperationNames++;
                return;
            }
            samples = new Samples();
            operations.put(name, samples);
        }
        samples.add(nanos);
    }

    Snapshot finish() {
        if (completed == null) {
            Map<String, Distribution> named = new LinkedHashMap<>();
            for (Map.Entry<String, Samples> entry : operations.entrySet()) named.put(
                entry.getKey(),
                entry.getValue()
                    .snapshot());
            completed = new Snapshot(
                sources.nanoTime() - started,
                before,
                sources.resources(),
                naturalWall.snapshot(),
                naturalCpu.count == 0 ? null : naturalCpu.snapshot(),
                managerWall.snapshot(),
                managerCpu.count == 0 ? null : managerCpu.snapshot(),
                named,
                droppedOperationNames,
                delta(nativeAttemptsBefore, sources.nativeAeConnectionAttempts()));
        }
        return completed;
    }

    static long delta(long begin, long end) {
        return begin < 0 || end < begin ? -1 : end - begin;
    }

    public static final class Snapshot {

        public final String status;
        public final String scope = "Natural server HIGHEST START to LOWEST END handler work completion; includes other mods/server work, excludes listeners before/after these boundaries and sampling/finalization overhead. CPU is this server thread only. Manager is this END handler work, attributed to phase at handler entry (including transition callbacks). Neither is mod-exclusive cost or causal attribution; no TPS/FPS assertion.";
        public final String resourceScope = "Heap and GC are JVM-wide boundary observations, including concurrent unrelated work; no forced GC. Negative GC counters mean unavailable.";
        public final Long nativeAeConnectionAttempts;
        public final String nativeAeConnectionAttemptsScope = "Accepted enrolled fixture endpoints in the test dimension only: GridConnection constructions reaching the native Platform.securityCheck redirect, before authorization verdict (including rejected attempts). Invalid constructors throwing before this gate are excluded. Two accepted endpoints count once; relay provenance uses its owner tile. Process-monotonic counter delta survives scenario session/reset boundaries. Zero means no accepted gate observation, not proof of connection success. Global PerformanceAudit configuration is unchanged.";
        public final long elapsedWallNanos;
        public final Resources before, after;
        public final Distribution naturalServerWindowWallNanos, naturalServerThreadCpuNanos;
        public final Distribution managerHandlerWallNanos, managerHandlerCpuNanos;
        public final Map<String, Distribution> namedOperationsWallNanos;
        public final long droppedOperationNames;

        private Snapshot(long elapsed, Resources before, Resources after, Distribution wall, Distribution cpu,
            Distribution manager, Distribution managerCpu, Map<String, Distribution> named, long dropped,
            long nativeAttempts) {
            status = wall.sampleCount == 0 ? "NOT_OBSERVED" : "OBSERVED_NO_PERFORMANCE_ASSERTION";
            elapsedWallNanos = elapsed;
            this.before = before;
            this.after = after;
            naturalServerWindowWallNanos = wall;
            naturalServerThreadCpuNanos = cpu;
            managerHandlerWallNanos = manager;
            managerHandlerCpuNanos = managerCpu;
            namedOperationsWallNanos = Collections.unmodifiableMap(named);
            droppedOperationNames = dropped;
            nativeAeConnectionAttempts = nativeAttempts < 0 ? null : nativeAttempts;
        }
    }

    /** Exact count/total/max; quantiles use the most recent bounded sample, explicitly disclosed in JSON. */
    public static final class Samples {

        private final long[] recent = new long[SAMPLE_LIMIT];
        private long count, total, max;

        public void add(long nanos) {
            if (nanos < 0) return;
            recent[(int) (count % SAMPLE_LIMIT)] = nanos;
            count++;
            total += nanos;
            max = Math.max(max, nanos);
        }

        public Distribution snapshot() {
            int retained = (int) Math.min(count, SAMPLE_LIMIT);
            long[] ordered = Arrays.copyOf(recent, retained);
            Arrays.sort(ordered);
            return new Distribution(count, total, max, ordered);
        }
    }

    public static final class Distribution {

        public final String percentileMethod = "nearest-rank over most recent at most 2048 samples; count/total/max cover the entire window";
        public final long sampleCount, totalNanos, maxNanos;
        public final int percentileSampleCount;
        public final Long p50Nanos, p95Nanos;

        private Distribution(long count, long total, long max, long[] ordered) {
            sampleCount = count;
            totalNanos = total;
            maxNanos = max;
            percentileSampleCount = ordered.length;
            p50Nanos = quantile(ordered, 0.50);
            p95Nanos = quantile(ordered, 0.95);
        }

        private static Long quantile(long[] ordered, double percentile) {
            return ordered.length == 0 ? null : ordered[(int) Math.ceil(ordered.length * percentile) - 1];
        }
    }

    public static final class Resources {

        public final long heapUsedBytes, heapCommittedBytes, heapMaxBytes, gcCollections, gcMillis;

        Resources(long used, long committed, long max, long collections, long millis) {
            heapUsedBytes = used;
            heapCommittedBytes = committed;
            heapMaxBytes = max;
            gcCollections = collections;
            gcMillis = millis;
        }
    }

    interface Sources {

        long nanoTime();

        long cpuTime();

        Resources resources();

        default long nativeAeConnectionAttempts() {
            return TestWorldProbes.nativeAeConnectionAttempts();
        }
    }

    private static final class JvmSources implements Sources {

        private final ThreadMXBean threads;

        JvmSources() {
            ThreadMXBean available;
            try {
                available = ManagementFactory.getThreadMXBean();
            } catch (RuntimeException | LinkageError unsupported) {
                available = null;
            }
            threads = available;
        }

        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        @Override
        public long cpuTime() {
            try {
                return threads != null && threads.isCurrentThreadCpuTimeSupported() && threads.isThreadCpuTimeEnabled()
                    ? threads.getCurrentThreadCpuTime()
                    : -1;
            } catch (RuntimeException | LinkageError unsupported) {
                return -1;
            }
        }

        @Override
        public Resources resources() {
            Runtime runtime = Runtime.getRuntime();
            long count = 0, millis = 0;
            try {
                for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                    long gcCount = gc.getCollectionCount(), gcTime = gc.getCollectionTime();
                    count = count < 0 || gcCount < 0 ? -1 : count + gcCount;
                    millis = millis < 0 || gcTime < 0 ? -1 : millis + gcTime;
                }
            } catch (RuntimeException | LinkageError unsupported) {
                count = -1;
                millis = -1;
            }
            return new Resources(
                runtime.totalMemory() - runtime.freeMemory(),
                runtime.totalMemory(),
                runtime.maxMemory(),
                count,
                millis);
        }
    }
}
