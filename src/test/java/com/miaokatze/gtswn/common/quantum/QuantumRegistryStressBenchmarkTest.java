package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import com.google.gson.GsonBuilder;

/** Optional wall-clock experiment. Ordinary tests skip before constructing any fixture. */
public class QuantumRegistryStressBenchmarkTest {

    @Test
    public void explicitlySelectedRegistryStress() throws Exception {
        assumeTrue("Select with -Dgtswn.stress=true in the test JVM", Boolean.getBoolean("gtswn.stress"));
        Support.validateSelection();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int side : Support.sides()) {
            for (String phase : new String[] { "cold", "warm_aliases", "topology_change" }) {
                for (int sample = -Support.warmups(); sample < Support.samples(); sample++) {
                    Metrics metrics = new Metrics();
                    CountingRegistry registry = new CountingRegistry(metrics);
                    Set<Long> positions = cube(side);
                    registry.quantizeAll(positions);
                    long[] aliases = aliases(side);
                    if (!"cold".equals(phase)) registry.registeredStructure(aliases[0]);
                    // Invalidate outside the timed section, preserving exactly the same final topology.
                    if ("topology_change".equals(phase)) {
                        registry.dequantize(aliases[0]);
                        registry.quantize(0, 64, 0);
                    }
                    metrics.clear();
                    long started = System.nanoTime();
                    long checksum = 0;
                    int calls = "cold".equals(phase) ? 1 : Support.calls();
                    for (int call = 0; call < calls; call++)
                        checksum += registry.registeredStructure(aliases[call % aliases.length])
                            .size();
                    long nanos = System.nanoTime() - started;
                    assertEquals((long) side * side * side * calls, checksum);
                    if (sample >= 0) rows.add(Support.row("registry_" + phase, side, sample, calls, nanos, metrics));
                }
            }
        }
        Support.write("registry", rows);
    }

    public static void main(String[] args) throws Exception {
        if (!Boolean.getBoolean("gtswn.stress")) throw new IllegalStateException("Requires -Dgtswn.stress=true");
        new QuantumRegistryStressBenchmarkTest().explicitlySelectedRegistryStress();
    }

    static Set<Long> cube(int side) {
        Set<Long> positions = new HashSet<>();
        for (int x = 0; x < side; x++) for (int y = 64; y < 64 + side; y++)
            for (int z = 0; z < side; z++) positions.add(QuantumControllerRegistry.pack(x, y, z));
        return positions;
    }

    static long[] aliases(int side) {
        return new long[] { QuantumControllerRegistry.pack(0, 64, 0), QuantumControllerRegistry.pack(side - 1, 64, 0),
            QuantumControllerRegistry.pack(0, 64 + side - 1, side - 1),
            QuantumControllerRegistry.pack(side - 1, 64 + side - 1, side - 1) };
    }

    static final class CountingRegistry extends QuantumControllerRegistry {

        final Metrics metrics;

        CountingRegistry(Metrics metrics) throws Exception {
            super(DATA_NAME);
            this.metrics = metrics;
            Field backing = QuantumControllerRegistry.class.getDeclaredField("controllers");
            backing.setAccessible(true);
            backing.set(this, new HashSet<Long>() {

                @Override
                public boolean contains(Object value) {
                    metrics.membershipProbes++;
                    return super.contains(value);
                }
            });
        }

        @Override
        public Set<Long> registeredStructure(long start) {
            metrics.structureQueries++;
            return super.registeredStructure(start);
        }
    }

    static final class Metrics {

        long membershipProbes, structureQueries, worldExists, worldTiles, safetyReads, gridClasses, gridMachines,
            machineReads, permissionReads, connectionReads, channelReads;

        void clear() {
            membershipProbes = structureQueries = worldExists = worldTiles = safetyReads = gridClasses = gridMachines = machineReads = permissionReads = connectionReads = channelReads = 0;
        }
    }

    static final class Support {

        private static final Map<Class<?>, Class<?>> FIXTURE_TYPES = new HashMap<>();
        private static final List<Map<String, Object>> FIXTURE_PROVENANCE = new ArrayList<>();
        private static Class<?> policyMarker;
        private static Method epochChanged;
        private static long epochNotifications;
        private static boolean policyApiDetected;

        private static synchronized void detectPolicyApi() throws Exception {
            if (policyApiDetected) return;
            policyApiDetected = true;
            try {
                Class<?> epoch = Class.forName("com.miaokatze.gtswn.common.quantum.QuantumPolicyEpoch");
                policyMarker = Class.forName("com.miaokatze.gtswn.common.quantum.QuantumPolicyEpoch$TrackedController");
                epochChanged = epoch.getMethod("changed");
                Object source = epoch.getProtectionDomain()
                    .getCodeSource()
                    .getLocation();
                if (!source.equals(
                    QuantumControllerRegistry.class.getProtectionDomain()
                        .getCodeSource()
                        .getLocation()))
                    throw new IllegalStateException("Policy API does not come from selected production JAR");
                if (!source.equals(
                    policyMarker.getProtectionDomain()
                        .getCodeSource()
                        .getLocation()))
                    throw new IllegalStateException("Policy marker does not come from selected production JAR");
                System.out.println("FIXTURE_POLICY_MARKER present=true source=" + source);
            } catch (ClassNotFoundException absent) {
                System.out.println("FIXTURE_POLICY_MARKER present=false (historical API absent)");
            }
        }

        /** Same empty subclass shape on every version; only the real optional marker differs. */
        @SuppressWarnings("unchecked")
        static synchronized <T> Class<? extends T> fixtureControllerType(Class<T> parent) throws Exception {
            detectPolicyApi();
            Class<?> previous = FIXTURE_TYPES.get(parent);
            if (previous != null) return (Class<? extends T>) previous;
            String name = parent.getName() + "$BenchmarkFixture";
            ClassWriter writer = new ClassWriter(0);
            writer.visit(
                Opcodes.V1_8,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                name.replace('.', '/'),
                null,
                parent.getName()
                    .replace('.', '/'),
                policyMarker == null ? null
                    : new String[] { policyMarker.getName()
                        .replace('.', '/') });
            writer.visitEnd();
            byte[] bytes = writer.toByteArray();
            Class<?> generated = new ClassLoader(parent.getClassLoader()) {

                Class<?> define() {
                    return defineClass(name, bytes, 0, bytes.length, Support.class.getProtectionDomain());
                }
            }.define();
            Map<String, Object> provenance = new LinkedHashMap<>();
            provenance.put("class", name);
            provenance.put("markerImplemented", policyMarker != null && policyMarker.isAssignableFrom(generated));
            provenance.put(
                "codeSource",
                generated.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toString());
            FIXTURE_PROVENANCE.add(provenance);
            FIXTURE_TYPES.put(parent, generated);
            System.out.println("FIXTURE_CONTROLLER " + provenance);
            return (Class<? extends T>) generated;
        }

        /** Mirrors the real Mixin setter notification; this adapter contains no policy cache. */
        static void fixturePolicyChanged() {
            if (epochChanged == null) return;
            try {
                epochChanged.invoke(null);
                epochNotifications++;
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Fixture epoch notification failed", failure);
            }
        }

        static int[] sides() {
            String size = System.getProperty("gtswn.stress.side", "both");
            if ("7".equals(size)) return new int[] { 7 };
            if ("16".equals(size)) return new int[] { 16 };
            if (!"both".equals(size)) throw new IllegalArgumentException("side must be 7, 16 or both");
            return new int[] { 7, 16 };
        }

        static int warmups() {
            return positive("gtswn.stress.warmups", 2);
        }

        static int samples() {
            return positive("gtswn.stress.samples", 5);
        }

        static int calls() {
            return positive("gtswn.stress.calls", 4);
        }

        static void validateSelection() {
            if (System.getProperty("gtswn.stress.label") == null || System.getProperty("gtswn.stress.output") == null)
                throw new IllegalArgumentException("Set gtswn.stress.label and gtswn.stress.output explicitly");
            if (Files.exists(Paths.get(System.getProperty("gtswn.stress.output"))))
                throw new IllegalArgumentException("Choose a fresh output path");
            sides();
            warmups();
            samples();
            calls();
        }

        private static int positive(String key, int fallback) {
            int value = Integer.getInteger(key, fallback);
            if (value < 1) throw new IllegalArgumentException(key + " must be positive");
            return value;
        }

        static Map<String, Object> row(String phase, int side, int sample, int calls, long nanos, Metrics metrics) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("phase", phase);
            row.put("side", side);
            row.put("controllers", side * side * side);
            row.put("sample", sample);
            row.put("driverCalls", calls);
            row.put("wallNanos", nanos);
            row.put("structureQueries", metrics.structureQueries);
            row.put("backingMembershipProbes", metrics.membershipProbes);
            row.put("worldBlockExists", metrics.worldExists);
            row.put("worldGetTileEntity", metrics.worldTiles);
            row.put("controllerSafetyPropertyReads", metrics.safetyReads);
            row.put("gridMachineClassEnumerations", metrics.gridClasses);
            row.put("gridMachineEnumerations", metrics.gridMachines);
            row.put("nodeGetMachine", metrics.machineReads);
            row.put("providerPermissionReads", metrics.permissionReads);
            row.put("nodeGetConnections", metrics.connectionReads);
            row.put("connectionGetUsedChannels", metrics.channelReads);
            // This harness never invokes AEApi.createGridConnection or natural node maintenance.
            row.put("aeConnectionAttempts", null);
            return row;
        }

        static void write(String suite, List<Map<String, Object>> rows) throws Exception {
            String label = System.getProperty("gtswn.stress.label");
            String output = System.getProperty("gtswn.stress.output");
            if (label == null || output == null)
                throw new IllegalArgumentException("Set gtswn.stress.label and gtswn.stress.output explicitly");
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("schema", "gtswn-isolated-stress-v1");
            report.put("suite", suite);
            report.put("label", label);
            report.put("sourceRef", System.getProperty("gtswn.stress.sourceRef", "UNRECORDED"));
            report.put("javaVersion", System.getProperty("java.version"));
            report.put("javaVm", System.getProperty("java.vm.name"));
            report.put("javaVendor", System.getProperty("java.vendor"));
            report.put(
                "jvmArguments",
                ManagementFactory.getRuntimeMXBean()
                    .getInputArguments());
            report.put("os", System.getProperty("os.name"));
            report.put("osVersion", System.getProperty("os.version"));
            report.put("architecture", System.getProperty("os.arch"));
            report.put("cpu", System.getenv("PROCESSOR_IDENTIFIER"));
            report.put(
                "logicalProcessors",
                Runtime.getRuntime()
                    .availableProcessors());
            report.put(
                "maxHeapBytes",
                Runtime.getRuntime()
                    .maxMemory());
            report.put("warmupSamplesPerPhase", warmups());
            report.put("measuredSamplesPerPhase", samples());
            report.put("aliasCount", 4);
            report.put("fixturePolicyMarkerPresent", policyMarker != null);
            report.put("fixtureEpochNotifications", epochNotifications);
            report.put("fixtureControllerClasses", FIXTURE_PROVENANCE);
            report.put("rows", rows);
            Path path = Paths.get(output);
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            // A caller must choose a fresh result path; never overwrite prior evidence.
            Files.write(
                path,
                new GsonBuilder().serializeNulls()
                    .setPrettyPrinting()
                    .create()
                    .toJson(report)
                    .getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.CREATE_NEW);
        }
    }
}
