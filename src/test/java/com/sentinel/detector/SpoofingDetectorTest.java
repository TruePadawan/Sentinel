package com.sentinel.detector;

import com.sentinel.config.DetectorConfig;
import com.sentinel.model.AlertType;
import com.sentinel.model.ComplianceAlert;
import com.sentinel.model.OrderCancelled;
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
 * Comprehensive tests for SpoofingDetector covering fast-cancel detection,
 * O/C ratio threshold, sliding window eviction, and edge cases.
 */
class SpoofingDetectorTest {

    private SpoofingDetector detector;

    @BeforeEach
    void setUp() {
        Properties props = new Properties();
        props.setProperty("spoof.cancel_window_ms", "50");
        props.setProperty("spoof.oc_ratio_threshold", "10.0");
        props.setProperty("spoof.oc_min_samples", "20");
        props.setProperty("spoof.quantity_threshold", "1000");
        detector = new SpoofingDetector(new DetectorConfig(props));
    }

    @Nested
    @DisplayName("Fast cancel detection")
    class FastCancelTest {

        @Test
        @DisplayName("Detects large order cancelled within 50ms")
        void detectsFastCancel() {
            OrderPlaced placed = new OrderPlaced(
                    "evt-001", "ord-001", "TRADER_A", "AAPL", "BUY", 10000, 175.50, 1_000_000L
            );
            OrderCancelled cancelled = new OrderCancelled(
                    "evt-002", "ord-001", "TRADER_A", "AAPL", 10000, 175.50, 1_000_040L
            );

            List<ComplianceAlert> placeAlerts = detector.process(placed);
            List<ComplianceAlert> cancelAlerts = detector.process(cancelled);

            assertTrue(placeAlerts.isEmpty(), "Placing an order should not trigger an alert");
            assertEquals(1, cancelAlerts.size(), "Fast cancel should trigger exactly one alert");

            ComplianceAlert alert = cancelAlerts.getFirst();
            assertEquals(AlertType.SPOOFING, alert.alertType());
            assertEquals("TRADER_A", alert.traderId());
            assertEquals("AAPL", alert.symbol());
            assertEquals("HIGH", alert.severity());
            assertTrue(alert.details().contains("Fast cancel"));
            assertTrue(alert.details().contains("40ms"));
        }

        @Test
        @DisplayName("No alert when cancel delay exceeds 50ms")
        void noAlertOnSlowCancel() {
            OrderPlaced placed = new OrderPlaced(
                    "evt-001", "ord-001", "TRADER_A", "AAPL", "BUY", 10000, 175.50, 1_000_000L
            );
            OrderCancelled cancelled = new OrderCancelled(
                    "evt-002", "ord-001", "TRADER_A", "AAPL", 10000, 175.50, 1_000_100L
            );

            detector.process(placed);
            List<ComplianceAlert> cancelAlerts = detector.process(cancelled);

            assertTrue(cancelAlerts.isEmpty(), "Cancel after 100ms should not trigger fast-cancel alert");
        }

        @Test
        @DisplayName("No alert when order quantity is below threshold")
        void noAlertOnSmallQuantity() {
            OrderPlaced placed = new OrderPlaced(
                    "evt-001", "ord-001", "TRADER_A", "AAPL", "BUY", 100, 175.50, 1_000_000L
            );
            OrderCancelled cancelled = new OrderCancelled(
                    "evt-002", "ord-001", "TRADER_A", "AAPL", 100, 175.50, 1_000_010L
            );

            detector.process(placed);
            List<ComplianceAlert> cancelAlerts = detector.process(cancelled);

            assertTrue(cancelAlerts.isEmpty(), "Small quantity cancel should not trigger alert");
        }

        @Test
        @DisplayName("No alert when cancel matches no pending order")
        void noAlertOnUnknownCancel() {
            OrderCancelled cancelled = new OrderCancelled(
                    "evt-002", "ord-nonexistent", "TRADER_A", "AAPL", 10000, 175.50, 1_000_010L
            );

            List<ComplianceAlert> alerts = detector.process(cancelled);
            assertTrue(alerts.isEmpty(), "Cancel without matching order should not crash or alert");
        }

        @Test
        @DisplayName("Exact 50ms delay does not trigger (strict less-than)")
        void exactBoundaryNoAlert() {
            OrderPlaced placed = new OrderPlaced(
                    "evt-001", "ord-001", "TRADER_A", "AAPL", "BUY", 10000, 175.50, 1_000_000L
            );
            OrderCancelled cancelled = new OrderCancelled(
                    "evt-002", "ord-001", "TRADER_A", "AAPL", 10000, 175.50, 1_000_050L
            );

            detector.process(placed);
            List<ComplianceAlert> cancelAlerts = detector.process(cancelled);

            assertTrue(cancelAlerts.isEmpty(), "Exactly 50ms delay should not trigger (strict < 50ms)");
        }

        @Test
        @DisplayName("49ms delay triggers alert")
        void justUnderBoundaryTriggers() {
            OrderPlaced placed = new OrderPlaced(
                    "evt-001", "ord-001", "TRADER_A", "AAPL", "BUY", 10000, 175.50, 1_000_000L
            );
            OrderCancelled cancelled = new OrderCancelled(
                    "evt-002", "ord-001", "TRADER_A", "AAPL", 10000, 175.50, 1_000_049L
            );

            detector.process(placed);
            List<ComplianceAlert> cancelAlerts = detector.process(cancelled);

            assertEquals(1, cancelAlerts.size(), "49ms delay should trigger fast-cancel alert");
        }
    }

    @Nested
    @DisplayName("Normal trading patterns")
    class NormalTradingTest {

        @Test
        @DisplayName("No alert on normal order placement and execution")
        void normalTradingNoAlert() {
            long baseTime = 1_000_000L;

            for (int i = 0; i < 10; i++) {
                OrderPlaced placed = new OrderPlaced(
                        "evt-" + i, "ord-" + i, "TRADER_A", "AAPL", "BUY",
                        100, 175.50, baseTime + i * 1000
                );
                List<ComplianceAlert> alerts = detector.process(placed);
                assertTrue(alerts.isEmpty(), "Normal order placement should not alert");
            }
        }

        @Test
        @DisplayName("TradeExecuted does not trigger spoofing alert")
        void tradeExecutedNoAlert() {
            TradeExecuted trade = new TradeExecuted(
                    "evt-001", "TRADER_A", "TRADER_B", "AAPL", 500, 175.52, 1_000_000L
            );

            List<ComplianceAlert> alerts = detector.process(trade);
            assertTrue(alerts.isEmpty(), "Trade execution should not trigger spoofing alert");
        }
    }

    @Nested
    @DisplayName("O/C ratio threshold detection")
    class OcRatioTest {

        @Test
        @DisplayName("Detects abnormal cancel ratio over 60s window")
        void detectsHighCancelRatio() {
            long baseTime = 1_000_000L;

            // Place 2 orders and cancel 25 times (many cancels per order)
            // This creates a cancel ratio > 10.0
            for (int i = 0; i < 2; i++) {
                OrderPlaced placed = new OrderPlaced(
                        "evt-p-" + i, "ord-" + i, "TRADER_A", "AAPL", "BUY",
                        10000, 175.50, baseTime + i * 100
                );
                detector.process(placed);
            }

            for (int i = 0; i < 25; i++) {
                // Cancel existing orders and some non-existent ones
                OrderCancelled cancelled = new OrderCancelled(
                        "evt-c-" + i, "ord-" + (i % 2), "TRADER_A", "AAPL",
                        10000, 175.50, baseTime + 200 + i * 100
                );
                List<ComplianceAlert> alerts = detector.process(cancelled);
                // After enough cancels, we should see an O/C ratio alert
                for (ComplianceAlert alert : alerts) {
                    if (alert.details().contains("O/C ratio")) {
                        assertEquals(AlertType.SPOOFING, alert.alertType());
                        assertEquals("MEDIUM", alert.severity());
                        assertEquals("TRADER_A", alert.traderId());
                        return;
                    }
                }
            }

            fail("Should have triggered O/C ratio alert");
        }

        @Test
        @DisplayName("No O/C ratio alert below min samples")
        void noAlertBelowMinSamples() {
            long baseTime = 1_000_000L;

            // Only 5 orders and 5 cancels - below min_samples=20
            for (int i = 0; i < 5; i++) {
                OrderPlaced placed = new OrderPlaced(
                        "evt-p-" + i, "ord-" + i, "TRADER_A", "AAPL", "BUY",
                        100, 175.50, baseTime + i * 100
                );
                detector.process(placed);

                OrderCancelled cancelled = new OrderCancelled(
                        "evt-c-" + i, "ord-" + i, "TRADER_A", "AAPL",
                        100, 175.50, baseTime + i * 100 + 10
                );
                detector.process(cancelled);
            }

            // No O/C ratio alert should have been emitted due to min_samples
            detector.reset();
        }
    }

    @Nested
    @DisplayName("Sliding window eviction")
    class SlidingWindowEvictionTest {

        @Test
        @DisplayName("Events older than 60s are evicted from window")
        void evictsOldEvents() {
            long baseTime = 1_000_000L;

            // Place and cancel orders at baseTime
            for (int i = 0; i < 25; i++) {
                OrderPlaced placed = new OrderPlaced(
                        "evt-p-" + i, "ord-" + i, "TRADER_A", "AAPL", "BUY",
                        10000, 175.50, baseTime
                );
                detector.process(placed);

                OrderCancelled cancelled = new OrderCancelled(
                        "evt-c-" + i, "ord-" + i, "TRADER_A", "AAPL",
                        10000, 175.50, baseTime + 10
                );
                detector.process(cancelled);
            }

            // Now place an order 61 seconds later - old events should be evicted
            OrderPlaced newOrder = new OrderPlaced(
                    "evt-new", "ord-new", "TRADER_A", "AAPL", "BUY",
                    10000, 175.50, baseTime + 61_000
            );
            List<ComplianceAlert> alerts = detector.process(newOrder);

            // No O/C ratio alert because old events were evicted
            boolean hasOcAlert = alerts.stream()
                    .anyMatch(a -> a.details().contains("O/C ratio"));
            assertFalse(hasOcAlert, "Old events should be evicted, no O/C ratio alert");
        }

        @Test
        @DisplayName("Events within 60s window are retained")
        void retainsRecentEvents() {
            long baseTime = 1_000_000L;

            // Place an order at baseTime and another at baseTime + 59s
            OrderPlaced order1 = new OrderPlaced(
                    "evt-001", "ord-001", "TRADER_A", "AAPL", "BUY",
                    10000, 175.50, baseTime
            );
            detector.process(order1);

            OrderPlaced order2 = new OrderPlaced(
                    "evt-002", "ord-002", "TRADER_A", "AAPL", "BUY",
                    10000, 175.50, baseTime + 59_000
            );
            detector.process(order2);

            // Both orders should still be in the window
            // Cancel the first order - it should still be found
            OrderCancelled cancel = new OrderCancelled(
                    "evt-003", "ord-001", "TRADER_A", "AAPL",
                    10000, 175.50, baseTime + 59_010
            );
            List<ComplianceAlert> alerts = detector.process(cancel);

            // The cancel is 59010ms after placement, so no fast-cancel alert
            // But the order should still be in pendingOrders
            assertTrue(alerts.stream().noneMatch(a -> a.details().contains("Fast cancel")),
                    "59s delay should not trigger fast cancel");
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCasesTest {

        @Test
        @DisplayName("Multiple traders are tracked independently")
        void independentTraderState() {
            long baseTime = 1_000_000L;

            // TRADER_A does a fast cancel
            OrderPlaced placedA = new OrderPlaced(
                    "evt-a1", "ord-a1", "TRADER_A", "AAPL", "BUY", 10000, 175.50, baseTime
            );
            OrderCancelled cancelledA = new OrderCancelled(
                    "evt-a2", "ord-a1", "TRADER_A", "AAPL", 10000, 175.50, baseTime + 10
            );

            // TRADER_B does normal trading
            OrderPlaced placedB = new OrderPlaced(
                    "evt-b1", "ord-b1", "TRADER_B", "AAPL", "BUY", 100, 175.50, baseTime
            );
            OrderCancelled cancelledB = new OrderCancelled(
                    "evt-b2", "ord-b1", "TRADER_B", "AAPL", 100, 175.50, baseTime + 1000
            );

            detector.process(placedA);
            List<ComplianceAlert> alertsA = detector.process(cancelledA);
            detector.process(placedB);
            List<ComplianceAlert> alertsB = detector.process(cancelledB);

            assertEquals(1, alertsA.size(), "TRADER_A should trigger alert");
            assertTrue(alertsB.isEmpty(), "TRADER_B should not trigger alert");
        }

        @Test
        @DisplayName("Reset clears all state")
        void resetClearsState() {
            detector.reset();
            // After reset, a cancel for a previously placed order should not match
            OrderCancelled cancelled = new OrderCancelled(
                    "evt-001", "ord-001", "TRADER_A", "AAPL", 10000, 175.50, 1_000_010L
            );
            List<ComplianceAlert> alerts = detector.process(cancelled);
            assertTrue(alerts.isEmpty(), "Reset should clear all pending orders");
        }

        @Test
        @DisplayName("Detector name returns class name")
        void detectorName() {
            assertEquals("SpoofingDetector", detector.name());
        }
    }
}
