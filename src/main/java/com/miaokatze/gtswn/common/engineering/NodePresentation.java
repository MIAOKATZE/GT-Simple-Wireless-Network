package com.miaokatze.gtswn.common.engineering;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot.Node;

/** Presentation identity excludes live readings, labels and connection state. */
public final class NodePresentation {

    public static final Comparator<Node> ORDER = Comparator.comparing((Node node) -> node.owner)
        .thenComparingInt(node -> node.dim)
        .thenComparingInt(node -> node.x)
        .thenComparingInt(node -> node.y)
        .thenComparingInt(node -> node.z)
        .thenComparingInt(node -> node.kind)
        .thenComparing(node -> node.kind == 1 ? node.name : "");

    private NodePresentation() {}

    public static List<Node> sorted(List<Node> nodes) {
        List<Node> result = new ArrayList<>(nodes);
        result.sort(ORDER);
        return result;
    }

    public static boolean sameIdentity(Node first, Node second) {
        return first != null && second != null && ORDER.compare(first, second) == 0;
    }

    public static Identity identity(Node node) {
        return new Identity(node);
    }

    /** Keeps coordinates and ownership only, never a stale Node or its online status. */
    public static final class Identity {

        private final int kind, dim, x, y, z;
        private final String owner, face;

        private Identity(Node node) {
            kind = node.kind;
            dim = node.dim;
            x = node.x;
            y = node.y;
            z = node.z;
            owner = node.owner;
            face = kind == 1 ? node.name : "";
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Identity identity)) return false;
            return kind == identity.kind && dim == identity.dim
                && x == identity.x
                && y == identity.y
                && z == identity.z
                && owner.equals(identity.owner)
                && face.equals(identity.face);
        }

        @Override
        public int hashCode() {
            return Objects.hash(kind, dim, x, y, z, owner, face);
        }
    }
}
