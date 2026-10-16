package com.miaokatze.gtswn.common.testworld;

/** Server-only reports explicitly retain checks that require a real client and human visual assessment. */
final class ManualClientScenario implements TestScenario {

    @Override
    public String id() {
        return "manual.client_visuals";
    }

    @Override
    public String description() {
        return "MANUAL: real-client GUI, HUD, key gestures, packet roundtrips, particles and screen rendering";
    }

    @Override
    public void start(TestContext ctx) {
        ctx.event(
            "MANUAL",
            "Portable monitor HUD/pins/charge, tap/quantum reveal gestures and client packet roundtrip");
        ctx.event(
            "MANUAL",
            "Information/AE panel text/charts, extender seams, terminal GUI names and device list layout");
        ctx.event(
            "MANUAL",
            "Node cable arms, highlights, migration/incorporation particles and client interaction feedback");
        ctx.skip(
            "MANUAL: server assertions cannot verify real client input, GUI rendering or visual correctness; run client UI suite and inspect");
    }

    @Override
    public void tick(TestContext ctx) {}
}
