package com.sentinel.detector;

import com.sentinel.config.DetectorConfig;
import com.sentinel.model.AlertType;
import com.sentinel.model.ComplianceAlert;
import com.sentinel.model.OrderPlaced;
import com.sentinel.model.TradeExecuted;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for WashTradeDetector covering cycle detection,
 * time window eviction, edge cases, and graph state management.
 */
class WashTradeDetectorTest {

    private WashTradeDetector detector;

    @BeforeEach
    void setUp() {
        Properties props = new Properties();
        props.setProperty("wash.window_ms", "5000");
        props.setProperty("wash.max_cycle_length", "5");
        detector = new WashTradeDetector(new DetectorConfig(props));
    }

    @Nested
    @DisplayName("3-node ring detection")
    class ThreeNodeRingTest {

        @Test
        @DisplayName("Detects A→B→C→A wash trade ring")
        void detectsThreeNodeRing() {
            long baseTime = 1_000_000L;

            // A buys from B
            TradeExecuted trade1 = new TradeExecuted(
                "evt-001", "TRADER_A", "TRADER_B", "AAPL", 100, 150.00, baseTime
            );
            // B buys from C
            TradeExecuted trade2 = new TradeExecuted(
                "evt-002", "TRADER_B", "TRADER_C", "AAPL", 100, 150.00, baseTime + 1000
            );
            // C buys from A (completes the ring)
            TradeExecuted trade3 = new TradeExecuted(
                "evt-003", "TRADER_C", "TRADER_A", "AAPL", 100, 150.00, baseTime + 2000
            );

            detector.process(trade1);
            detector.process(trade2);
            List<ComplianceAlert> alerts = detector.process(trade3);

            assertEquals(1, alerts.size(), "3-node ring should trigger exactly one alert");
            ComplianceAlert alert = alerts.get(0);
            assertEquals(AlertType.WASH_TRADE, alert.alertType());
            assertEquals("HIGH", alert.severity());
            assertTrue(alert.details().contains("Wash trade ring"));
        }

        @Test
        @DisplayName("Detects ring with different symbols isolated")
        void ringIsolatedBySymbol() {
            long baseTime = 1_000_000L;

            // A→B on AAPL
            TradeExecuted trade1 = new TradeExecuted(
                "evt-001", "TRADER_A", "TRADER_B", "AAPL", 100, 150.00, baseTime
            );
            // B→C on AAPL
            TradeExecuted trade2 = new TradeExecuted(
                "evt-002", "TRADER_B", "TRADER_C", "AAPL", 100, 150.00, baseTime + 1000
            );
            // C→A on GOOG (different symbol, should NOT complete ring on AAPL)
            TradeExecuted trade3 = new TradeExecuted(
                "evt-003", "TRADER_C", "TRADER_A", "GOOG", 100, 150.00, baseTime + 2000
            );

            detector.process(trade1);
            detector.process(trade2);
            List<ComplianceAlert> alerts = detector.process(trade3);

            // C→A on GOOG creates a separate graph; no ring on AAPL
            // But C→A on GOOG alone doesn't form a ring either
            assertTrue(alerts.isEmpty(), "Cross-symbol trades should not form rings");
        }
    }

    @Nested
    @DisplayName("4-node ring detection")
    class FourNodeRingTest {

        @Test
        @DisplayName("Detects A→B→C→D→A wash trade ring")
        void detectsFourNodeRing() {
            long baseTime = 1_000_000L;

            TradeExecuted trade1 = new TradeExecuted(
                "evt-001", "TRADER_A", "TRADER_B", "AAPL", 100, 150.00, baseTime
            );
            TradeExecuted trade2 = new TradeExecuted(
                "evt-002", "TRADER_B", "TRADER_C", "AAPL", 100, 150.00, baseTime + 1000
            );
            TradeExecuted trade3 = new TradeExecuted(
                "evt-003", "TRADER_C", "TRADER_D", "AAPL", 100, 150.00, baseTime + 2000
            );
            TradeExecuted trade4 = new TradeExecuted(
                "evt-004", "TRADER_D", "TRADER_A", "AAPL", 100, 150.00, baseTime + 3000
            );

            detector.process(trade1);
            detector.process(trade2);
            detector.process(trade3);
            List<ComplianceAlert> alerts = detector.process(trade4);

            assertEquals(1, alerts.size(), "4-node ring should trigger exactly one alert");
            assertEquals(AlertType.WASH_TRADE, alerts.get(0).alertType());
        }
    }

    @Nested
    @DisplayName("No cycle scenarios")
    class NoCycleTest {

        @Test
        @DisplayName("No alert for linear chain (no cycle)")
        void noAlertOnLinearChain() {
            long baseTime = 1_000_000L;

            TradeExecuted trade1 = new TradeExecuted(
                "evt-001", "TRADER_A", "TRADER_B", "AAPL", 100, 150.00, baseTime
            );
            TradeExecuted trade2 = new TradeExecuted(
                "evt-002", "TRADER_B", "TRADER_C", "AAPL", 100, 150.00, baseTime + 1000
            );
            TradeExecuted trade3 = new TradeExecuted(
                "evt-003", "TRADER_C", "TRADER_D", "AAPL", 100, 150.00, baseTime + 2000
            );

            detector.process(trade1);
            detector.process(trade2);
            List<ComplianceAlert> alerts = detector.process(trade3);

            assertTrue(alerts.isEmpty(), "Linear chain should not trigger alert");
        }

        @Test
        @DisplayName("No alert for single trade")
        void noAlertOnSingleTrade() {
            TradeExecuted trade = new TradeExecuted(
                "evt-001", "TRADER_A", "TRADER_B", "AAPL", 100, 150.00, 1_000_000L
            );

            List<ComplianceAlert> alerts = detector.process(trade);
            assertTrue(alerts.isEmpty(), "Single trade should not trigger alert");
        }

        @Test
        @DisplayName("No alert for OrderPlaced event")
        void noAlertOnOrderPlaced() {
            OrderPlaced order = new OrderPlaced(
                "evt-001", "ord-001", "TRADER_A", "AAPL", "BUY", 1000, 150.00, 1_000_000L
            );

            List<ComplianceAlert> alerts = detector.process(order);
            assertTrue(alerts.isEmpty(), "OrderPlaced should not trigger wash trade alert");
        }
    }

    @Nested
    @DisplayName("Self-trade detection")
    class SelfTradeTest {

        @Test
        @DisplayName("Detects self-trade as cycle of length 1")
        void detectsSelfTrade() {
            // A trades with A (buyer and seller are the same)
            TradeExecuted trade = new TradeExecuted(
                "evt-001", "TRADER_A", "TRADER_A", "AAPL", 100, 150.00, 1_000_000L
            );

            List<ComplianceAlert> alerts = detector.process(trade);

            assertEquals(1, alerts.size(), "Self-trade should trigger wash trade alert");
            assertEquals(AlertType.WASH_TRADE, alerts.get(0).alertType());
        }
    }

    @Nested
    @DisplayName("Time window eviction")
    class TimeWindowTest {

        @Test
        @DisplayName("No alert when cycle edge expires from window")
        void noAlertWhenEdgeExpires() {
            long baseTime = 1_000_000L;

            // A→B at baseTime
            TradeExecuted trade1 = new TradeExecuted(
                "evt-001", "TRADER_A", "TRADER_B", "AAPL", 100, 150.00, baseTime
            );
            // B→C at baseTime + 1s
            TradeExecuted trade2 = new TradeExecuted(
                "evt-002", "TRADER_B", "TRADER_C", "AAPL", 100, 150.00, baseTime + 1000
            );
            // C→A at baseTime + 6s (outside 5s window from trade1)
            TradeExecuted trade3 = new TradeExecuted(
                "evt-003", "TRADER_C", "TRADER_A", "AAPL", 100, 150.00, baseTime + 6000
            );

            detector.process(trade1);
            detector.process(trade2);
            List<ComplianceAlert> alerts = detector.process(trade3);

            assertTrue(alerts.isEmpty(), "Expired edges should not form cycles");
        }

        @Test
        @DisplayName("Alert when all edges within window")
        void alertWhenAllEdgesInWindow() {
            long baseTime = 1_000_000L;

            TradeExecuted trade1 = new TradeExecuted(
                "evt-001", "TRADER_A", "TRADER_B", "AAPL", 100, 150.00, baseTime
            );
            TradeExecuted trade2 = new TradeExecuted(
                "evt-002", "TRADER_B", "TRADER_C", "AAPL", 100, 150.00, baseTime + 1000
            );
            TradeExecuted trade3 = new TradeExecuted(
                "evt-003", "TRADER_C", "TRADER_A", "AAPL", 100, 150.00, baseTime + 4000
            );

            detector.process(trade1);
            detector.process(trade2);
            List<ComplianceAlert> alerts = detector.process(trade3);

            assertEquals(1, alerts.size(), "All edges within 5s window should form cycle");
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCasesTest {

        @Test
        @DisplayName("Handles unknown traders gracefully")
        void handlesUnknownTraders() {
            TradeExecuted trade = new TradeExecuted(
                "evt-001", "UNKNOWN_A", "UNKNOWN_B", "AAPL", 100, 150.00, 1_000_000L
            );

            List<ComplianceAlert> alerts = detector.process(trade);
            // No crash, no alert for single trade
            assertTrue(alerts.isEmpty());
        }

        @Test
        @DisplayName("Reset clears all state")
        void resetClearsState() {
            long baseTime = 1_000_000L;

            TradeExecuted trade1 = new TradeExecuted(
                "evt-001", "TRADER_A", "TRADER_B", "AAPL", 100, 150.00, baseTime
            );
            TradeExecuted trade2 = new TradeExecuted(
                "evt-002", "TRADER_B", "TRADER_A", "AAPL", 100, 150.00, baseTime + 1000
            );

            detector.process(trade1);
            detector.reset();

            // After reset, trade2 alone should not form a cycle
            List<ComplianceAlert> alerts = detector.process(trade2);
            assertTrue(alerts.isEmpty(), "Reset should clear all graph state");
        }

        @Test
        @DisplayName("Alert details contain cycle participants")
        void alertDetailsContainParticipants() {
            long baseTime = 1_000_000L;

            TradeExecuted trade1 = new TradeExecuted(
                "evt-001", "TRADER_A", "TRADER_B", "AAPL", 100, 150.00, baseTime
            );
            TradeExecuted trade2 = new TradeExecuted(
                "evt-002", "TRADER_B", "TRADER_C", "AAPL", 100, 150.00, baseTime + 1000
            );
            TradeExecuted trade3 = new TradeExecuted(
                "evt-003", "TRADER_C", "TRADER_A", "AAPL", 100, 150.00, baseTime + 2000
            );

            detector.process(trade1);
            detector.process(trade2);
            List<ComplianceAlert> alerts = detector.process(trade3);

            assertEquals(1, alerts.size());
            String details = alerts.get(0).details();
            assertTrue(details.contains("TRADER_A"), "Details should mention TRADER_A");
            assertTrue(details.contains("TRADER_B"), "Details should mention TRADER_B");
            assertTrue(details.contains("TRADER_C"), "Details should mention TRADER_C");
        }
    }
}
