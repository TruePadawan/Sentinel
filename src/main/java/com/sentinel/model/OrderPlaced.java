package com.sentinel.model;

/**
 * Represents an order placed on the market.
 */
public record OrderPlaced(
        String eventId,
        String orderId,
        String traderId,
        String symbol,
        String side,
        double quantity,
        double price,
        long timestamp
) implements MarketEvent {

    /**
     * Computes the notional value of this order.
     */
    public double notional() {
        return quantity * price;
    }
}
