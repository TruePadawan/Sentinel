package com.sentinel.detector;

import com.sentinel.model.ComplianceAlert;
import com.sentinel.model.MarketEvent;
import com.sentinel.producer.AlertSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates all compliance detectors, processing each market event
 * through every detector and collecting alerts. Records per-event
 * detection latency for benchmarking.
 */
public class DetectorPipeline {

    private static final Logger logger = LoggerFactory.getLogger(DetectorPipeline.class);

    private final List<Detector> detectors;
    private final AlertSender alertSender;

    // Latency tracking
    private long totalEvents;
    private long totalDetectionTimeMs;

    public DetectorPipeline(List<Detector> detectors, AlertSender alertSender) {
        this.detectors = detectors;
        this.alertSender = alertSender;
        logger.info("DetectorPipeline initialized with {} detectors: {}",
                detectors.size(),
                detectors.stream().map(Detector::name).toList());
    }

    /**
     * Process a single market event through all detectors.
     *
     * @param event the market event to evaluate
     * @return list of all compliance alerts generated
     */
    public List<ComplianceAlert> process(MarketEvent event) {
        long startNanos = System.nanoTime();
        List<ComplianceAlert> allAlerts = new ArrayList<>();

        for (Detector detector : detectors) {
            try {
                List<ComplianceAlert> alerts = detector.process(event);
                allAlerts.addAll(alerts);
            } catch (Exception e) {
                logger.error("Detector {} failed on event {}: {}",
                        detector.name(), event.eventId(), e.getMessage(), e);
            }
        }

        long elapsedNanos = System.nanoTime() - startNanos;
        totalEvents++;
        totalDetectionTimeMs += elapsedNanos / 1_000_000;

        if (!allAlerts.isEmpty()) {
            alertSender.sendAll(allAlerts);
            logger.info("Event {} triggered {} alerts", event.eventId(), allAlerts.size());
        }

        return allAlerts;
    }

    /**
     * Process a batch of market events sequentially.
     *
     * @param events the events to process
     * @return total number of alerts generated
     */
    public int processBatch(List<MarketEvent> events) {
        int alertCount = 0;
        for (MarketEvent event : events) {
            alertCount += process(event).size();
        }
        return alertCount;
    }

    /**
     * Returns the total number of events processed.
     */
    public long totalEvents() {
        return totalEvents;
    }

    /**
     * Returns the average detection time in milliseconds.
     */
    public double averageDetectionTimeMs() {
        return totalEvents == 0 ? 0.0 : (double) totalDetectionTimeMs / totalEvents;
    }

    /**
     * Reset latency tracking statistics.
     */
    public void resetStats() {
        totalEvents = 0;
        totalDetectionTimeMs = 0;
    }
}
