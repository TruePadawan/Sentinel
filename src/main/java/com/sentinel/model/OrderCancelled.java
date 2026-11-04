package com.sentinel.model;

/**
 * Represents an order cancellation event.
 */
public record OrderCancelled(
        String eventId,
        String originalOrderId,
        String traderId,
        String symbol,
        double quantity,
        double price,
        long timestamp
) implements MarketEvent {

    /**
     * Computes the notional value of the cancelled order.
     */
    public double notional() {
        return quantity * price;
    }
}
