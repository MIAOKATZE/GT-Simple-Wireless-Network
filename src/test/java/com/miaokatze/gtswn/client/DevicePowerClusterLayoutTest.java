package com.miaokatze.gtswn.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.miaokatze.gtswn.client.DevicePowerClusterLayout.Bounds;
import com.miaokatze.gtswn.client.DevicePowerClusterLayout.Group;
import com.miaokatze.gtswn.client.DevicePowerClusterLayout.Point;
import com.miaokatze.gtswn.client.DevicePowerClusterLayout.Reading;
import com.miaokatze.gtswn.client.DevicePowerClusterLayout.Result;

public class DevicePowerClusterLayoutTest {

    private static final Bounds VIEW = new Bounds(40, 40, 1000, 600);
    private static final DevicePowerClusterLayout.Measure MEASURE = (group, width) -> {
        group.width = Math.min(width, group.points.size() == 1 && !group.remainder ? 80 : 180);
        group.height = group.points.size() == 1 && !group.remainder ? 9 : 29;
        group.lines = new String[] { group.points.size() == 1 ? group.points.get(0).detail : "summary" };
    };

    @Test
    public void distantMachinesKeepTheirIndividualReadingsAndZoomedNeighboursBecomeASummary() {
        DevicePowerClusterLayout layout = new DevicePowerClusterLayout();
        Result near = layout.layout(Arrays.asList(point("a", 180, 180), point("b", 500, 180)), VIEW, 64, MEASURE);
        assertEquals(2, near.groups.size());
        assertEquals("a EU/t", near.groups.get(0).lines[0]);
        Result far = layout.layout(Arrays.asList(point("a", 280, 180), point("b", 315, 180)), VIEW, 64, MEASURE);
        assertEquals(1, far.groups.size());
        assertEquals(2, far.groups.get(0).points.size());
        assertEquals(BigInteger.valueOf(64), far.groups.get(0).input);
        assertEquals(BigInteger.valueOf(16), far.groups.get(0).output);
        assertEquals(
            297.5,
            far.groups.get(0)
                .centerX(),
            0D);
    }

    @Test
    public void inputAndOutputStayExactBeyondLongAndDoublePrecision() {
        List<Point> points = Arrays.asList(
            point("a", 200, 200, Long.MAX_VALUE, Long.MAX_VALUE - 2, Reading.CONFIRMED),
            point("b", 205, 200, Long.MAX_VALUE, 7, Reading.CONFIRMED));
        Group group = new DevicePowerClusterLayout().layout(points, VIEW, 64, MEASURE).groups.get(0);
        assertEquals(new BigInteger("18446744073709551614"), group.input);
        assertEquals(new BigInteger("9223372036854775812"), group.output);
    }

    @Test
    public void decimalMetricFormattingDoesNotRoundLargeIntegersThroughDouble() {
        assertEquals("9.9E", DevicePowerClusterLayout.formatTotal(new BigInteger("9949999999999999999")));
        assertEquals("10.0E", DevicePowerClusterLayout.formatTotal(new BigInteger("9950000000000000000")));
        assertEquals("0", DevicePowerClusterLayout.formatTotal(BigInteger.ZERO));
        assertEquals("32", DevicePowerClusterLayout.formatTotal(BigInteger.valueOf(32)));
    }

    @Test
    public void unknownUnavailableAndIgnoredAreCountedWithoutContributingFakeZeroOrOldReadings() {
        List<Point> points = Arrays.asList(
            point("known", 200, 200, 32, 64, Reading.CONFIRMED),
            point("unknown", 200, 200, 999, 999, Reading.UNKNOWN),
            point("missing", 200, 200, 999, 999, Reading.UNAVAILABLE),
            point("ignored", 200, 200, 999, 999, Reading.IGNORED));
        Group group = new DevicePowerClusterLayout().layout(points, VIEW, 64, MEASURE).groups.get(0);
        assertEquals(4, group.points.size());
        assertEquals(1, group.confirmed);
        assertEquals(1, group.unknown);
        assertEquals(1, group.unavailable);
        assertEquals(1, group.ignored);
        assertEquals(BigInteger.valueOf(32), group.input);
        assertEquals(BigInteger.valueOf(64), group.output);
    }

    @Test
    public void deduplicationAndViewportClippingDoNotInflateTheScreenTotal() {
        Result result = new DevicePowerClusterLayout().layout(
            Arrays.asList(point("same", 200, 200), point("same", 205, 205), point("outside", 1, 1)),
            VIEW,
            64,
            MEASURE);
        assertEquals(1, result.included);
        assertEquals(1, result.duplicates);
        assertEquals(1, result.outside);
        assertEquals(BigInteger.valueOf(32), result.groups.get(0).input);
    }

    @Test
    public void tinyZoomChangesDoNotSplitAnExistingClusterButZoomingInEventuallyDoes() {
        DevicePowerClusterLayout layout = new DevicePowerClusterLayout();
        assertEquals(
            1,
            layout.layout(Arrays.asList(point("a", 200, 200), point("b", 280, 200)), VIEW, 64, MEASURE).groups.size());
        assertEquals(
            1,
            layout.layout(Arrays.asList(point("a", 200, 200), point("b", 288, 200)), VIEW, 64, MEASURE).groups.size());
        assertEquals(
            2,
            layout.layout(Arrays.asList(point("a", 200, 200), point("b", 400, 200)), VIEW, 64, MEASURE).groups.size());
    }

    @Test
    public void inputOrderDoesNotChangeStableClusterMembershipOrPlacement() {
        List<Point> points = Arrays.asList(point("a", 150, 200), point("b", 180, 200), point("c", 600, 350));
        Result first = new DevicePowerClusterLayout().layout(points, VIEW, 64, MEASURE);
        Collections.reverse(points);
        Result reversed = new DevicePowerClusterLayout().layout(points, VIEW, 64, MEASURE);
        assertEquals(first.groups.size(), reversed.groups.size());
        for (int i = 0; i < first.groups.size(); i++) {
            assertEquals(first.groups.get(i).points.get(0).key, reversed.groups.get(i).points.get(0).key);
            assertEquals(first.groups.get(i).left, reversed.groups.get(i).left);
            assertEquals(first.groups.get(i).top, reversed.groups.get(i).top);
        }
    }

    @Test
    public void budgetRemainderPreservesEveryVisibleMachineAndItsSeparateTotals() {
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < 20; i++) points.add(point("p" + i, 110 + i % 5 * 180, 100 + i / 5 * 100));
        Result result = new DevicePowerClusterLayout().layout(points, VIEW, 4, MEASURE);
        assertEquals(4, result.groups.size());
        assertTrue(result.groups.get(3).remainder);
        assertEquals(20, count(result));
        BigInteger input = BigInteger.ZERO, output = BigInteger.ZERO;
        for (Group group : result.groups) {
            input = input.add(group.input);
            output = output.add(group.output);
        }
        assertEquals(BigInteger.valueOf(640), input);
        assertEquals(BigInteger.valueOf(160), output);
        assertFits(result, VIEW);
    }

    @Test
    public void denseMaximumInputUsesBoundedGridWorkAndOneSummary() {
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < 600; i++) points.add(point("p" + i, 250 + i % 3, 250 + i % 5));
        Result result = new DevicePowerClusterLayout().layout(points, VIEW, 64, MEASURE);
        assertEquals(DevicePowerClusterLayout.MAX_POINTS, result.included);
        assertEquals(88, result.overflow);
        assertEquals(1, result.groups.size());
        assertEquals(512, count(result));
        assertTrue(result.gridVisits < 100000);
        assertFits(result, VIEW);
    }

    @Test
    public void edgeLabelsAreMovedInsideTheViewportAndNeverOverlap() {
        List<Point> points = Arrays.asList(
            point("left", 41, 42),
            point("right", 999, 43),
            point("bottom", 990, 599),
            point("other", 995, 580));
        Result result = new DevicePowerClusterLayout().layout(points, VIEW, 64, MEASURE);
        assertEquals(4, count(result));
        assertFits(result, VIEW);
    }

    @Test
    public void anEmptyOrNonFiniteViewportInputDoesNotProduceLabels() {
        assertTrue(new DevicePowerClusterLayout().layout(Collections.emptyList(), VIEW, 64, MEASURE).groups.isEmpty());
        Result result = new DevicePowerClusterLayout().layout(
            Arrays.asList(point("nan", Double.NaN, 200), point("inf", 200, Double.POSITIVE_INFINITY)),
            VIEW,
            64,
            MEASURE);
        assertTrue(result.groups.isEmpty());
        assertEquals(2, result.outside);
    }

    private static Point point(String key, double x, double y) {
        return point(key, x, y, 32, 8, Reading.CONFIRMED);
    }

    private static Point point(String key, double x, double y, long input, long output, Reading reading) {
        return new Point(key, x, y, input, output, reading, key + " EU/t", 80, 9);
    }

    private static int count(Result result) {
        int count = 0;
        for (Group group : result.groups) count += group.points.size();
        return count;
    }

    private static void assertFits(Result result, Bounds bounds) {
        for (int i = 0; i < result.groups.size(); i++) {
            Group group = result.groups.get(i);
            assertTrue(group.left >= bounds.left && group.top >= bounds.top);
            assertTrue(group.left + group.width <= bounds.right && group.top + group.height <= bounds.bottom);
            for (int j = 0; j < i; j++) {
                Group other = result.groups.get(j);
                assertFalse(
                    group.left < other.left + other.width && group.left + group.width > other.left
                        && group.top < other.top + other.height
                        && group.top + group.height > other.top);
            }
        }
    }
}
