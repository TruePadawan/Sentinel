package com.sentinel.model;

/**
 * Represents a trade execution between a buyer and seller.
 */
public record TradeExecuted(
        String eventId,
        String buyerId,
        String sellerId,
        String symbol,
        double quantity,
        double price,
        long timestamp
) implements MarketEvent {

    @Override
    public String traderId() {
        return buyerId;
    }

    /**
     * Computes the notional value of this trade.
     */
    public double notional() {
        return quantity * price;
    }
}
