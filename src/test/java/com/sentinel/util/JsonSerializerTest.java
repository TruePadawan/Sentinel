package com.sentinel.util;

import com.sentinel.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for JSON serialization round-trips of all domain models.
 * Verifies polymorphic deserialization of MarketEvent subtypes via the "type" discriminator.
 */
class JsonSerializerTest {

    @Nested
    @DisplayName("OrderPlaced serialization round-trip")
    class OrderPlacedTest {

        private static final OrderPlaced originalEvent = new OrderPlaced(
            "evt-001", "ord-001", "TRADER_A", "AAPL", "BUY", 10000, 175.50, 1730784000000L
        );

        @Test
        @DisplayName("Serializes OrderPlaced to JSON with type discriminator")
        void serializesWithTypeInfo() {
            String json = JsonSerializer.serializeEvent(originalEvent);
            assertTrue(json.contains("\"type\":\"ORDER_PLACED\""), "JSON must contain type discriminator");
            assertTrue(json.contains("\"eventId\":\"evt-001\""));
            assertTrue(json.contains("\"orderId\":\"ord-001\""));
            assertTrue(json.contains("\"traderId\":\"TRADER_A\""));
            assertTrue(json.contains("\"symbol\":\"AAPL\""));
            assertTrue(json.contains("\"side\":\"BUY\""));
            assertTrue(json.contains("\"quantity\":10000.0"));
            assertTrue(json.contains("\"price\":175.5"));
            assertTrue(json.contains("\"timestamp\":1730784000000"));
        }

        @Test
        @DisplayName("Deserializes OrderPlaced JSON back to correct type")
        void deserializesToCorrectType() {
            String json = JsonSerializer.serializeEvent(originalEvent);
            MarketEvent event = JsonSerializer.deserializeEvent(json);

            assertInstanceOf(OrderPlaced.class, event, "Deserialized event must be OrderPlaced");
            OrderPlaced placed = (OrderPlaced) event;
            assertEquals("evt-001", placed.eventId());
            assertEquals("ord-001", placed.orderId());
            assertEquals("TRADER_A", placed.traderId());
            assertEquals("AAPL", placed.symbol());
            assertEquals("BUY", placed.side());
            assertEquals(10000.0, placed.quantity());
            assertEquals(175.50, placed.price());
            assertEquals(1730784000000L, placed.timestamp());
        }

        @Test
        @DisplayName("Full round-trip preserves all fields")
        void roundTripPreservesAllFields() {
            String json = JsonSerializer.serializeEvent(originalEvent);
            MarketEvent event = JsonSerializer.deserializeEvent(json);
            OrderPlaced roundTripped = (OrderPlaced) event;
            assertEquals(originalEvent, roundTripped);
        }

        @Test
        @DisplayName("Notional value is computed correctly")
        void notionalValue() {
            assertEquals(1_755_000.0, originalEvent.notional());
        }
    }

    @Nested
    @DisplayName("OrderCancelled serialization round-trip")
    class OrderCancelledTest {

        private static final OrderCancelled originalEvent = new OrderCancelled(
            "evt-002", "ord-001", "TRADER_A", "AAPL", 10000, 175.50, 1730784000045L
        );

        @Test
        @DisplayName("Serializes OrderCancelled to JSON with type discriminator")
        void serializesWithTypeInfo() {
            String json = JsonSerializer.serializeEvent(originalEvent);
            assertTrue(json.contains("\"type\":\"ORDER_CANCELLED\""), "JSON must contain type discriminator");
            assertTrue(json.contains("\"eventId\":\"evt-002\""));
            assertTrue(json.contains("\"originalOrderId\":\"ord-001\""));
        }

        @Test
        @DisplayName("Deserializes OrderCancelled JSON back to correct type")
        void deserializesToCorrectType() {
            String json = JsonSerializer.serializeEvent(originalEvent);
            MarketEvent event = JsonSerializer.deserializeEvent(json);

            assertInstanceOf(OrderCancelled.class, event, "Deserialized event must be OrderCancelled");
            OrderCancelled cancelled = (OrderCancelled) event;
            assertEquals("evt-002", cancelled.eventId());
            assertEquals("ord-001", cancelled.originalOrderId());
            assertEquals("TRADER_A", cancelled.traderId());
            assertEquals("AAPL", cancelled.symbol());
            assertEquals(10000.0, cancelled.quantity());
            assertEquals(175.50, cancelled.price());
            assertEquals(1730784000045L, cancelled.timestamp());
        }

        @Test
        @DisplayName("Full round-trip preserves all fields")
        void roundTripPreservesAllFields() {
            String json = JsonSerializer.serializeEvent(originalEvent);
            MarketEvent event = JsonSerializer.deserializeEvent(json);
            OrderCancelled roundTripped = (OrderCancelled) event;
            assertEquals(originalEvent, roundTripped);
        }
    }

    @Nested
    @DisplayName("TradeExecuted serialization round-trip")
    class TradeExecutedTest {

        private static final TradeExecuted originalEvent = new TradeExecuted(
            "evt-003", "TRADER_A", "TRADER_B", "AAPL", 500, 175.52, 1730784001000L
        );

        @Test
        @DisplayName("Serializes TradeExecuted to JSON with type discriminator")
        void serializesWithTypeInfo() {
            String json = JsonSerializer.serializeEvent(originalEvent);
            assertTrue(json.contains("\"type\":\"TRADE_EXECUTED\""), "JSON must contain type discriminator");
            assertTrue(json.contains("\"buyerId\":\"TRADER_A\""));
            assertTrue(json.contains("\"sellerId\":\"TRADER_B\""));
        }

        @Test
        @DisplayName("Deserializes TradeExecuted JSON back to correct type")
        void deserializesToCorrectType() {
            String json = JsonSerializer.serializeEvent(originalEvent);
            MarketEvent event = JsonSerializer.deserializeEvent(json);

            assertInstanceOf(TradeExecuted.class, event, "Deserialized event must be TradeExecuted");
            TradeExecuted executed = (TradeExecuted) event;
            assertEquals("evt-003", executed.eventId());
            assertEquals("TRADER_A", executed.buyerId());
            assertEquals("TRADER_B", executed.sellerId());
            assertEquals("AAPL", executed.symbol());
            assertEquals(500.0, executed.quantity());
            assertEquals(175.52, executed.price());
            assertEquals(1730784001000L, executed.timestamp());
        }

        @Test
        @DisplayName("Full round-trip preserves all fields")
        void roundTripPreservesAllFields() {
            String json = JsonSerializer.serializeEvent(originalEvent);
            MarketEvent event = JsonSerializer.deserializeEvent(json);
            TradeExecuted roundTripped = (TradeExecuted) event;
            assertEquals(originalEvent, roundTripped);
        }

        @Test
        @DisplayName("traderId() returns buyerId as per interface contract")
        void traderIdReturnsBuyerId() {
            assertEquals("TRADER_A", originalEvent.traderId());
        }

        @Test
        @DisplayName("Notional value is computed correctly")
        void notionalValue() {
            assertEquals(87_760.0, originalEvent.notional());
        }
    }

    @Nested
    @DisplayName("Polymorphic deserialization from raw JSON")
    class PolymorphicDeserializationTest {

        @Test
        @DisplayName("Deserializes OrderPlaced from raw JSON string")
        void deserializeOrderPlacedFromRawJson() {
            String json = """
                {
                  "type": "ORDER_PLACED",
                  "eventId": "evt-100",
                  "orderId": "ord-100",
                  "traderId": "TRADER_X",
                  "symbol": "GOOG",
                  "side": "SELL",
                  "quantity": 5000,
                  "price": 140.25,
                  "timestamp": 1730784100000
                }
                """;
            MarketEvent event = JsonSerializer.deserializeEvent(json);
            assertInstanceOf(OrderPlaced.class, event);
            OrderPlaced placed = (OrderPlaced) event;
            assertEquals("TRADER_X", placed.traderId());
            assertEquals("GOOG", placed.symbol());
            assertEquals("SELL", placed.side());
        }

        @Test
        @DisplayName("Deserializes OrderCancelled from raw JSON string")
        void deserializeOrderCancelledFromRawJson() {
            String json = """
                {
                  "type": "ORDER_CANCELLED",
                  "eventId": "evt-101",
                  "originalOrderId": "ord-100",
                  "traderId": "TRADER_X",
                  "symbol": "GOOG",
                  "quantity": 5000,
                  "price": 140.25,
                  "timestamp": 1730784100030
                }
                """;
            MarketEvent event = JsonSerializer.deserializeEvent(json);
            assertInstanceOf(OrderCancelled.class, event);
            OrderCancelled cancelled = (OrderCancelled) event;
            assertEquals("ord-100", cancelled.originalOrderId());
        }

        @Test
        @DisplayName("Deserializes TradeExecuted from raw JSON string")
        void deserializeTradeExecutedFromRawJson() {
            String json = """
                {
                  "type": "TRADE_EXECUTED",
                  "eventId": "evt-102",
                  "buyerId": "TRADER_X",
                  "sellerId": "TRADER_Y",
                  "symbol": "GOOG",
                  "quantity": 100,
                  "price": 140.30,
                  "timestamp": 1730784101000
                }
                """;
            MarketEvent event = JsonSerializer.deserializeEvent(json);
            assertInstanceOf(TradeExecuted.class, event);
            TradeExecuted executed = (TradeExecuted) event;
            assertEquals("TRADER_X", executed.buyerId());
            assertEquals("TRADER_Y", executed.sellerId());
        }

        @Test
        @DisplayName("Pattern matching switch works on deserialized events")
        void patternMatchingOnDeserializedEvents() {
            String orderPlacedJson = """
                {"type":"ORDER_PLACED","eventId":"e1","orderId":"o1","traderId":"T1","symbol":"AAPL","side":"BUY","quantity":100,"price":150.0,"timestamp":1000}
                """;
            String tradeExecutedJson = """
                {"type":"TRADE_EXECUTED","eventId":"e2","buyerId":"T1","sellerId":"T2","symbol":"AAPL","quantity":50,"price":150.0,"timestamp":2000}
                """;

            MarketEvent event1 = JsonSerializer.deserializeEvent(orderPlacedJson);
            MarketEvent event2 = JsonSerializer.deserializeEvent(tradeExecutedJson);

            // Verify pattern matching works on deserialized events
            String result1 = switch (event1) {
                case OrderPlaced op -> "PLACED:" + op.side();
                case OrderCancelled oc -> "CANCELLED:" + oc.originalOrderId();
                case TradeExecuted te -> "TRADED:" + te.sellerId();
            };
            assertEquals("PLACED:BUY", result1);

            String result2 = switch (event2) {
                case OrderPlaced op -> "PLACED:" + op.side();
                case OrderCancelled oc -> "CANCELLED:" + oc.originalOrderId();
                case TradeExecuted te -> "TRADED:" + te.sellerId();
            };
            assertEquals("TRADED:T2", result2);
        }
    }

    @Nested
    @DisplayName("ComplianceAlert serialization round-trip")
    class ComplianceAlertTest {

        private static final ComplianceAlert originalAlert = new ComplianceAlert(
            "alert-001", AlertType.SPOOFING, "TRADER_A", "AAPL",
            "Cancelled 95% of orders within 50ms window; O/C ratio 18.2 over 60s",
            1730784000050L, "HIGH"
        );

        @Test
        @DisplayName("Serializes ComplianceAlert to JSON")
        void serializesAlert() {
            String json = JsonSerializer.serializeAlert(originalAlert);
            assertTrue(json.contains("\"alertId\":\"alert-001\""));
            assertTrue(json.contains("\"alertType\":\"SPOOFING\""));
            assertTrue(json.contains("\"severity\":\"HIGH\""));
        }

        @Test
        @DisplayName("Deserializes ComplianceAlert from JSON")
        void deserializesAlert() {
            String json = JsonSerializer.serializeAlert(originalAlert);
            ComplianceAlert alert = JsonSerializer.deserializeAlert(json);
            assertEquals("alert-001", alert.alertId());
            assertEquals(AlertType.SPOOFING, alert.alertType());
            assertEquals("TRADER_A", alert.traderId());
            assertEquals("AAPL", alert.symbol());
            assertEquals("HIGH", alert.severity());
        }

        @Test
        @DisplayName("Full round-trip preserves all fields")
        void roundTripPreservesAllFields() {
            String json = JsonSerializer.serializeAlert(originalAlert);
            ComplianceAlert roundTripped = JsonSerializer.deserializeAlert(json);
            assertEquals(originalAlert, roundTripped);
        }

        @Test
        @DisplayName("AlertType enum serializes correctly for all types")
        void allAlertTypes() {
            for (AlertType type : AlertType.values()) {
                ComplianceAlert alert = new ComplianceAlert(
                    "test", type, "T", "S", "details", 0L, "LOW"
                );
                String json = JsonSerializer.serializeAlert(alert);
                ComplianceAlert deserialized = JsonSerializer.deserializeAlert(json);
                assertEquals(type, deserialized.alertType(), "AlertType " + type + " should round-trip");
            }
        }
    }

    @Nested
    @DisplayName("Alert ID generation")
    class AlertIdGenerationTest {

        @Test
        @DisplayName("Generates unique alert IDs")
        void generatesUniqueIds() {
            String id1 = JsonSerializer.generateAlertId();
            String id2 = JsonSerializer.generateAlertId();
            assertNotEquals(id1, id2);
            assertTrue(id1.startsWith("alert-"));
            assertTrue(id2.startsWith("alert-"));
        }
    }

    @Nested
    @DisplayName("Malformed JSON handling")
    class MalformedJsonTest {

        @Test
        @DisplayName("Throws on invalid JSON")
        void throwsOnInvalidJson() {
            assertThrows(RuntimeException.class, () -> JsonSerializer.deserializeEvent("{invalid json}"));
        }

        @Test
        @DisplayName("Throws on missing type discriminator")
        void throwsOnMissingType() {
            String json = """
                {"eventId":"e1","traderId":"T1","symbol":"AAPL","timestamp":1000}
                """;
            assertThrows(RuntimeException.class, () -> JsonSerializer.deserializeEvent(json));
        }

        @Test
        @DisplayName("Ignores unknown properties gracefully")
        void ignoresUnknownProperties() {
            String json = """
                {
                  "type": "ORDER_PLACED",
                  "eventId": "e1",
                  "orderId": "o1",
                  "traderId": "T1",
                  "symbol": "AAPL",
                  "side": "BUY",
                  "quantity": 100,
                  "price": 150.0,
                  "timestamp": 1000,
                  "unknownField": "should be ignored"
                }
                """;
            MarketEvent event = JsonSerializer.deserializeEvent(json);
            assertInstanceOf(OrderPlaced.class, event);
            assertEquals("e1", event.eventId());
        }
    }
}
