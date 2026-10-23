package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.Test;

import com.miaokatze.gtswn.client.engineering.StableNodeClusters.Cluster;
import com.miaokatze.gtswn.client.engineering.StableNodeClusters.Projection;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot.Node;
import com.miaokatze.gtswn.common.engineering.NodePresentation;

public class StableNodeClustersTest {

    @Test
    public void permutationsAndRefreshesKeepCentersAndClickCycleOrder() {
        List<Projection> input = Arrays.asList(
            point(node(3, "b", 0, true), 31, 33),
            point(node(2, "a", 0, true), 11, 13),
            point(node(1, "a", 0, true), 15, 17));
        StableNodeClusters clusters = new StableNodeClusters();
        List<Cluster> expected = update(clusters, input);
        for (int seed = 0; seed < 20; seed++) {
            List<Projection> shuffled = new ArrayList<>(input);
            Collections.shuffle(shuffled, new Random(seed));
            assertClusters(expected, update(clusters, shuffled));
            assertClusters(expected, update(new StableNodeClusters(), shuffled));
        }
        assertEquals(13, expected.get(0).x);
        assertEquals(1, expected.get(0).nodes.get(0).x);
        assertEquals(2, expected.get(0).nodes.get(1).x);
    }

    @Test
    public void gridEdgesUseHysteresisWhileTrueMotionStillMovesMarkers() {
        Node a = node(1, "a", 0, true), b = node(2, "a", 0, true);
        StableNodeClusters clusters = new StableNodeClusters();
        assertEquals(1, update(clusters, Arrays.asList(point(a, 21, 12), point(b, 23, 12))).size());
        for (double x : new double[] { 25, 23, 26, 23, 25 }) {
            List<Cluster> result = update(clusters, Arrays.asList(point(a, 21, 12), point(b, x, 12)));
            assertEquals(1, result.size());
            assertEquals(Math.round((21 + x) / 2), result.get(0).x);
        }
        assertEquals(2, update(clusters, Arrays.asList(point(a, 21, 12), point(b, 29, 12))).size());
        assertEquals(2, update(clusters, Arrays.asList(point(a, 21, 12), point(b, 25, 12))).size());
        assertEquals(1, update(clusters, Arrays.asList(point(a, 21, 12), point(b, 19, 12))).size());
    }

    @Test
    public void membershipChangesUseCurrentCenterNeverAnArbitraryFirstNode() {
        Node a = node(1, "a", 0, true), b = node(2, "a", 0, true), c = node(3, "a", 0, true);
        StableNodeClusters clusters = new StableNodeClusters();
        assertEquals(12, update(clusters, Arrays.asList(point(a, 10, 12), point(b, 14, 12))).get(0).x);
        assertEquals(
            14,
            update(clusters, Arrays.asList(point(c, 18, 12), point(b, 14, 12), point(a, 10, 12))).get(0).x);
        assertEquals(16, update(clusters, Arrays.asList(point(c, 18, 12), point(b, 14, 12))).get(0).x);
        assertEquals(18, update(clusters, Collections.singletonList(point(c, 18, 12))).get(0).x);
        assertTrue(update(clusters, Collections.emptyList()).isEmpty());
    }

    @Test
    public void refreshReplacesLiveNodeReadingsAndDropsRemovedMembers() {
        StableNodeClusters clusters = new StableNodeClusters();
        Node online = node(1, "a", 0, true), offline = node(1, "a", 0, false);
        assertTrue(update(clusters, Collections.singletonList(point(online, 12, 12))).get(0).allOnline);
        Cluster refreshed = update(clusters, Collections.singletonList(point(offline, 12, 12))).get(0);
        assertFalse(refreshed.allOnline);
        assertSame(offline, refreshed.nodes.get(0));
        assertTrue(update(clusters, Collections.emptyList()).isEmpty());
    }

    @Test
    public void ownersDimensionsKindsAndModesNeverMerge() {
        List<Projection> points = Arrays.asList(
            point(node(1, "a", 0, true), 12, 12),
            point(node(2, "b", 0, true), 12, 12),
            point(node(3, "a", 1, true), 12, 12),
            point(new Node(1, 0, 4, 0, 0, "a", "a", "UP", true, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), 12, 12),
            point(new Node(1, 0, 5, 0, 0, "a", "a", "UP", true, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0), 12, 12));
        assertEquals(4, update(new StableNodeClusters(), points).size());
    }

    @Test
    public void viewportMembershipIsNotClippedBeforeAggregation() {
        List<Cluster> result = update(
            new StableNodeClusters(),
            Arrays.asList(point(node(1, "a", 0, true), 9, 12), point(node(2, "a", 0, true), 15, 12)));
        assertEquals(1, result.size());
        assertEquals(12, result.get(0).x);
        assertEquals(2, result.get(0).nodes.size());
    }

    @Test
    public void denseThousandsUseOneBucketAndIdentityIgnoresConnectionReason() {
        List<Projection> dense = new ArrayList<>();
        for (int i = 5000; i > 0; i--) dense.add(point(node(i, "a", 0, true), 12, 12));
        List<Cluster> result = update(new StableNodeClusters(), dense);
        assertEquals(1, result.size());
        assertEquals(5000, result.get(0).nodes.size());
        assertEquals(1, result.get(0).nodes.get(0).x);
        assertEquals(5000, result.get(0).nodes.get(4999).x);
        assertTrue(NodePresentation.sameIdentity(node(1, "a", 0, true), node(1, "a", 0, false)));
        assertFalse(NodePresentation.sameIdentity(node(1, "a", 0, true), node(1, "b", 0, true)));
    }

    @Test
    public void zoomFollowsCurrentProjectionAndSessionChangeResetsBoundaryHistory() {
        Node a = node(1, "a", 0, true), b = node(2, "a", 0, true);
        StableNodeClusters clusters = new StableNodeClusters();
        assertEquals(2, update(clusters, Arrays.asList(point(a, 21, 12), point(b, 25, 12))).size());
        assertEquals(2, update(clusters, Arrays.asList(point(a, 21, 12), point(b, 23, 12))).size());
        assertEquals(
            1,
            clusters.update(2, 0, 640, 400, Arrays.asList(point(a, 21, 12), point(b, 23, 12)))
                .size());
        assertEquals(200, update(clusters, Collections.singletonList(point(a, 200, 120))).get(0).x);
        assertEquals(100, update(clusters, Collections.singletonList(point(a, 100, 60))).get(0).x);
    }

    private static List<Cluster> update(StableNodeClusters clusters, List<Projection> points) {
        return clusters.update(1, 0, 640, 400, points);
    }

    private static Projection point(Node node, double x, double y) {
        return new Projection(node, x, y);
    }

    private static Node node(int x, String owner, int dim, boolean online) {
        return new Node(
            2,
            dim,
            x,
            0,
            0,
            owner,
            owner,
            online ? "NONE" : "OFFLINE",
            online,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0);
    }

    private static void assertClusters(List<Cluster> expected, List<Cluster> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            Cluster first = expected.get(i), second = actual.get(i);
            assertEquals(first.x, second.x);
            assertEquals(first.y, second.y);
            assertEquals(first.nodes.size(), second.nodes.size());
            for (int n = 0; n < first.nodes.size(); n++)
                assertTrue(NodePresentation.sameIdentity(first.nodes.get(n), second.nodes.get(n)));
        }
    }
}
