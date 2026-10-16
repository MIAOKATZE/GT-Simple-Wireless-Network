package com.miaokatze.gtswn.common.testworld;

/** A bounded state machine driven by real server END ticks. Never block or sleep in these callbacks. */
public interface TestScenario {

    String id();

    default String description() {
        return id();
    }

    default int timeoutTicks() {
        return 1200;
    }

    /** Heavy cases must opt out and can then run only by their explicit stable ID. */
    default boolean includeInAll() {
        return true;
    }

    void start(TestContext context) throws Exception;

    void tick(TestContext context) throws Exception;

    default void cleanup(TestContext context) throws Exception {}
}
