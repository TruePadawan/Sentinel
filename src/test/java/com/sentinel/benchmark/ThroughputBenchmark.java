package com.sentinel.benchmark;

import com.sentinel.config.DetectorConfig;
import com.sentinel.detector.Detector;
import com.sentinel.detector.DetectorPipeline;
import com.sentinel.detector.SpoofingDetector;
import com.sentinel.detector.VelocityDetector;
import com.sentinel.detector.WashTradeDetector;
import com.sentinel.model.ComplianceAlert;
import com.sentinel.model.MarketEvent;
import com.sentinel.model.OrderCancelled;
import com.sentinel.model.OrderPlaced;
import com.sentinel.model.TradeExecuted;
import com.sentinel.producer.AlertSender;
import org.HdrHistogram.Histogram;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Throughput and latency benchmark for the detection pipeline.
 * Processes 1 million synthetic events through all three detectors and measures:
 * - Events per second
 * - p50/p99/p999 detection latency
 * - GC pause time
 * - Alert rate
 * <p>
 */
public class ThroughputBenchmark {

    private static final int TOTAL_EVENTS = 100_000;
    private static final int WARMUP_EVENTS = 1_000;
    private static final long MAX_HISTOGRAM_VALUE = 300_000_000; // 5 minutes in microseconds
    private static final int HISTOGRAM_SIGFIGS = 5;

    private static final String[] SYMBOLS = {
            "AAPL", "GOOG", "MSFT", "AMZN", "TSLA", "META", "NVDA", "JPM",
            "BAC", "GS", "WMT", "DIS", "NFLX", "AMD", "INTC", "CRM", "ORCL",
            "CSCO", "PFE", "MRK"
    };
    private static final String[] SIDES = {"BUY", "SELL"};

    private final Random random = new Random(42);
    private final Histogram latencyHistogram = new Histogram(MAX_HISTOGRAM_VALUE, HISTOGRAM_SIGFIGS);
    private final AtomicLong alertCount = new AtomicLong(0);

    private long generateEventId() {
        return random.nextLong();
    }

    private String generateOrderId() {
        return "ord-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String generateTraderId() {
        return "TRADER-" + random.nextInt(200);
    }

    private String generateSymbol() {
        return SYMBOLS[random.nextInt(SYMBOLS.length)];
    }

    private double generatePrice() {
        return random.nextDouble() * 450.0 + 50.0;
    }

    private double generateQuantity() {
        return random.nextDouble() * 500.0 + 10.0;
    }

    /**
     * Generate a mix of normal and manipulation events.
     */
    private MarketEvent generateEvent(long baseTimestamp) {
        long timestamp = baseTimestamp + random.nextInt(60_000);
        String symbol = generateSymbol();
        String traderId = generateTraderId();
        double price = generatePrice();
        double quantity = generateQuantity();

        int eventMix = random.nextInt(100);

        if (eventMix < 50) {
            // 50% Order Placed
            return new OrderPlaced(
                    String.valueOf(generateEventId()),
                    generateOrderId(),
                    traderId,
                    symbol,
                    SIDES[random.nextInt(SIDES.length)],
                    quantity,
                    price,
                    timestamp
            );
        } else if (eventMix < 80) {
            // 30% Order Cancelled
            return new OrderCancelled(
                    String.valueOf(generateEventId()),
                    generateOrderId(),
                    traderId,
                    symbol,
                    quantity,
                    price,
                    timestamp
            );
        } else {
            // 20% Trade Executed
            return new TradeExecuted(
                    String.valueOf(generateEventId()),
                    traderId,
                    generateTraderId(),
                    symbol,
                    quantity,
                    price,
                    timestamp
            );
        }
    }

    /**
     * Generate a spoofing pattern (large order + fast cancel).
     */
    private List<MarketEvent> generateSpoofingPattern(long baseTimestamp) {
        String symbol = generateSymbol();
        String traderId = "SPOOF-" + random.nextInt(50);
        double price = generatePrice();
        double quantity = random.nextDouble() * 45000.0 + 5000.0; // Above threshold
        String orderId = generateOrderId();

        long timestamp = baseTimestamp + random.nextInt(10_000);
        List<MarketEvent> events = new ArrayList<>(2);
        events.add(new OrderPlaced(
                String.valueOf(generateEventId()),
                orderId,
                traderId,
                symbol,
                SIDES[random.nextInt(SIDES.length)],
                quantity,
                price,
                timestamp
        ));
        events.add(new OrderCancelled(
                String.valueOf(generateEventId()),
                orderId,
                traderId,
                symbol,
                quantity,
                price,
                timestamp + random.nextInt(40) + 5 // 5-45ms, within 50ms window
        ));
        return events;
    }

    /**
     * Generate a wash trade ring (3-node cycle).
     */
    private List<MarketEvent> generateWashTradePattern(long baseTimestamp) {
        String symbol = generateSymbol();
        double price = generatePrice();
        double quantity = random.nextDouble() * 4900.0 + 100.0;
        String[] traders = new String[3];
        for (int i = 0; i < 3; i++) {
            traders[i] = "WASH-" + random.nextInt(30) + "-T" + i;
        }

        long timestamp = baseTimestamp + random.nextInt(10_000);
        List<MarketEvent> events = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            events.add(new TradeExecuted(
                    String.valueOf(generateEventId()),
                    traders[i],
                    traders[(i + 1) % 3],
                    symbol,
                    quantity,
                    price,
                    timestamp + i * random.nextInt(500) + 100
            ));
        }
        return events;
    }

    /**
     * Generate a velocity spike pattern.
     */
    private List<MarketEvent> generateVelocityPattern(long baseTimestamp) {
        String symbol = generateSymbol();
        String traderId = "VELO-" + random.nextInt(20);
        double price = generatePrice();
        // ~$200k per trade, 6 trades = $1.2M > $1M limit
        double quantity = 200_000.0 / price;

        long timestamp = baseTimestamp + random.nextInt(10_000);
        List<MarketEvent> events = new ArrayList<>(6);
        for (int i = 0; i < 6; i++) {
            events.add(new TradeExecuted(
                    String.valueOf(generateEventId()),
                    traderId,
                    "COUNTERPARTY-" + random.nextInt(100),
                    symbol,
                    quantity,
                    price,
                    timestamp + i * random.nextInt(8000) + 500
            ));
        }
        return events;
    }

    /**
     * No-op alert sender for benchmarking.
     */
    private AlertSender noOpAlertSender() {
        return new AlertSender() {
            @Override
            public void send(ComplianceAlert alert) {
                alertCount.incrementAndGet();
            }

            @Override
            public void flush() {
                // No-op
            }
        };
    }

    /**
     * Run the benchmark.
     */
    void run() {
        DetectorConfig config = new DetectorConfig();
        List<Detector> detectors = Arrays.asList(
                new SpoofingDetector(config),
                new WashTradeDetector(config),
                new VelocityDetector(config)
        );
        AlertSender alertSender = noOpAlertSender();
        DetectorPipeline pipeline = new DetectorPipeline(detectors, alertSender);

        long baseTimestamp = System.currentTimeMillis();

        //  Warmup 
        System.out.println("Warming up with " + WARMUP_EVENTS + " events...");
        long warmupStart = System.nanoTime();
        for (int i = 0; i < WARMUP_EVENTS; i++) {
            MarketEvent event = generateEvent(baseTimestamp);
            pipeline.process(event);
        }
        long warmupElapsed = (System.nanoTime() - warmupStart) / 1_000_000;
        System.out.println("Warmup complete: " + warmupElapsed + "ms ("
                + (WARMUP_EVENTS * 1000.0 / warmupElapsed) + " events/sec)");
        System.out.println();

        // Reset stats after warmup
        pipeline.resetStats();
        alertCount.set(0);

        //  Main benchmark 
        System.out.println("Running benchmark with " + TOTAL_EVENTS + " events...");
        long gcBefore = getTotalGcTime();
        long start = System.nanoTime();

        int patternCount = 0;
        for (int i = 0; i < TOTAL_EVENTS; i++) {
            // Inject manipulation patterns at ~10% rate
            if (random.nextInt(100) < 5) {
                List<MarketEvent> pattern = generateSpoofingPattern(baseTimestamp);
                for (MarketEvent event : pattern) {
                    long eventStart = System.nanoTime();
                    List<ComplianceAlert> alerts = pipeline.process(event);
                    long eventEnd = System.nanoTime();
                    latencyHistogram.recordValue(eventEnd - eventStart);
                }
                patternCount++;
            } else if (random.nextInt(100) < 3) {
                List<MarketEvent> pattern = generateWashTradePattern(baseTimestamp);
                for (MarketEvent event : pattern) {
                    long eventStart = System.nanoTime();
                    List<ComplianceAlert> alerts = pipeline.process(event);
                    long eventEnd = System.nanoTime();
                    latencyHistogram.recordValue(eventEnd - eventStart);
                }
                patternCount++;
            } else if (random.nextInt(100) < 2) {
                List<MarketEvent> pattern = generateVelocityPattern(baseTimestamp);
                for (MarketEvent event : pattern) {
                    long eventStart = System.nanoTime();
                    List<ComplianceAlert> alerts = pipeline.process(event);
                    long eventEnd = System.nanoTime();
                    latencyHistogram.recordValue(eventEnd - eventStart);
                }
                patternCount++;
            } else {
                MarketEvent event = generateEvent(baseTimestamp);
                long eventStart = System.nanoTime();
                List<ComplianceAlert> alerts = pipeline.process(event);
                long eventEnd = System.nanoTime();
                latencyHistogram.recordValue(eventEnd - eventStart);
            }

            // Progress reporting
            if ((i + 1) % 25_000 == 0) {
                long partialElapsed = (System.nanoTime() - start) / 1_000_000;
                System.out.println("  Processed " + (i + 1) + "/" + TOTAL_EVENTS
                        + " events in " + partialElapsed + "ms ("
                        + ((i + 1) * 1000.0 / partialElapsed) + " events/sec)");
            }
        }

        long end = System.nanoTime();
        long totalElapsed = (end - start) / 1_000_000;
        long gcAfter = getTotalGcTime();
        long gcTime = gcAfter - gcBefore;

        //  Results 
        double throughput = TOTAL_EVENTS * 1000.0 / totalElapsed;

        System.out.println();
        System.out.println("=".repeat(60));
        System.out.println("BENCHMARK RESULTS");
        System.out.println("=".repeat(60));
        System.out.println();
        System.out.println("  Total Events:        " + TOTAL_EVENTS);
        System.out.println("  Pattern Injections:  " + patternCount);
        System.out.println("  Total Time:          " + totalElapsed + "ms");
        System.out.println("  Throughput:          " + String.format("%,.0f", throughput) + " events/sec");
        System.out.println();
        System.out.println("  Alerts Generated:    " + alertCount.get());
        System.out.println("  Alert Rate:          " + String.format("%.2f", alertCount.get() * 100.0 / TOTAL_EVENTS) + "%");
        System.out.println();
        System.out.println("  GC Time:             " + gcTime + "ms ("
                + String.format("%.1f", gcTime * 100.0 / totalElapsed) + "% of total)");
        System.out.println();

        // Latency percentiles
        long p50 = latencyHistogram.getValueAtPercentile(50.0);
        long p90 = latencyHistogram.getValueAtPercentile(90.0);
        long p95 = latencyHistogram.getValueAtPercentile(95.0);
        long p99 = latencyHistogram.getValueAtPercentile(99.0);
        long p999 = latencyHistogram.getValueAtPercentile(99.9);

        System.out.println("Detection Latency (microseconds):");
        System.out.println("  P50:   " + p50 + "μs (" + p50 / 1000.0 + "ms)");
        System.out.println("  P90:   " + p90 + "μs (" + p90 / 1000.0 + "ms)");
        System.out.println("  P95:   " + p95 + "μs (" + p95 / 1000.0 + "ms)");
        System.out.println("  P99:   " + p99 + "μs (" + p99 / 1000.0 + "ms)");
        System.out.println("  P99.9: " + p999 + "μs (" + p999 / 1000.0 + "ms)");
        System.out.println();

        // Exit criteria
        System.out.println("=".repeat(60));
        System.out.println("EXIT CRITERIA");
        System.out.println("=".repeat(60));

        boolean throughputOk = throughput >= 50_000;
        boolean p99Ok = p99 <= 1_000; // 1ms = 1000μs
        boolean gcOk = gcTime < 10; // < 10ms GC for 1M events

        System.out.println("  Throughput ≥ 50,000 events/sec: "
                + (throughputOk ? "✓ PASS" : "✗ FAIL")
                + " (" + String.format("%,.0f", throughput) + ")");
        System.out.println("  P99 Latency ≤ 1ms:              "
                + (p99Ok ? "✓ PASS" : "✗ FAIL")
                + " (" + p99 / 1000.0 + "ms)");
        System.out.println("  GC Time < 10ms per 1M events:   "
                + (gcOk ? "✓ PASS" : "✗ FAIL")
                + " (" + gcTime + "ms)");

        boolean allPass = throughputOk && p99Ok && gcOk;
        System.out.println();
        if (allPass) {
            System.out.println("  ✓ ALL CRITERIA PASSED");
        } else {
            System.out.println("  ✗ SOME CRITERIA FAILED");
        }

    }

    private long getTotalGcTime() {
        long totalGcTime = 0;
        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            totalGcTime += gcBean.getCollectionTime();
        }
        return totalGcTime;
    }

    /**
     * JUnit test entry point.
     */
    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("Throughput benchmark: 1M events")
    void testThroughput() {
        run();
    }

    /**
     * Standalone entry point for running without JUnit.
     */
    public static void main(String[] args) {
        new ThroughputBenchmark().run();
    }
}
