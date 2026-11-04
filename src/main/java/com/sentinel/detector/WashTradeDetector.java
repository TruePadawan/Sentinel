package com.sentinel.detector;

import com.sentinel.config.DetectorConfig;
import com.sentinel.model.AlertType;
import com.sentinel.model.ComplianceAlert;
import com.sentinel.model.MarketEvent;
import com.sentinel.model.OrderCancelled;
import com.sentinel.model.OrderPlaced;
import com.sentinel.model.TradeEdge;
import com.sentinel.model.TradeExecuted;
import com.sentinel.util.JsonSerializer;
import com.sentinel.util.SlidingWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Detects wash trading rings: circular trading patterns where connected accounts
 * trade an asset in a loop within a tight time window.
 * <p>
 * Maintains a time-windowed directed graph and executes bounded DFS on new trade
 * executions to detect cycles of length k ≤ maxCycleLength.
 * <p>
 * Complexity: O(d²) amortized per trade where d = average degree in the time window.
 * Space: O(E) bounded by time window.
 */
public class WashTradeDetector implements Detector {

    private static final Logger logger = LoggerFactory.getLogger(WashTradeDetector.class);

    private final long windowMs;
    private final int maxCycleLength;

    /**
     * Per-symbol graph state. Keyed by symbol since partitions are by symbol,
     * so each partition processes one symbol single-threaded.
     */
    private final Map<String, SymbolGraph> symbolGraphs = new HashMap<>();

    /**
     * Directed graph for a single symbol, with time-windowed edge eviction.
     */
    private static class SymbolGraph {
        /**
         * Adjacency list: from-trader -> list of edges
         */
        private final Map<String, List<TradeEdge>> adjacencyList = new HashMap<>();
        /**
         * Sliding window for edge eviction
         */
        private final SlidingWindow<TradeEdge> edgeWindow;

        public SymbolGraph(long windowMs) {
            this.edgeWindow = new SlidingWindow<>(windowMs, TradeEdge::timestamp);
        }

        public void addEdge(TradeEdge edge) {
            adjacencyList
                    .computeIfAbsent(edge.from(), k -> new ArrayList<>())
                    .add(edge);
            edgeWindow.add(edge);
        }

        public void evict(long nowMs) {
            edgeWindow.evict(nowMs);
            // Rebuild adjacency list from remaining edges
            adjacencyList.clear();
            for (TradeEdge edge : edgeWindow.getDeque()) {
                adjacencyList
                        .computeIfAbsent(edge.from(), k -> new ArrayList<>())
                        .add(edge);
            }
        }
    }

    public WashTradeDetector(DetectorConfig config) {
        this.windowMs = config.washWindowMs();
        this.maxCycleLength = config.washMaxCycleLength();
    }

    @Override
    public List<ComplianceAlert> process(MarketEvent event) {
        List<ComplianceAlert> alerts = new ArrayList<>();

        switch (event) {
            case TradeExecuted executed -> processTradeExecuted(executed, alerts);
            case OrderPlaced placed -> {
                // Order events don't affect wash trading graph
            }
            case OrderCancelled cancelled -> {
                // Order events don't affect wash trading graph
            }
        }

        return alerts;
    }

    private void processTradeExecuted(TradeExecuted event, List<ComplianceAlert> alerts) {
        SymbolGraph graph = symbolGraphs.computeIfAbsent(
                event.symbol(),
                s -> new SymbolGraph(windowMs)
        );

        // Evict expired edges before processing
        graph.evict(event.timestamp());

        // Self-trade: buyer and seller are the same - immediate wash trade
        if (event.buyerId().equals(event.sellerId())) {
            ComplianceAlert alert = new ComplianceAlert(
                    JsonSerializer.generateAlertId(),
                    AlertType.WASH_TRADE,
                    event.buyerId(),
                    event.symbol(),
                    String.format(
                            "Self-trade detected: %s traded with itself (notional: $%.2f)",
                            event.buyerId(), event.quantity() * event.price()
                    ),
                    event.timestamp(),
                    "HIGH"
            );
            alerts.add(alert);
            logger.info("Wash trade alert: self-trade by {} on {} (notional: ${})",
                    event.buyerId(), event.symbol(),
                    String.format("%.2f", event.quantity() * event.price()));
            return;
        }

        // Create directed edge: buyer -> seller
        // Only forward edges so a cycle requires at least 3 distinct trades
        TradeEdge forward = new TradeEdge(
                event.buyerId(), event.sellerId(), event.symbol(),
                event.timestamp(), event.price(), event.quantity()
        );

        graph.addEdge(forward);

        // Detect cycles starting from buyer
        List<String> cycle = detectCycle(graph, event.buyerId(), event.symbol(), event.timestamp());
        if (cycle != null && !cycle.isEmpty()) {
            double totalNotional = computeCycleNotional(cycle, graph);
            ComplianceAlert alert = new ComplianceAlert(
                    JsonSerializer.generateAlertId(),
                    AlertType.WASH_TRADE,
                    event.buyerId(),
                    event.symbol(),
                    String.format(
                            "Wash trade ring detected: %s (cycle length: %d, notional: $%.2f)",
                            String.join(" -> ", cycle), cycle.size(), totalNotional
                    ),
                    event.timestamp(),
                    "HIGH"
            );
            alerts.add(alert);
            logger.info("Wash trade alert: ring {} on {} (participants: {}, notional: ${})",
                    String.join(" -> ", cycle), event.symbol(), cycle.size(),
                    String.format("%.2f", totalNotional));
        }
    }

    /**
     * Bounded DFS to detect cycles starting from the given node.
     * Returns the cycle path if found, null otherwise.
     * Only traverses edges within the time window.
     */
    private List<String> detectCycle(SymbolGraph graph, String startNode, String symbol, long startTime) {
        // DFS stack: (currentNode, path-so-far)
        Deque<DfsState> stack = new ArrayDeque<>();
        stack.push(new DfsState(startNode, List.of(startNode), new HashSet<>(Set.of(startNode))));

        while (!stack.isEmpty()) {
            DfsState state = stack.pop();

            if (state.path.size() > maxCycleLength) continue;

            List<TradeEdge> neighbors = graph.adjacencyList.getOrDefault(state.currentNode, List.of());
            for (TradeEdge edge : neighbors) {
                String nextNode = edge.to();

                // Found a cycle back to the start node
                if (nextNode.equals(startNode) && state.path.size() >= 2) {
                    List<String> cycle = new ArrayList<>(state.path);
                    cycle.add(startNode); // Close the cycle
                    return cycle;
                }

                // Continue DFS if not yet visited in this path
                if (!state.visited.contains(nextNode)) {
                    List<String> newPath = new ArrayList<>(state.path);
                    newPath.add(nextNode);
                    Set<String> newVisited = new HashSet<>(state.visited);
                    newVisited.add(nextNode);
                    stack.push(new DfsState(nextNode, newPath, newVisited));
                }
            }
        }

        return null;
    }

    private record DfsState(String currentNode, List<String> path, Set<String> visited) {
    }

    private double computeCycleNotional(List<String> cycle, SymbolGraph graph) {
        double total = 0;
        for (int i = 0; i < cycle.size() - 1; i++) {
            String from = cycle.get(i);
            String to = cycle.get(i + 1);
            List<TradeEdge> edges = graph.adjacencyList.getOrDefault(from, List.of());
            for (TradeEdge edge : edges) {
                if (edge.to().equals(to)) {
                    total += edge.notional();
                    break;
                }
            }
        }
        return total;
    }

    /**
     * Resets all state. Useful for testing.
     */
    public void reset() {
        symbolGraphs.clear();
    }
}
