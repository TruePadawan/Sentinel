package com.sentinel.detector;

import com.sentinel.config.DetectorConfig;
import com.sentinel.model.AlertType;
import com.sentinel.model.ComplianceAlert;
import com.sentinel.model.MarketEvent;
import com.sentinel.model.OrderCancelled;
import com.sentinel.model.OrderPlaced;
import com.sentinel.model.TradeExecuted;
import com.sentinel.util.JsonSerializer;
import com.sentinel.util.SlidingWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Detects velocity and notional breach violations:
 * 1. Daily notional volume exceeds a configurable limit per trader/symbol.
 * 2. Rolling 1-minute notional volume exceeds a configurable limit per trader/symbol.
 * <p>
 * Uses incremental counters for O(1) amortized updates and time-bounded Deque
 * for rolling window eviction.
 * <p>
 * Complexity: O(1) amortized per event. Space: O(1) for daily + O(m) for rolling
 * minute bounded by 60s window.
 */
public class VelocityDetector implements Detector {

    private static final Logger logger = LoggerFactory.getLogger(VelocityDetector.class);

    private final double dailyNotionalLimit;
    private final double minuteNotionalLimit;

    /**
     * Per-trader/symbol composite key state
     */
    private final Map<String, TraderVelocityState> traderStates = new HashMap<>();

    /**
     * Per-trader/symbol velocity tracking state.
     */
    private static class TraderVelocityState {
        /**
         * Cumulative daily notional volume
         */
        private double dailyNotional = 0.0;
        /**
         * Rolling 1-minute window of notional ticks
         */
        private final SlidingWindow<NotionalTick> rollingMinuteWindow;
        /**
         * Incremental sum of rolling minute notional
         */
        private double rollingMinuteNotional = 0.0;
        /**
         * Start of current day (midnight epoch ms)
         */
        private long dayStart;

        public TraderVelocityState() {
            this.rollingMinuteWindow = new SlidingWindow<>(60_000, NotionalTick::timestamp);
            this.dayStart = 0;
        }
    }

    /**
     * Lightweight record for notional volume ticks in the rolling window.
     */
    private record NotionalTick(long timestamp, double notional) {
    }

    public VelocityDetector(DetectorConfig config) {
        this.dailyNotionalLimit = config.velocityDailyNotionalLimit();
        this.minuteNotionalLimit = config.velocityMinuteNotionalLimit();
    }

    @Override
    public List<ComplianceAlert> process(MarketEvent event) {
        List<ComplianceAlert> alerts = new ArrayList<>();

        double notional = computeNotional(event);
        if (notional <= 0) return alerts;

        String traderId = event.traderId();
        String symbol = event.symbol();
        String key = traderId + "|" + symbol;

        TraderVelocityState state = traderStates.computeIfAbsent(key, k -> new TraderVelocityState());

        // Reset daily total at midnight
        long currentDayStart = getDayStart(event.timestamp());
        if (state.dayStart != currentDayStart) {
            state.dailyNotional = 0.0;
            state.dayStart = currentDayStart;
        }

        // Update daily notional
        state.dailyNotional += notional;

        // Update rolling minute window
        NotionalTick tick = new NotionalTick(event.timestamp(), notional);
        state.rollingMinuteWindow.add(tick);
        state.rollingMinuteNotional += notional;

        // Recalculate rolling sum from window for accuracy after eviction
        state.rollingMinuteNotional = 0.0;
        for (NotionalTick t : state.rollingMinuteWindow.getDeque()) {
            state.rollingMinuteNotional += t.notional();
        }

        // Check daily limit
        if (state.dailyNotional > dailyNotionalLimit) {
            ComplianceAlert alert = new ComplianceAlert(
                    JsonSerializer.generateAlertId(),
                    AlertType.VELOCITY_BREACH,
                    traderId,
                    symbol,
                    String.format(
                            "Daily notional limit exceeded: $%.2f / $%.2f",
                            state.dailyNotional, dailyNotionalLimit
                    ),
                    event.timestamp(),
                    "HIGH"
            );
            alerts.add(alert);
            logger.info("Velocity alert: daily breach by {} on {} (${}/ ${})",
                    traderId, symbol,
                    String.format("%.2f", state.dailyNotional),
                    String.format("%.2f", dailyNotionalLimit));
        }

        // Check rolling minute limit
        if (state.rollingMinuteNotional > minuteNotionalLimit) {
            ComplianceAlert alert = new ComplianceAlert(
                    JsonSerializer.generateAlertId(),
                    AlertType.VELOCITY_BREACH,
                    traderId,
                    symbol,
                    String.format(
                            "Rolling minute notional limit exceeded: $%.2f / $%.2f",
                            state.rollingMinuteNotional, minuteNotionalLimit
                    ),
                    event.timestamp(),
                    "MEDIUM"
            );
            alerts.add(alert);
            logger.info("Velocity alert: minute breach by {} on {} (${}/ ${})",
                    traderId, symbol,
                    String.format("%.2f", state.rollingMinuteNotional),
                    String.format("%.2f", minuteNotionalLimit));
        }

        return alerts;
    }

    /**
     * Computes the notional value of a market event.
     * OrderPlaced and OrderCancelled use quantity * price.
     * TradeExecuted uses quantity * price.
     */
    private double computeNotional(MarketEvent event) {
        switch (event) {
            case OrderPlaced placed -> {
                return placed.quantity() * placed.price();
            }
            case OrderCancelled cancelled -> {
                return cancelled.quantity() * cancelled.price();
            }
            case TradeExecuted executed -> {
                return executed.quantity() * executed.price();
            }
        }
    }

    /**
     * Computes the start of the day (midnight) for a given timestamp.
     */
    private long getDayStart(long timestampMs) {
        // Approximate midnight: truncate to day boundary
        // Using a simple calculation: epoch days * 86.4 million
        long dayMs = 86_400_000L;
        return (timestampMs / dayMs) * dayMs;
    }

    /**
     * Resets all state. Useful for testing.
     */
    public void reset() {
        traderStates.clear();
    }
}
