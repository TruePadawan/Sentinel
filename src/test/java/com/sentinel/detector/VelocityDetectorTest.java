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
 * Comprehensive tests for VelocityDetector covering daily notional limits,
 * rolling minute limits, midnight reset, and window eviction.
 */
class VelocityDetectorTest {

    private VelocityDetector detector;

    @BeforeEach
    void setUp() {
        Properties props = new Properties();
        props.setProperty("velocity.daily_notional_limit", "10000000.0"); // $10M
        props.setProperty("velocity.minute_notional_limit", "1000000.0");  // $1M
        detector = new VelocityDetector(new DetectorConfig(props));
    }

    @Nested
    @DisplayName("Daily notional limit")
    class DailyLimitTest {

        @Test
        @DisplayName("Alert when daily notional exceeds limit")
        void alertsOnDailyBreach() {
            long baseTime = 1_000_000L;

            // 7 trades of $1.5M each = $10.5M total, exceeds $10M limit
            for (int i = 0; i < 7; i++) {
                TradeExecuted trade = new TradeExecuted(
                    "evt-" + i, "TRADER_A", "TRADER_B", "AAPL",
                    10000, 150.00, baseTime + i * 1000
                );
                List<ComplianceAlert> alerts = detector.process(trade);
                // First 6 trades: $9M total, no alert
                // 7th trade: $10.5M total, alert
                if (i < 6) {
                    assertTrue(
                        alerts.stream().noneMatch(a -> a.details().contains("Daily")),
                        "Trade " + i + " should not trigger daily alert yet"
                    );
                } else {
                    assertTrue(
                        alerts.stream().anyMatch(a -> a.details().contains("Daily")),
                        "Trade 7 should trigger daily alert"
                    );
                }
            }
        }

        @Test
        @DisplayName("No alert when daily notional is below limit")
        void noAlertBelowDailyLimit() {
            long baseTime = 1_000_000L;

            // 5 trades of $1M each = $5M total, below $10M limit
            for (int i = 0; i < 5; i++) {
                TradeExecuted trade = new TradeExecuted(
                    "evt-" + i, "TRADER_A", "TRADER_B", "AAPL",
                    5000, 200.00, baseTime + i * 1000
                );
                List<ComplianceAlert> alerts = detector.process(trade);
                assertTrue(
                    alerts.stream().noneMatch(a -> a.details().contains("Daily")),
                    "Should not trigger daily alert below limit"
                );
            }
        }

        @Test
        @DisplayName("Daily limit is per trader/symbol combination")
        void dailyLimitPerTraderSymbol() {
            long baseTime = 1_000_000L;

            // TRADER_A on AAPL: $6M
            for (int i = 0; i < 4; i++) {
                TradeExecuted trade = new TradeExecuted(
                    "evt-a-" + i, "TRADER_A", "TRADER_B", "AAPL",
                    10000, 150.00, baseTime + i * 1000
                );
                detector.process(trade);
            }

            // TRADER_A on GOOG: $6M (separate from AAPL)
            for (int i = 0; i < 4; i++) {
                TradeExecuted trade = new TradeExecuted(
                    "evt-g-" + i, "TRADER_A", "TRADER_B", "GOOG",
                    10000, 150.00, baseTime + i * 1000
                );
                detector.process(trade);
            }

            // Neither should have triggered daily alert yet ($6M each < $10M)
            // Add more to AAPL to cross threshold
            TradeExecuted trade = new TradeExecuted(
                "evt-a-extra", "TRADER_A", "TRADER_B", "AAPL",
                10000, 150.00, baseTime + 4000
            );
            List<ComplianceAlert> alerts = detector.process(trade);
            // AAPL: $7.5M, still below $10M
            assertTrue(
                alerts.stream().noneMatch(a -> a.details().contains("Daily")),
                "AAPL should not trigger daily alert at $7.5M"
            );
        }
    }

    @Nested
    @DisplayName("Rolling minute notional limit")
    class MinuteLimitTest {

        @Test
        @DisplayName("Alert when rolling minute notional exceeds limit")
        void alertsOnMinuteBreach() {
            long baseTime = 1_000_000L;

            // 7 trades of $150K each within 1 minute = $1.05M, exceeds $1M limit
            for (int i = 0; i < 7; i++) {
                TradeExecuted trade = new TradeExecuted(
                    "evt-" + i, "TRADER_A", "TRADER_B", "AAPL",
                    1000, 150.00, baseTime + i * 500 // 500ms apart
                );
                List<ComplianceAlert> alerts = detector.process(trade);
                if (i < 6) {
                    assertTrue(
                        alerts.stream().noneMatch(a -> a.details().contains("minute")),
                        "Trade " + i + " should not trigger minute alert yet"
                    );
                } else {
                    assertTrue(
                        alerts.stream().anyMatch(a -> a.details().contains("minute")),
                        "Trade 7 should trigger minute alert"
                    );
                }
            }
        }

        @Test
        @DisplayName("No alert when rolling minute notional is below limit")
        void noAlertBelowMinuteLimit() {
            long baseTime = 1_000_000L;

            // 5 trades of $100K each within 1 minute = $500K, below $1M limit
            for (int i = 0; i < 5; i++) {
                TradeExecuted trade = new TradeExecuted(
                    "evt-" + i, "TRADER_A", "TRADER_B", "AAPL",
                    500, 200.00, baseTime + i * 500
                );
                List<ComplianceAlert> alerts = detector.process(trade);
                assertTrue(
                    alerts.stream().noneMatch(a -> a.details().contains("minute")),
                    "Should not trigger minute alert below limit"
                );
            }
        }
    }

    @Nested
    @DisplayName("Midnight reset")
    class MidnightResetTest {

        @Test
        @DisplayName("Daily total resets at midnight")
        void dailyResetsAtMidnight() {
            // Day 1: accumulate $10M
            long day1Start = 86_400_000L; // 1 day after epoch
            for (int i = 0; i < 6; i++) {
                TradeExecuted trade = new TradeExecuted(
                    "evt-d1-" + i, "TRADER_A", "TRADER_B", "AAPL",
                    10000, 150.00, day1Start + i * 1000
                );
                detector.process(trade);
            }
            // $9M on day 1, no alert yet

            // Day 2: same trader, fresh daily total
            long day2Start = 86_400_000L * 2; // 2 days after epoch
            TradeExecuted trade = new TradeExecuted(
                "evt-d2-0", "TRADER_A", "TRADER_B", "AAPL",
                1000, 150.00, day2Start
            );
            List<ComplianceAlert> alerts = detector.process(trade);
            // Day 2 starts fresh: $150K, no daily alert
            assertTrue(
                alerts.stream().noneMatch(a -> a.details().contains("Daily")),
                "New day should reset daily total"
            );
        }
    }

    @Nested
    @DisplayName("Rolling window eviction")
    class WindowEvictionTest {

        @Test
        @DisplayName("Old ticks evicted from rolling minute window")
        void evictsOldTicks() {
            long baseTime = 1_000_000L;

            // 7 trades of $150K within first 3 seconds = $1.05M
            for (int i = 0; i < 7; i++) {
                TradeExecuted trade = new TradeExecuted(
                    "evt-" + i, "TRADER_A", "TRADER_B", "AAPL",
                    1000, 150.00, baseTime + i * 500
                );
                detector.process(trade);
            }

            // Wait 60 seconds: all ticks should be evicted
            TradeExecuted laterTrade = new TradeExecuted(
                "evt-later", "TRADER_A", "TRADER_B", "AAPL",
                1000, 150.00, baseTime + 61_000
            );
            List<ComplianceAlert> alerts = detector.process(laterTrade);
            // Only $150K in window, no minute alert
            assertTrue(
                alerts.stream().noneMatch(a -> a.details().contains("minute")),
                "Old ticks should be evicted from rolling window"
            );
        }

        @Test
        @DisplayName("Rolling window maintains correct sum after eviction")
        void rollingSumAfterEviction() {
            long baseTime = 1_000_000L;

            // 5 trades of $200K at baseTime = $1M total
            for (int i = 0; i < 5; i++) {
                TradeExecuted trade = new TradeExecuted(
                    "evt-" + i, "TRADER_A", "TRADER_B", "AAPL",
                    1000, 200.00, baseTime
                );
                detector.process(trade);
            }

            // At 30 seconds: still $1M in window
            TradeExecuted midTrade = new TradeExecuted(
                "evt-mid", "TRADER_A", "TRADER_B", "AAPL",
                100, 100.00, baseTime + 30_000
            );
            List<ComplianceAlert> midAlerts = detector.process(midTrade);
            // $1M + $10K = $1.01M, should trigger minute alert
            assertTrue(
                midAlerts.stream().anyMatch(a -> a.details().contains("minute")),
                "Should trigger minute alert at $1.01M"
            );

            // At 61 seconds: old ticks evicted, only $10K in window
            TradeExecuted lateTrade = new TradeExecuted(
                "evt-late", "TRADER_A", "TRADER_B", "AAPL",
                100, 100.00, baseTime + 61_000
            );
            List<ComplianceAlert> lateAlerts = detector.process(lateTrade);
            // midTrade at 30s is also evicted (61s - 30s = 31s < 60s, still in window)
            // Actually midTrade at 30_000 is within 60s of 61_000, so it stays
            // Window contains: midTrade ($10K) + lateTrade ($10K) = $20K
            // No minute alert expected
            assertTrue(
                lateAlerts.stream().noneMatch(a -> a.details().contains("minute")),
                "Evicted window should not trigger minute alert"
            );
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCasesTest {

        @Test
        @DisplayName("Handles OrderPlaced events")
        void handlesOrderPlaced() {
            OrderPlaced order = new OrderPlaced(
                "evt-001", "ord-001", "TRADER_A", "AAPL", "BUY", 100, 150.00, 1_000_000L
            );

            List<ComplianceAlert> alerts = detector.process(order);
            // $15K notional, below both limits
            assertTrue(alerts.isEmpty());
        }

        @Test
        @DisplayName("Handles OrderCancelled events")
        void handlesOrderCancelled() {
            OrderCancelled cancel = new OrderCancelled(
                "evt-001", "ord-001", "TRADER_A", "AAPL", 100, 150.00, 1_000_000L
            );

            List<ComplianceAlert> alerts = detector.process(cancel);
            // $15K notional, below both limits
            assertTrue(alerts.isEmpty());
        }

        @Test
        @DisplayName("Alert has correct type and severity")
        void alertTypeAndSeverity() {
            long baseTime = 1_000_000L;

            // Trigger daily breach
            for (int i = 0; i < 7; i++) {
                TradeExecuted trade = new TradeExecuted(
                    "evt-" + i, "TRADER_A", "TRADER_B", "AAPL",
                    10000, 150.00, baseTime + i * 1000
                );
                List<ComplianceAlert> alerts = detector.process(trade);
                for (ComplianceAlert alert : alerts) {
                    assertEquals(AlertType.VELOCITY_BREACH, alert.alertType());
                    if (alert.details().contains("Daily")) {
                        assertEquals("HIGH", alert.severity());
                    } else if (alert.details().contains("minute")) {
                        assertEquals("MEDIUM", alert.severity());
                    }
                }
            }
        }

        @Test
        @DisplayName("Reset clears all state")
        void resetClearsState() {
            long baseTime = 1_000_000L;

            // Accumulate some notional
            for (int i = 0; i < 5; i++) {
                TradeExecuted trade = new TradeExecuted(
                    "evt-" + i, "TRADER_A", "TRADER_B", "AAPL",
                    10000, 150.00, baseTime + i * 1000
                );
                detector.process(trade);
            }

            detector.reset();

            // After reset, same volume should not trigger alerts
            for (int i = 0; i < 5; i++) {
                TradeExecuted trade = new TradeExecuted(
                    "evt-r-" + i, "TRADER_A", "TRADER_B", "AAPL",
                    10000, 150.00, baseTime + i * 1000
                );
                List<ComplianceAlert> alerts = detector.process(trade);
                assertTrue(
                    alerts.stream().noneMatch(a -> a.details().contains("Daily")),
                    "Reset should clear daily state"
                );
            }
        }

        @Test
        @DisplayName("Multiple traders tracked independently")
        void independentTraderTracking() {
            long baseTime = 1_000_000L;

            // TRADER_A: $6M
            for (int i = 0; i < 4; i++) {
                TradeExecuted trade = new TradeExecuted(
                    "evt-a-" + i, "TRADER_A", "TRADER_B", "AAPL",
                    10000, 150.00, baseTime + i * 1000
                );
                detector.process(trade);
            }

            // TRADER_C: $6M (independent)
            for (int i = 0; i < 4; i++) {
                TradeExecuted trade = new TradeExecuted(
                    "evt-c-" + i, "TRADER_C", "TRADER_D", "AAPL",
                    10000, 150.00, baseTime + i * 1000
                );
                detector.process(trade);
            }

            // Neither should have triggered daily alert
            // Add more to TRADER_A to cross threshold
            TradeExecuted tradeA = new TradeExecuted(
                "evt-a-extra", "TRADER_A", "TRADER_B", "AAPL",
                10000, 150.00, baseTime + 4000
            );
            List<ComplianceAlert> alertsA = detector.process(tradeA);
            // TRADER_A: $7.5M, still below $10M
            assertTrue(
                alertsA.stream().noneMatch(a -> a.details().contains("Daily")),
                "TRADER_A should not trigger at $7.5M"
            );

            // Add more to TRADER_C to cross threshold
            for (int i = 0; i < 3; i++) {
                TradeExecuted tradeC = new TradeExecuted(
                    "evt-c-extra-" + i, "TRADER_C", "TRADER_D", "AAPL",
                    10000, 150.00, baseTime + 4000 + i * 1000
                );
                List<ComplianceAlert> alertsC = detector.process(tradeC);
                // TRADER_C: $6M + $4.5M = $10.5M, should trigger
                if (i == 2) {
                    assertTrue(
                        alertsC.stream().anyMatch(a -> a.details().contains("Daily")),
                        "TRADER_C should trigger daily alert at $10.5M"
                    );
                }
            }
        }
    }
}
