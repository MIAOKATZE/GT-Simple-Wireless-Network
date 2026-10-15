package com.miaokatze.gtswn.common.testworld;

import net.minecraft.world.WorldServer;

/**
 * One scenario invocation. PASS/SKIP may be replaced by FAIL through callback completion and cleanup.
 * The first failure is terminal and retains its reason; cleanup also runs on timeout and stop.
 */
public final class TestContext {

    private final WorldServer world;
    private final String runId;
    private final TestWorldReport report;
    final TestWorldReport.Result result;
    long elapsed;
    String outcome;
    String detail;

    TestContext(WorldServer world, String runId, TestWorldReport report, TestWorldReport.Result result) {
        this.world = world;
        this.runId = runId;
        this.report = report;
        this.result = result;
    }

    public WorldServer world() {
        return world;
    }

    public long tick() {
        return elapsed;
    }

    public String runId() {
        return runId;
    }

    public void event(String type, String message) {
        report.event(result, elapsed, type, message);
    }

    public void pass(String message) {
        finish("PASS", message);
    }

    public void fail(String message) {
        finish("FAIL", message);
    }

    public void skip(String message) {
        finish("SKIP", message);
    }

    public void check(boolean condition, String message) {
        event("assertion", (condition ? "PASS: " : "FAIL: ") + message);
        if (!condition) fail(message);
    }

    /** Runner-only exception handling: retain later errors in the trace without replacing the first failure. */
    void callbackFailure(String callback, Throwable failure) {
        String message = callback + " failed: "
            + failure.getClass()
                .getName()
            + ": "
            + failure.getMessage();
        event("callback_failure", message);
        fail(message);
    }

    private void finish(String status, String message) {
        if (outcome == null || ("FAIL".equals(status) && !"FAIL".equals(outcome))) {
            outcome = status;
            detail = message;
            event("outcome", status + ": " + message);
        }
    }
}
