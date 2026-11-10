package com.miaokatze.gtswn.common.testworld;

import java.io.File;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import net.minecraft.command.ICommandSender;
import net.minecraft.world.World;

import com.miaokatze.gtswn.common.diagnostics.BusinessDiagnostics;
import com.miaokatze.gtswn.common.diagnostics.SlotDiagnostics;
import com.miaokatze.gtswn.common.util.DebugTestGate;
import com.miaokatze.miaokatzecore.api.testlab.LabCase;
import com.miaokatze.miaokatzecore.api.testlab.LabModule;
import com.miaokatze.miaokatzecore.api.testlab.LabRegistry;

import appeng.api.networking.IGridNode;

/** Loaded by name only after startup configuration explicitly enables the replacement variant. */
public final class TestLabBootstrap implements DebugTestGate.Harness, LabModule {

    public TestLabBootstrap() {}

    @Override
    public void preInit(File configDir) {
        try {
            Class.forName("com.miaokatze.miaokatzecore.testlab.LabDriver", false, getClass().getClassLoader());
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException(
                "GTSWN testlab requires the matching MiaoKatzeCore-testlab replacement JAR; remove the ordinary Core JAR before installing it.",
                failure);
        }
        TestDimension.init(configDir);
        SlotDiagnostics.install(SlotOverrideAudit::observe);
        LabRegistry.register(this);
    }

    @Override
    public void init() {
        TestWorldManager.init();
    }

    @Override
    public void registerBlocks() {
        StressSupplyTile.register();
    }

    @Override
    public void serverStarting() {
        TestWorldManager.serverStarting();
    }

    @Override
    public void clientInit() {
        com.miaokatze.gtswn.client.testworld.UiDebugSuite.register();
        // The legacy JVM property is an explicit benchmark invocation, never part of all.
        com.miaokatze.gtswn.client.testworld.EffectsBenchmark.registerIfRequested();
    }

    @Override
    public void shutdown() {
        TestWorldManager.shutdown();
    }

    @Override
    public void execute(ICommandSender sender, String[] args) {
        if (args.length > 1 && "run".equals(args[0]) && args[1].startsWith("ui."))
            throw new net.minecraft.command.CommandException(
                "Client task: use /gtswnui batch " + args[1].substring(3) + " quick with an actual OP2 connection");
        if (args.length > 1 && "run".equals(args[0]) && "jvm.effects_benchmark".equals(args[1]))
            throw new net.minecraft.command.CommandException(
                "Separate JVM task: invoke the hidden effects runner with -Dgtswn.effectsBenchmark=<label>; it is never part of all");
        String[] legacy;
        if (args.length > 0 && "test".equals(args[0])) legacy = args;
        else {
            legacy = new String[args.length + 1];
            legacy[0] = "test";
            System.arraycopy(args, 0, legacy, 1, args.length);
        }
        TestWorldCommand.execute(sender, legacy);
    }

    @Override
    public List<String> complete(String[] args) {
        return TestWorldCommand.complete(args);
    }

    @Override
    public String id() {
        return "gtswn";
    }

    @Override
    public boolean enabled() {
        return DebugTestGate.isEnabled();
    }

    @Override
    public String status() {
        return TestWorldManager.status();
    }

    @Override
    public java.util.Map<String, Object> snapshot() {
        return TestWorldManager.snapshot();
    }

    @Override
    public boolean ownsWorld(World world) {
        return TestDimension.isTest(world);
    }

    @Override
    public List<LabCase> cases() {
        List<LabCase> result = new ArrayList<>();
        for (TestScenario scenario : TestWorldManager.scenarios()) {
            boolean client = "device.paged_client".equals(scenario.id())
                || "quantum.safety_client".equals(scenario.id());
            result.add(
                new LabCase(
                    scenario.id(),
                    scenario.description(),
                    client ? "client-server" : "server",
                    new LinkedHashSet<>(
                        Arrays.asList(
                            "isolated-world",
                            "preserve-fixtures",
                            scenario.includeInAll() ? "default" : "explicit")),
                    scenario.includeInAll(),
                    client));
        }
        for (String preset : Arrays.asList(
            "portable",
            "device",
            "settings",
            "quantum",
            "link",
            "panel",
            "panel-ae-chart",
            "panel-ae-list",
            "monitor",
            "cover-input",
            "cover-output"))
            result.add(
                new LabCase(
                    "ui." + preset,
                    "Real GUI layout/input via /gtswnui batch " + preset,
                    "client",
                    Collections.singleton("manual-server-assertion"),
                    false,
                    true));
        result.add(
            new LabCase(
                "jvm.effects_benchmark",
                "Explicit -Dgtswn.effectsBenchmark=<label>; creates a separate save, captures GL/frame data, then exits client",
                "jvm",
                Collections.singleton("explicit-property-entry"),
                false,
                true));
        return result;
    }

    static void installProbes() {
        BusinessDiagnostics.install(new BusinessDiagnostics.Listener() {

            @Override
            public void at(int dimension, int x, int y, int z, String type, String detail) {
                TestWorldProbes.at(dimension, x, y, z, TestWorldProbes.Type.valueOf(type), detail);
            }

            @Override
            public void eu(UUID owner, BigInteger amount, boolean committed) {
                TestWorldProbes.eu(owner, amount, committed);
            }

            @Override
            public void flow(Object controller, int channel, double amount) {
                TestWorldProbes.flow(controller, channel, amount);
            }

            @Override
            public void aeConnectionAttempt(IGridNode first, IGridNode second) {
                TestWorldProbes.aeConnectionAttempt(first, second);
            }

            @Override
            public void deviceKey(String key, String type) {
                TestWorldProbes.deviceKey(key, TestWorldProbes.Type.valueOf(type));
            }

            @Override
            public long queueToken(World world, int x, int y, int z) {
                return TestWorldProbes.queueToken(world, x, y, z);
            }

            @Override
            public void queue(long token, World world, int x, int y, int z, boolean executed) {
                TestWorldProbes.queue(token, world, x, y, z, executed);
            }
        });
    }
}
