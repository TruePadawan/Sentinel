package com.sentinel.model;

/**
 * Directed edge in the wash trading detection graph.
 * Represents a trade relationship between two traders.
 */
public record TradeEdge(
    String from,
    String to,
    String symbol,
    long timestamp,
    double price,
    double quantity
) {
    /**
     * Computes the notional value of this trade edge.
     */
    public double notional() {
        return quantity * price;
    }
}
