package com.miaokatze.gtswn.client.engineering;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot.Node;
import com.miaokatze.gtswn.common.engineering.NodePresentation;
import com.miaokatze.gtswn.common.engineering.NodePresentation.Identity;

/** Bounded screen-grid aggregation with stable member order and boundary hysteresis. */
final class StableNodeClusters {

    private static final int CELL = 24, MARGIN = 4;
    private Map<Identity, Bucket> previous = new HashMap<>();
    private long session = Long.MIN_VALUE;
    private int width, height, dimension;

    List<Cluster> update(long sessionId, int dim, int w, int h, List<Projection> projected) {
        if (session != sessionId || dimension != dim || width != w || height != h) previous.clear();
        session = sessionId;
        dimension = dim;
        width = w;
        height = h;
        // Sorting before accumulation also makes floating-point centers invariant under input permutations.
        List<Projection> ordered = new ArrayList<>(projected);
        ordered.sort((first, second) -> NodePresentation.ORDER.compare(first.node, second.node));
        Map<Identity, Bucket> next = new HashMap<>();
        Map<Bucket, Accumulator> buckets = new LinkedHashMap<>();
        for (Projection point : ordered) {
            Node node = point.node;
            if (node.dim != dim || !Double.isFinite(point.x) || !Double.isFinite(point.y)) continue;
            Identity identity = NodePresentation.identity(node);
            Bucket bucket = previous.get(identity);
            if (bucket == null || !bucket.accepts(point)) bucket = new Bucket(point);
            next.put(identity, bucket);
            buckets.computeIfAbsent(bucket, ignored -> new Accumulator())
                .add(point);
        }
        // Membership is replaced on every frame: removed nodes and old live readings cannot survive a refresh.
        previous = next;
        List<Cluster> result = new ArrayList<>(buckets.size());
        for (Accumulator accumulator : buckets.values()) result.add(accumulator.finish());
        return result;
    }

    static final class Projection {

        final Node node;
        final double x, y;

        Projection(Node node, double x, double y) {
            this.node = node;
            this.x = x;
            this.y = y;
        }
    }

    static final class Cluster {

        final int x, y;
        final List<Node> nodes;
        final boolean allOnline;

        Cluster(int x, int y, List<Node> nodes, boolean allOnline) {
            this.x = x;
            this.y = y;
            this.nodes = Collections.unmodifiableList(nodes);
            this.allOnline = allOnline;
        }
    }

    private static final class Accumulator {

        private double x, y;
        private boolean allOnline = true;
        private final List<Node> nodes = new ArrayList<>();

        void add(Projection point) {
            x += point.x;
            y += point.y;
            nodes.add(point.node);
            allOnline &= point.node.online;
        }

        Cluster finish() {
            return new Cluster(
                (int) Math.round(x / nodes.size()),
                (int) Math.round(y / nodes.size()),
                nodes,
                allOnline);
        }
    }

    private static final class Bucket {

        private final int x, y, dim, kind, mode;
        private final String owner;

        Bucket(Projection point) {
            x = (int) Math.floor(point.x / CELL);
            y = (int) Math.floor(point.y / CELL);
            dim = point.node.dim;
            kind = point.node.kind;
            mode = point.node.mode;
            owner = point.node.owner;
        }

        boolean accepts(Projection point) {
            return dim == point.node.dim && kind == point.node.kind
                && mode == point.node.mode
                && owner.equals(point.node.owner)
                && point.x >= (double) x * CELL - MARGIN
                && point.x < ((double) x + 1) * CELL + MARGIN
                && point.y >= (double) y * CELL - MARGIN
                && point.y < ((double) y + 1) * CELL + MARGIN;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Bucket bucket)) return false;
            return x == bucket.x && y == bucket.y
                && dim == bucket.dim
                && kind == bucket.kind
                && mode == bucket.mode
                && owner.equals(bucket.owner);
        }

        @Override
        public int hashCode() {
            return Objects.hash(x, y, dim, kind, mode, owner);
        }
    }
}
