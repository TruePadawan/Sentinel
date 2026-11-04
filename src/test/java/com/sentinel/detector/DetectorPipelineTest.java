package com.sentinel.detector;

import com.sentinel.config.DetectorConfig;
import com.sentinel.model.*;
import com.sentinel.producer.AlertSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for DetectorPipeline exercising all three detectors
 * together with event ordering and state consistency.
 */
class DetectorPipelineTest {

    private DetectorPipeline pipeline;
    private List<ComplianceAlert> capturedAlerts;

    @BeforeEach
    void setUp() {
        Properties props = new Properties();
        props.setProperty("spoof.cancel_window_ms", "50");
        props.setProperty("spoof.oc_ratio_threshold", "10.0");
        props.setProperty("spoof.oc_min_samples", "20");
        props.setProperty("spoof.quantity_threshold", "1000");
        props.setProperty("wash.window_ms", "5000");
        props.setProperty("wash.max_cycle_length", "5");
        props.setProperty("velocity.daily_notional_limit", "10000000.0");
        props.setProperty("velocity.minute_notional_limit", "1000000.0");

        DetectorConfig config = new DetectorConfig(props);

        List<Detector> detectors = List.of(
                new SpoofingDetector(config),
                new WashTradeDetector(config),
                new VelocityDetector(config)
        );

        // Use a no-op producer that captures alerts for verification
        capturedAlerts = new ArrayList<>();
        AlertSender noOpSender = new AlertSender() {
            @Override
            public void send(ComplianceAlert alert) {
                capturedAlerts.add(alert);
            }

            @Override
            public void flush() {}
        };
        pipeline = new DetectorPipeline(detectors, noOpSender);
    }

    @Nested
    @DisplayName("Multi-detector event processing")
    class MultiDetectorTest {

        @Test
        @DisplayName("Spoofing event triggers only SpoofingDetector")
        void spoofingEventTriggersOnlySpoofingDetector() {
            long baseTime = 1_000_000L;

            // Use small quantity to avoid triggering VelocityDetector
            OrderPlaced order = new OrderPlaced(
                    "evt-001", "ord-001", "TRADER_A", "AAPL", "BUY", 1000, 1.00, baseTime
            );
            OrderCancelled cancel = new OrderCancelled(
                    "evt-002", "ord-001", "TRADER_A", "AAPL", 1000, 1.00, baseTime + 30
            );

            pipeline.process(order);
            List<ComplianceAlert> alerts = pipeline.process(cancel);

            assertEquals(1, alerts.size(), "Fast cancel should trigger one alert");
            assertEquals(AlertType.SPOOFING, alerts.get(0).alertType());
            assertTrue(capturedAlerts.contains(alerts.get(0)), "Alert should be captured by producer");
        }

        @Test
        @DisplayName("Wash trade event triggers only WashTradeDetector")
        void washTradeEventTriggersOnlyWashTradeDetector() {
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

            pipeline.process(trade1);
            pipeline.process(trade2);
            List<ComplianceAlert> alerts = pipeline.process(trade3);

            assertEquals(1, alerts.size(), "3-node ring should trigger one alert");
            assertEquals(AlertType.WASH_TRADE, alerts.get(0).alertType());
        }

        @Test
        @DisplayName("Normal trading generates no alerts")
        void normalTradingNoAlerts() {
            long baseTime = 1_000_000L;

            OrderPlaced order = new OrderPlaced(
                    "evt-001", "ord-001", "TRADER_A", "AAPL", "BUY", 100, 175.50, baseTime
            );
            TradeExecuted trade = new TradeExecuted(
                    "evt-002", "TRADER_A", "TRADER_B", "AAPL", 100, 175.50, baseTime + 1000
            );

            List<ComplianceAlert> alerts1 = pipeline.process(order);
            List<ComplianceAlert> alerts2 = pipeline.process(trade);

            assertTrue(alerts1.isEmpty(), "Normal order should not trigger alert");
            assertTrue(alerts2.isEmpty(), "Normal trade should not trigger alert");
        }
    }

    @Nested
    @DisplayName("Event ordering and state consistency")
    class EventOrderingTest {

        @Test
        @DisplayName("Events are processed in order")
        void eventsProcessedInOrder() {
            long baseTime = 1_000_000L;

            // Process a sequence of events
            List<MarketEvent> events = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                events.add(new OrderPlaced(
                        "evt-" + i, "ord-" + i, "TRADER_A", "AAPL", "BUY",
                        100, 175.50, baseTime + i * 1000
                ));
            }

            for (MarketEvent event : events) {
                pipeline.process(event);
            }

            assertEquals(10, pipeline.totalEvents(), "All 10 events should be counted");
        }

        @Test
        @DisplayName("State is consistent across detectors")
        void stateConsistentAcrossDetectors() {
            long baseTime = 1_000_000L;

            // Use small quantity to avoid triggering VelocityDetector
            OrderPlaced order = new OrderPlaced(
                    "evt-001", "ord-001", "TRADER_A", "AAPL", "BUY", 1000, 1.00, baseTime
            );
            OrderCancelled cancel = new OrderCancelled(
                    "evt-002", "ord-001", "TRADER_A", "AAPL", 1000, 1.00, baseTime + 30
            );

            pipeline.process(order);
            List<ComplianceAlert> alerts = pipeline.process(cancel);

            assertEquals(1, alerts.size());
            assertEquals(AlertType.SPOOFING, alerts.get(0).alertType());
            // No wash trade alert should be mixed in
            assertFalse(alerts.stream().anyMatch(a -> a.alertType() == AlertType.WASH_TRADE));
        }
    }

    @Nested
    @DisplayName("Latency tracking")
    class LatencyTrackingTest {

        @Test
        @DisplayName("Tracks total events processed")
        void tracksTotalEvents() {
            long baseTime = 1_000_000L;

            for (int i = 0; i < 100; i++) {
                pipeline.process(new OrderPlaced(
                        "evt-" + i, "ord-" + i, "TRADER_A", "AAPL", "BUY",
                        100, 175.50, baseTime + i * 1000
                ));
            }

            assertEquals(100, pipeline.totalEvents());
            assertTrue(pipeline.averageDetectionTimeMs() >= 0, "Average time should be non-negative");
        }

        @Test
        @DisplayName("Reset clears statistics")
        void resetClearsStats() {
            long baseTime = 1_000_000L;

            pipeline.process(new OrderPlaced(
                    "evt-001", "ord-001", "TRADER_A", "AAPL", "BUY", 100, 175.50, baseTime
            ));
            assertEquals(1, pipeline.totalEvents());

            pipeline.resetStats();
            assertEquals(0, pipeline.totalEvents());
            assertEquals(0.0, pipeline.averageDetectionTimeMs(), 0.0);
        }
    }

    @Nested
    @DisplayName("Batch processing")
    class BatchProcessingTest {

        @Test
        @DisplayName("Process batch of events")
        void processBatch() {
            long baseTime = 1_000_000L;

            List<MarketEvent> events = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                events.add(new OrderPlaced(
                        "evt-" + i, "ord-" + i, "TRADER_A", "AAPL", "BUY",
                        100, 175.50, baseTime + i * 1000
                ));
            }

            int alertCount = pipeline.processBatch(events);
            assertEquals(10, pipeline.totalEvents());
            assertEquals(0, alertCount, "No alerts for normal orders");
        }
    }

}
