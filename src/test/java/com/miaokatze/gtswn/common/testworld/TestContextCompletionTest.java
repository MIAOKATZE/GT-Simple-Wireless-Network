package com.miaokatze.gtswn.common.testworld;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TestContextCompletionTest {

    private TestContext context() {
        TestWorldReport report = new TestWorldReport("completion", 19871, "one");
        return new TestContext(null, report.runId, report, new TestWorldReport.Result("one"));
    }

    @Test
    public void startExceptionOverridesAnEarlierPass() {
        TestContext context = context();
        context.pass("assertions passed before returning");
        context.callbackFailure("start", new IllegalStateException("late start failure"));
        assertEquals("FAIL", context.outcome);
        assertEquals("start failed: java.lang.IllegalStateException: late start failure", context.detail);
    }

    @Test
    public void tickNativeFailureOverridesAnEarlierSkip() {
        TestContext context = context();
        context.skip("optional integration absent");
        context.callbackFailure("tick", new UnsatisfiedLinkError("native callback failed"));
        assertEquals("FAIL", context.outcome);
        assertEquals("tick failed: java.lang.UnsatisfiedLinkError: native callback failed", context.detail);
    }

    @Test
    public void cleanupExceptionOverridesAnEarlierPass() {
        TestContext context = context();
        context.pass("tick completed");
        context.callbackFailure("cleanup", new IllegalArgumentException("finally failed"));
        assertEquals("FAIL", context.outcome);
        assertEquals("cleanup failed: java.lang.IllegalArgumentException: finally failed", context.detail);
    }

    @Test
    public void firstFailureSurvivesLaterSuccessSkipAndCallbackErrors() {
        TestContext context = context();
        context.fail("first broken invariant");
        context.pass("late success");
        context.skip("late skip");
        context.callbackFailure("tick", new IllegalStateException("secondary failure"));
        context.callbackFailure("cleanup", new IllegalStateException("cleanup failure"));
        context.fail("later explicit failure");
        assertEquals("FAIL", context.outcome);
        assertEquals("first broken invariant", context.detail);
        assertTrue(
            context.result.events.stream()
                .anyMatch(event -> event.detail.contains("secondary failure")));
        assertTrue(
            context.result.events.stream()
                .anyMatch(event -> event.detail.contains("cleanup failure")));
    }

    @Test
    public void failedAssertionOverridesAnEarlierPassAndCannotBeReversed() {
        TestContext context = context();
        context.pass("premature success");
        context.check(false, "assertion after success failed");
        context.pass("unconditional success at callback end");
        assertEquals("FAIL", context.outcome);
        assertEquals("assertion after success failed", context.detail);
    }

    @Test
    public void wrappedNativeCauseAndBoundedFramesReachTheReportWithoutReplacingFirstFailure() {
        TestContext context = context();
        context.fail("first failure");
        IllegalArgumentException nativeFailure = new IllegalArgumentException("exact native rejection");
        StackTraceElement[] frames = new StackTraceElement[20];
        for (int i = 0; i < frames.length; i++)
            frames[i] = new StackTraceElement("NativeHandler", "step" + i, "NativeHandler.java", i + 1);
        nativeFailure.setStackTrace(frames);
        context.callbackFailure(
            "tick",
            new IllegalStateException(
                "handler wrapper",
                new java.lang.reflect.InvocationTargetException(nativeFailure)));
        assertEquals("FAIL", context.outcome);
        assertEquals("first failure", context.detail);
        assertTrue(
            context.result.events.stream()
                .anyMatch(
                    event -> event.type.equals("callback_cause")
                        && event.detail.equals("cause[2]=java.lang.IllegalArgumentException: exact native rejection")));
        assertEquals(
            12L,
            context.result.events.stream()
                .filter(event -> event.type.equals("callback_stack") && event.detail.startsWith("cause[2] "))
                .count());
        assertTrue(
            context.result.events.stream()
                .anyMatch(event -> event.detail.contains("NativeHandler.step11")));
        assertTrue(
            context.result.events.stream()
                .noneMatch(event -> event.detail.contains("NativeHandler.step12")));
    }
}
