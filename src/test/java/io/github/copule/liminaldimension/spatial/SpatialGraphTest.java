package io.github.copule.liminaldimension.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.copule.liminaldimension.spatial.SpatialGraph.Edge;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpatialGraphTest {
    private static final Edge E0 = new Edge("e0", "n0", "n1");
    private static final Edge E1 = new Edge("e1", "n1", "n3");
    private static final Edge E2 = new Edge("e2", "n0", "n2");
    private static final Edge E3 = new Edge("e3", "n2", "n3");

    @Test
    void equalHopRoutesAreStableAcrossInputOrdering() {
        var first = new SpatialGraph(List.of("n0", "n1", "n2", "n3"), List.of(E2, E1, E3, E0));
        var second = new SpatialGraph(List.of("n3", "n2", "n1", "n0"), List.of(E0, E3, E1, E2));
        assertEquals(List.of(E0, E1), first.route("n0", "n3").orElseThrow());
        assertEquals(first.route("n0", "n3"), second.route("n0", "n3"));
    }

    @Test
    void findsShortestPathEvenIfLongerAlternativeIsVisitedFirst() {
        var direct = new Edge("e4", "n0", "n3");
        var graph = new SpatialGraph(List.of("n0", "n1", "n3"), List.of(E0, E1, direct));
        assertEquals(List.of(direct), graph.route("n0", "n3").orElseThrow());
    }

    @Test
    void equalHopParallelEdgesUseEdgeIdToBreakTie() {
        var other = new Edge("e4", "n0", "n1");
        var graph = new SpatialGraph(List.of("n0", "n1"), List.of(other, E0));
        assertEquals(List.of(E0), graph.route("n0", "n1").orElseThrow());
    }

    @Test
    void disconnectedSearchTerminatesInThePresenceOfCycles() {
        var graph = new SpatialGraph(List.of("n0", "n1", "n2"), List.of(E0,
                new Edge("cycle", "n1", "n0"), new Edge("self", "n1", "n1")));
        assertTrue(graph.route("n0", "n2").isEmpty());
    }

    @Test
    void edgesAreDirectedAndIdentityIsAnEmptySuccessfulRoute() {
        var graph = new SpatialGraph(List.of("n0", "n1"), List.of(E0));
        assertTrue(graph.route("n1", "n0").isEmpty());
        assertEquals(List.of(), graph.route("n0", "n0").orElseThrow());
    }

    @Test
    void rejectsInvalidIdentifiersAndDanglingOrDuplicateDefinitions() {
        assertThrows(IllegalArgumentException.class, () -> new Edge(" ", "n0", "n1"));
        assertThrows(IllegalArgumentException.class, () -> new Edge("e0", null, "n1"));
        assertThrows(IllegalArgumentException.class, () -> new SpatialGraph(List.of(""), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new SpatialGraph(List.of("n0", "n0"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new SpatialGraph(List.of("n0"), List.of(E0)));
        assertThrows(IllegalArgumentException.class, () -> new SpatialGraph(List.of("n1"), List.of(E0)));
        var duplicateId = new Edge(E0.id(), "n1", "n0");
        assertThrows(IllegalArgumentException.class,
                () -> new SpatialGraph(List.of("n0", "n1"), List.of(E0, duplicateId)));
    }

    @Test
    void distinguishesInvalidQueryFromDisconnectedNodes() {
        var graph = new SpatialGraph(List.of("n0"), List.of());
        assertThrows(IllegalArgumentException.class, () -> graph.route("n0", "unknown"));
        assertThrows(IllegalArgumentException.class, () -> graph.route("unknown", "n0"));
    }

    @Test
    void snapshotAndReturnedPathAreImmutable() {
        var nodes = new ArrayList<>(List.of("n0", "n1"));
        var edges = new ArrayList<>(List.of(E0));
        var graph = new SpatialGraph(nodes, edges);
        nodes.clear();
        edges.clear();
        var route = graph.route("n0", "n1").orElseThrow();
        assertEquals(List.of(E0), route);
        assertThrows(UnsupportedOperationException.class, route::clear);
    }
}
