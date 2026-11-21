package io.github.copule.liminaldimension.spatial;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Engine-independent, directed connectivity proof of concept. No runtime consumers. */
public final class SpatialGraph {
    public record Edge(String id, String from, String to) {
        public Edge {
            requireId(id);
            requireId(from);
            requireId(to);
        }
    }

    private final Map<String, List<Edge>> outgoing;

    public SpatialGraph(Collection<String> nodes, Collection<Edge> edges) {
        Objects.requireNonNull(nodes, "nodes");
        Objects.requireNonNull(edges, "edges");
        Map<String, List<Edge>> indexed = new HashMap<>();
        for (String node : nodes) {
            requireId(node);
            if (indexed.putIfAbsent(node, new ArrayList<>()) != null) {
                throw new IllegalArgumentException("Duplicate node ID: " + node);
            }
        }
        Set<String> edgeIds = new HashSet<>();
        for (Edge edge : edges) {
            Objects.requireNonNull(edge, "edge");
            if (!edgeIds.add(edge.id())) {
                throw new IllegalArgumentException("Duplicate edge ID: " + edge.id());
            }
            List<Edge> from = indexed.get(edge.from());
            if (from == null || !indexed.containsKey(edge.to())) {
                throw new IllegalArgumentException("Unknown endpoint for edge: " + edge.id());
            }
            from.add(edge);
        }
        Comparator<Edge> visitOrder = Comparator.comparing(Edge::to).thenComparing(Edge::id);
        indexed.replaceAll((node, neighbors) -> {
            neighbors.sort(visitOrder);
            return List.copyOf(neighbors);
        });
        outgoing = Map.copyOf(indexed);
    }

    /**
     * Returns a shortest path by edge count between two registered nodes. Equal-length
     * alternatives are explored in destination-ID then edge-ID order. An empty path is
     * success for identical endpoints; an absent result means no path.
     */
    public Optional<List<Edge>> route(String from, String to) {
        requireNode(from);
        requireNode(to);
        ArrayDeque<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        Map<String, Edge> incoming = new HashMap<>();
        queue.addLast(from);
        visited.add(from);
        while (!queue.isEmpty()) {
            String node = queue.removeFirst();
            if (node.equals(to)) {
                ArrayList<Edge> path = new ArrayList<>();
                for (String current = to; !current.equals(from); ) {
                    Edge edge = incoming.get(current);
                    path.add(edge);
                    current = edge.from();
                }
                Collections.reverse(path);
                return Optional.of(List.copyOf(path));
            }
            for (Edge edge : outgoing.get(node)) {
                if (visited.add(edge.to())) {
                    incoming.put(edge.to(), edge);
                    queue.addLast(edge.to());
                }
            }
        }
        return Optional.empty();
    }

    private void requireNode(String node) {
        requireId(node);
        if (!outgoing.containsKey(node)) {
            throw new IllegalArgumentException("Unknown node ID: " + node);
        }
    }

    private static void requireId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Expected a nonblank ID");
        }
    }
}
