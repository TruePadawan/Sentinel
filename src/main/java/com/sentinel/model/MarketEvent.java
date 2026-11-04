package com.sentinel.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Sealed interface for market event polymorphism.
 * All market events implement this interface, enabling pattern matching and type-safe dispatch.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    property = "type"
)
@JsonSubTypes({
    @JsonSubTypes.Type(value = OrderPlaced.class, name = "ORDER_PLACED"),
    @JsonSubTypes.Type(value = OrderCancelled.class, name = "ORDER_CANCELLED"),
    @JsonSubTypes.Type(value = TradeExecuted.class, name = "TRADE_EXECUTED")
})
public sealed interface MarketEvent
    permits OrderPlaced, OrderCancelled, TradeExecuted {

    /** Unique event identifier */
    String eventId();

    /** Primary actor trader ID; for TradeExecuted this returns buyerId */
    String traderId();

    /** Trading symbol (e.g., "AAPL") */
    String symbol();

    /** Event timestamp in milliseconds since epoch */
    long timestamp();
}
