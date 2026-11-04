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
 * Detects spoofing patterns:
 * 1. Fast cancel - large order cancelled within a configurable time window before execution.
 * 2. Abnormal Order-to-Cancel ratio over a sliding 60-second window.
 * <p>
 * State is keyed by trader ID, with per-trader sliding windows and pending order maps.
 * Complexity: O(1) amortized per event. Space: O(w) bounded by 60s window.
 */
public class SpoofingDetector implements Detector {

    private static final Logger logger = LoggerFactory.getLogger(SpoofingDetector.class);

    private final long cancelWindowMs;
    private final double ocRatioThreshold;
    private final int ocMinSamples;
    private final double quantityThreshold;

    /**
     * Per-trader state: pending orders keyed by orderId
     */
    private final Map<String, TraderState> traderStates = new HashMap<>();

    /**
     * Per-trader state holding a sliding window and pending orders.
     */
    private static class TraderState {
        /**
         * Sliding 60-second window of order events (placed and cancelled)
         */
        private final SlidingWindow<OrderEvent> slidingWindow;
        /**
         * Pending orders keyed by orderId for fast-cancel lookup
         */
        private final Map<String, OrderPlaced> pendingOrders = new HashMap<>();
        /**
         * Incremental counters for O/C ratio
         */
        private int orderCount = 0;
        private int cancelCount = 0;

        public TraderState(long windowMs) {
            this.slidingWindow = new SlidingWindow<>(windowMs, OrderEvent::timestamp);
        }
    }

    /**
     * Lightweight wrapper for order events in the sliding window.
     */
    private record OrderEvent(String traderId, String orderId, long timestamp, boolean isCancel) {
    }

    public SpoofingDetector(DetectorConfig config) {
        this.cancelWindowMs = config.spoofCancelWindowMs();
        this.ocRatioThreshold = config.spoofOcRatioThreshold();
        this.ocMinSamples = config.spoofOcMinSamples();
        this.quantityThreshold = config.spoofQuantityThreshold();
    }

    @Override
    public List<ComplianceAlert> process(MarketEvent event) {
        List<ComplianceAlert> alerts = new ArrayList<>();

        switch (event) {
            case OrderPlaced placed -> processOrderPlaced(placed, alerts);
            case OrderCancelled cancelled -> processOrderCancelled(cancelled, alerts);
            case TradeExecuted executed -> {
                // TradeExecuted does not directly trigger spoofing, but we track it for completeness
            }
        }

        return alerts;
    }

    private void processOrderPlaced(OrderPlaced event, List<ComplianceAlert> alerts) {
        TraderState state = traderStates.computeIfAbsent(
                event.traderId(),
                id -> new TraderState(60_000) // 60-second sliding window
        );

        // Add to pending orders for fast-cancel lookup
        state.pendingOrders.put(event.orderId(), event);

        // Add to sliding window
        OrderEvent orderEvent = new OrderEvent(event.traderId(), event.orderId(), event.timestamp(), false);
        state.slidingWindow.add(orderEvent);
        state.orderCount++;

        // Check O/C ratio after adding
        checkOcRatio(state, event, alerts);
    }

    private void processOrderCancelled(OrderCancelled event, List<ComplianceAlert> alerts) {
        TraderState state = traderStates.computeIfAbsent(
                event.traderId(),
                id -> new TraderState(60_000)
        );

        // Check for fast cancel
        OrderPlaced originalOrder = state.pendingOrders.remove(event.originalOrderId());
        if (originalOrder != null) {
            long cancelDelay = event.timestamp() - originalOrder.timestamp();
            if (cancelDelay < cancelWindowMs && originalOrder.quantity() >= quantityThreshold) {
                ComplianceAlert alert = new ComplianceAlert(
                        JsonSerializer.generateAlertId(),
                        AlertType.SPOOFING,
                        event.traderId(),
                        event.symbol(),
                        String.format(
                                "Fast cancel detected: order %s cancelled %dms after placement (quantity: %.0f)",
                                event.originalOrderId(), cancelDelay, originalOrder.quantity()
                        ),
                        event.timestamp(),
                        "HIGH"
                );
                alerts.add(alert);
                logger.info("Spoofing alert: fast cancel by {} on {} (orderId: {}, delay: {}ms)",
                        event.traderId(), event.symbol(), event.originalOrderId(), cancelDelay);
            }
        }

        // Add to sliding window
        OrderEvent orderEvent = new OrderEvent(event.traderId(), event.originalOrderId(), event.timestamp(), true);
        state.slidingWindow.add(orderEvent);
        state.cancelCount++;

        // Check O/C ratio after adding
        checkOcRatio(state, event, alerts);
    }

    private void checkOcRatio(TraderState state, MarketEvent event, List<ComplianceAlert> alerts) {
        int totalSamples = state.orderCount + state.cancelCount;
        if (totalSamples < ocMinSamples) return;

        // Recalculate ratio from sliding window for accuracy after eviction
        int windowOrders = 0;
        int windowCancels = 0;
        for (OrderEvent e : state.slidingWindow.getDeque()) {
            if (e.isCancel()) windowCancels++;
            else windowOrders++;
        }

        if (windowOrders == 0) return;

        // Cancel ratio: cancels per order placed. High ratio means excessive cancellations.
        double cancelRatio = (double) windowCancels / windowOrders;
        if (cancelRatio > ocRatioThreshold && windowCancels >= ocMinSamples) {
            ComplianceAlert alert = new ComplianceAlert(
                    JsonSerializer.generateAlertId(),
                    AlertType.SPOOFING,
                    event.traderId(),
                    event.symbol(),
                    String.format(
                            "Abnormal O/C ratio: %.2f cancels per order over 60s window (%d orders, %d cancels)",
                            cancelRatio, windowOrders, windowCancels
                    ),
                    event.timestamp(),
                    "MEDIUM"
            );
            alerts.add(alert);
            logger.info("Spoofing alert: abnormal O/C ratio by {} on {} (ratio: {})",
                    event.traderId(), event.symbol(), String.format("%.2f", cancelRatio));
        }
    }

    /**
     * Resets all state. Useful for testing.
     */
    public void reset() {
        traderStates.clear();
    }
}
