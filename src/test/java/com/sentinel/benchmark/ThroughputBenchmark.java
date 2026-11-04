package com.sentinel.benchmark;

import com.sentinel.config.DetectorConfig;
import com.sentinel.detector.SpoofingDetector;
import com.sentinel.detector.VelocityDetector;
import com.sentinel.detector.WashTradeDetector;
import com.sentinel.model.ComplianceAlert;
import com.sentinel.model.MarketEvent;
import com.sentinel.model.OrderCancelled;
import com.sentinel.model.OrderPlaced;
import com.sentinel.model.TradeExecuted;
import org.HdrHistogram.Histogram;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Throughput and latency benchmark for the detection pipeline.
 * Processes 1 million synthetic events through all three detectors and measures:
 * - Total and per-detector events per second
 * - p50/p99/p999 detection latency per detector and combined
 * - GC pause time and heap memory footprint
 * - Alert rate
 * <p>
 * Results are printed to stdout and optionally saved to a JSON file.
 */
public class ThroughputBenchmark {

    private static final int TOTAL_EVENTS = 100_000;
    private static final int WARMUP_EVENTS = 5_000;
    private static final long MAX_HISTOGRAM_VALUE = 300_000_000; // 5 minutes in microseconds
    private static final int HISTOGRAM_SIGFIGS = 5;

    // Deterministic pattern mix: 10% manipulation events
    private static final double SPOOFING_RATE = 0.05; // 5%
    private static final double WASH_TRADE_RATE = 0.03; // 3%
    private static final double VELOCITY_RATE = 0.02; // 2%

    private static final String[] SYMBOLS = {
            "AAPL", "GOOG", "MSFT", "AMZN", "TSLA", "META", "NVDA", "JPM",
            "BAC", "GS", "WMT", "DIS", "NFLX", "AMD", "INTC", "CRM", "ORCL",
            "CSCO", "PFE", "MRK"
    };
    private static final String[] SIDES = {"BUY", "SELL"};

    private final Random random = new Random(42);
    private final AtomicLong alertCount = new AtomicLong(0);

    // Per-detector histograms
    private final Histogram spoofingLatency = new Histogram(MAX_HISTOGRAM_VALUE, HISTOGRAM_SIGFIGS);
    private final Histogram washTradeLatency = new Histogram(MAX_HISTOGRAM_VALUE, HISTOGRAM_SIGFIGS);
    private final Histogram velocityLatency = new Histogram(MAX_HISTOGRAM_VALUE, HISTOGRAM_SIGFIGS);
    private final Histogram combinedLatency = new Histogram(MAX_HISTOGRAM_VALUE, HISTOGRAM_SIGFIGS);

    // Per-detector timing
    private long spoofingTotalNs = 0;
    private long washTradeTotalNs = 0;
    private long velocityTotalNs = 0;

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
     * Generate a normal market event with a deterministic mix.
     */
    private MarketEvent generateNormalEvent(long baseTimestamp) {
        long timestamp = baseTimestamp + random.nextInt(60_000);
        String symbol = generateSymbol();
        String traderId = generateTraderId();
        double price = generatePrice();
        double quantity = generateQuantity();
        String eventId = UUID.randomUUID().toString().substring(0, 8);

        int mix = random.nextInt(100);
        if (mix < 50) {
            return new OrderPlaced(
                    eventId, generateOrderId(), traderId, symbol,
                    SIDES[random.nextInt(SIDES.length)], quantity, price, timestamp
            );
        } else if (mix < 80) {
            return new OrderCancelled(
                    eventId, generateOrderId(), traderId, symbol,
                    quantity, price, timestamp
            );
        } else {
            return new TradeExecuted(
                    eventId, traderId, generateTraderId(), symbol,
                    quantity, price, timestamp
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
        double quantity = random.nextDouble() * 45000.0 + 5000.0;
        String orderId = generateOrderId();
        long timestamp = baseTimestamp + random.nextInt(10_000);

        List<MarketEvent> events = new ArrayList<>(2);
        events.add(new OrderPlaced(
                UUID.randomUUID().toString().substring(0, 8), orderId, traderId, symbol,
                SIDES[random.nextInt(SIDES.length)], quantity, price, timestamp
        ));
        events.add(new OrderCancelled(
                UUID.randomUUID().toString().substring(0, 8), orderId, traderId, symbol,
                quantity, price, timestamp + random.nextInt(40) + 5
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
                    UUID.randomUUID().toString().substring(0, 8),
                    traders[i], traders[(i + 1) % 3], symbol,
                    quantity, price, timestamp + i * random.nextInt(500) + 100
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
        double quantity = 200_000.0 / price;

        long timestamp = baseTimestamp + random.nextInt(10_000);
        List<MarketEvent> events = new ArrayList<>(6);
        for (int i = 0; i < 6; i++) {
            events.add(new TradeExecuted(
                    UUID.randomUUID().toString().substring(0, 8),
                    traderId, "COUNTERPARTY-" + random.nextInt(100), symbol,
                    quantity, price, timestamp + i * random.nextInt(8000) + 500
            ));
        }
        return events;
    }

    /**
     * Run the benchmark.
     */
    public BenchmarkResult run() {
        DetectorConfig config = new DetectorConfig();
        SpoofingDetector spoofingDetector = new SpoofingDetector(config);
        WashTradeDetector washTradeDetector = new WashTradeDetector(config);
        VelocityDetector velocityDetector = new VelocityDetector(config);

        long baseTimestamp = System.currentTimeMillis();

        // Warmup
        System.out.println("Warming up with " + WARMUP_EVENTS + " events...");
        long warmupStart = System.nanoTime();
        for (int i = 0; i < WARMUP_EVENTS; i++) {
            MarketEvent event = generateNormalEvent(baseTimestamp);
            spoofingDetector.process(event);
            washTradeDetector.process(event);
            velocityDetector.process(event);
        }
        long warmupElapsed = (System.nanoTime() - warmupStart) / 1_000_000;
        System.out.println("Warmup complete: " + warmupElapsed + "ms ("
                + String.format("%,.0f", WARMUP_EVENTS * 1000.0 / warmupElapsed) + " events/sec)");
        System.out.println();

        // Main benchmark
        System.out.println("Running benchmark with " + String.format("%,d", TOTAL_EVENTS) + " events...");
        System.out.println("  Pattern mix: spoofing " + String.format("%.0f", SPOOFING_RATE * 100) + "%, wash trade "
                + String.format("%.0f", WASH_TRADE_RATE * 100) + "%, velocity "
                + String.format("%.0f", VELOCITY_RATE * 100) + "%");
        System.out.println();

        long gcBefore = getTotalGcTime();
        long start = System.nanoTime();

        int spoofingPatterns = 0;
        int washTradePatterns = 0;
        int velocityPatterns = 0;
        int normalEvents = 0;

        for (int i = 0; i < TOTAL_EVENTS; i++) {
            double r = random.nextDouble();

            List<MarketEvent> events;
            if (r < SPOOFING_RATE) {
                events = generateSpoofingPattern(baseTimestamp);
                spoofingPatterns++;
            } else if (r < SPOOFING_RATE + WASH_TRADE_RATE) {
                events = generateWashTradePattern(baseTimestamp);
                washTradePatterns++;
            } else if (r < SPOOFING_RATE + WASH_TRADE_RATE + VELOCITY_RATE) {
                events = generateVelocityPattern(baseTimestamp);
                velocityPatterns++;
            } else {
                events = List.of(generateNormalEvent(baseTimestamp));
            }

            for (MarketEvent event : events) {
                long eventStart = System.nanoTime();

                long s0 = System.nanoTime();
                List<ComplianceAlert> sAlerts = spoofingDetector.process(event);
                long sElapsed = System.nanoTime() - s0;
                spoofingTotalNs += sElapsed;
                alertCount.addAndGet(sAlerts.size());

                long w0 = System.nanoTime();
                List<ComplianceAlert> wAlerts = washTradeDetector.process(event);
                long wElapsed = System.nanoTime() - w0;
                washTradeTotalNs += wElapsed;
                alertCount.addAndGet(wAlerts.size());

                long v0 = System.nanoTime();
                List<ComplianceAlert> vAlerts = velocityDetector.process(event);
                long vElapsed = System.nanoTime() - v0;
                velocityTotalNs += vElapsed;
                alertCount.addAndGet(vAlerts.size());

                long eventEnd = System.nanoTime();
                combinedLatency.recordValue(eventEnd - eventStart);
                spoofingLatency.recordValue(sElapsed);
                washTradeLatency.recordValue(wElapsed);
                velocityLatency.recordValue(vElapsed);
            }

            // Progress reporting
            if ((i + 1) % 25_000 == 0) {
                long partialElapsed = (System.nanoTime() - start) / 1_000_000;
                System.out.println("  Processed " + String.format("%,d", i + 1) + "/"
                        + String.format("%,d", TOTAL_EVENTS) + " events in "
                        + partialElapsed + "ms ("
                        + String.format("%,.0f", (i + 1) * 1000.0 / partialElapsed) + " events/sec)");
            }
        }

        long end = System.nanoTime();
        long totalElapsedMs = (end - start) / 1_000_000;
        long gcAfter = getTotalGcTime();
        long gcTimeMs = gcAfter - gcBefore;

        // Memory measurement
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        long heapUsedMb = heapUsage.getUsed() / (1024 * 1024);
        long heapCommittedMb = heapUsage.getCommitted() / (1024 * 1024);

        // Compute results
        double throughput = TOTAL_EVENTS * 1000.0 / totalElapsedMs;
        double spoofingThroughput = TOTAL_EVENTS * 1000.0 / (spoofingTotalNs / 1_000_000.0);
        double washTradeThroughput = TOTAL_EVENTS * 1000.0 / (washTradeTotalNs / 1_000_000.0);
        double velocityThroughput = TOTAL_EVENTS * 1000.0 / (velocityTotalNs / 1_000_000.0);

        BenchmarkResult result = new BenchmarkResult(
                TOTAL_EVENTS, spoofingPatterns, washTradePatterns, velocityPatterns,
                totalElapsedMs, throughput,
                spoofingThroughput, washTradeThroughput, velocityThroughput,
                spoofingTotalNs / 1_000_000L, washTradeTotalNs / 1_000_000L, velocityTotalNs / 1_000_000L,
                (int) alertCount.get(),
                gcTimeMs,
                heapUsedMb, heapCommittedMb,
                combinedLatency, spoofingLatency, washTradeLatency, velocityLatency
        );

        // Print results
        printResults(result);
        return result;
    }

    private void printResults(BenchmarkResult r) {
        System.out.println();
        System.out.println("=".repeat(70));
        System.out.println("BENCHMARK RESULTS");
        System.out.println("=".repeat(70));
        System.out.println();
        System.out.println("  Total Events:        " + String.format("%,d", r.totalEvents));
        System.out.println("  Pattern Injections:  " + String.format("%,d", r.spoofingPatterns + r.washTradePatterns + r.velocityPatterns));
        System.out.println("    Spoofing:          " + String.format("%,d", r.spoofingPatterns));
        System.out.println("    Wash Trade:        " + String.format("%,d", r.washTradePatterns));
        System.out.println("    Velocity:          " + String.format("%,d", r.velocityPatterns));
        System.out.println("  Total Time:          " + String.format("%,d", r.totalElapsedMs) + "ms");
        System.out.println();

        System.out.println("THROUGHPUT");
        System.out.println("  Combined:            " + String.format("%,.0f", r.throughput) + " events/sec");
        System.out.println("  SpoofingDetector:    " + String.format("%,.0f", r.spoofingThroughput) + " events/sec");
        System.out.println("  WashTradeDetector:   " + String.format("%,.0f", r.washTradeThroughput) + " events/sec");
        System.out.println("  VelocityDetector:    " + String.format("%,.0f", r.velocityThroughput) + " events/sec");
        System.out.println();

        System.out.println("DETECTOR TIME BREAKDOWN");
        System.out.println("  Spoofing:            " + String.format("%,d", r.spoofingTimeMs) + "ms ("
                + String.format("%.1f", r.spoofingTimeMs * 100.0 / r.totalElapsedMs) + "% of total)");
        System.out.println("  WashTrade:           " + String.format("%,d", r.washTradeTimeMs) + "ms ("
                + String.format("%.1f", r.washTradeTimeMs * 100.0 / r.totalElapsedMs) + "% of total)");
        System.out.println("  Velocity:            " + String.format("%,d", r.velocityTimeMs) + "ms ("
                + String.format("%.1f", r.velocityTimeMs * 100.0 / r.totalElapsedMs) + "% of total)");
        System.out.println();

        System.out.println("ALERTS");
        System.out.println("  Generated:           " + String.format("%,d", r.alertCount));
        System.out.println("  Alert Rate:          " + String.format("%.4f", r.alertCount * 100.0 / r.totalEvents) + "%");
        System.out.println();

        System.out.println("MEMORY");
        System.out.println("  GC Time:             " + String.format("%,d", r.gcTimeMs) + "ms ("
                + String.format("%.1f", r.gcTimeMs * 100.0 / r.totalElapsedMs) + "% of total)");
        System.out.println("  Heap Used:           " + String.format("%,d", r.heapUsedMb) + " MB");
        System.out.println("  Heap Committed:      " + String.format("%,d", r.heapCommittedMb) + " MB");
        System.out.println();

        // Combined latency percentiles
        long cP50 = r.combinedLatency.getValueAtPercentile(50.0);
        long cP90 = r.combinedLatency.getValueAtPercentile(90.0);
        long cP95 = r.combinedLatency.getValueAtPercentile(95.0);
        long cP99 = r.combinedLatency.getValueAtPercentile(99.0);
        long cP999 = r.combinedLatency.getValueAtPercentile(99.9);

        System.out.println("COMBINED LATENCY (microseconds)");
        System.out.println("  P50:   " + String.format("%,d", cP50) + "μs (" + String.format("%.3f", cP50 / 1000.0) + "ms)");
        System.out.println("  P90:   " + String.format("%,d", cP90) + "μs (" + String.format("%.3f", cP90 / 1000.0) + "ms)");
        System.out.println("  P95:   " + String.format("%,d", cP95) + "μs (" + String.format("%.3f", cP95 / 1000.0) + "ms)");
        System.out.println("  P99:   " + String.format("%,d", cP99) + "μs (" + String.format("%.3f", cP99 / 1000.0) + "ms)");
        System.out.println("  P99.9: " + String.format("%,d", cP999) + "μs (" + String.format("%.3f", cP999 / 1000.0) + "ms)");
        System.out.println();

        // Per-detector latency
        System.out.println("PER-DETECTOR LATENCY (microseconds)");
        System.out.println();
        printLatencyTable("SpoofingDetector", r.spoofingLatency);
        printLatencyTable("WashTradeDetector", r.washTradeLatency);
        printLatencyTable("VelocityDetector", r.velocityLatency);

        // Exit criteria
        System.out.println();
        System.out.println("=".repeat(70));
        System.out.println("EXIT CRITERIA");
        System.out.println("=".repeat(70));

        boolean throughputOk = r.throughput >= 50_000;
        boolean p99Ok = cP99 <= 1_000;
        boolean gcOk = r.gcTimeMs < 50; // < 50ms GC

        System.out.println("  Throughput >= 50,000 events/sec: "
                + (throughputOk ? "PASS" : "FAIL")
                + " (" + String.format("%,.0f", r.throughput) + ")");
        System.out.println("  P99 Latency <= 1ms:              "
                + (p99Ok ? "PASS" : "FAIL")
                + " (" + String.format("%.3f", cP99 / 1000.0) + "ms)");
        System.out.println("  GC Time < 50ms:                  "
                + (gcOk ? "PASS" : "FAIL")
                + " (" + r.gcTimeMs + "ms)");

        System.out.println();
        if (throughputOk && p99Ok && gcOk) {
            System.out.println("  ALL CRITERIA PASSED");
        } else {
            System.out.println("  SOME CRITERIA FAILED");
        }
    }

    private void printLatencyTable(String name, Histogram histogram) {
        long p50 = histogram.getValueAtPercentile(50.0);
        long p99 = histogram.getValueAtPercentile(99.0);
        long p999 = histogram.getValueAtPercentile(99.9);
        System.out.println("  " + name + ":");
        System.out.println("    P50:   " + String.format("%,d", p50) + "μs");
        System.out.println("    P99:   " + String.format("%,d", p99) + "μs");
        System.out.println("    P99.9: " + String.format("%,d", p999) + "μs");
        System.out.println();
    }

    /**
     * Save benchmark results to a JSON file for CV documentation.
     */
    public void saveToJson(BenchmarkResult r, Path outputPath) throws IOException {
        long cP50 = r.combinedLatency.getValueAtPercentile(50.0);
        long cP90 = r.combinedLatency.getValueAtPercentile(90.0);
        long cP95 = r.combinedLatency.getValueAtPercentile(95.0);
        long cP99 = r.combinedLatency.getValueAtPercentile(99.0);
        long cP999 = r.combinedLatency.getValueAtPercentile(99.9);

        long sP50 = r.spoofingLatency.getValueAtPercentile(50.0);
        long sP99 = r.spoofingLatency.getValueAtPercentile(99.0);
        long wP50 = r.washTradeLatency.getValueAtPercentile(50.0);
        long wP99 = r.washTradeLatency.getValueAtPercentile(99.0);
        long vP50 = r.velocityLatency.getValueAtPercentile(50.0);
        long vP99 = r.velocityLatency.getValueAtPercentile(99.0);

        String json = "{\n"
                + "  \"benchmark\": {\n"
                + "    \"total_events\": " + r.totalEvents + ",\n"
                + "    \"total_time_ms\": " + r.totalElapsedMs + ",\n"
                + "    \"throughput_events_per_sec\": " + String.format("%.0f", r.throughput) + ",\n"
                + "    \"alert_count\": " + r.alertCount + ",\n"
                + "    \"alert_rate_percent\": " + String.format("%.4f", r.alertCount * 100.0 / r.totalEvents) + ",\n"
                + "    \"gc_time_ms\": " + r.gcTimeMs + ",\n"
                + "    \"heap_used_mb\": " + r.heapUsedMb + ",\n"
                + "    \"heap_committed_mb\": " + r.heapCommittedMb + "\n"
                + "  },\n"
                + "  \"throughput_per_detector\": {\n"
                + "    \"spoofing\": " + String.format("%.0f", r.spoofingThroughput) + ",\n"
                + "    \"wash_trade\": " + String.format("%.0f", r.washTradeThroughput) + ",\n"
                + "    \"velocity\": " + String.format("%.0f", r.velocityThroughput) + "\n"
                + "  },\n"
                + "  \"detector_time_ms\": {\n"
                + "    \"spoofing\": " + r.spoofingTimeMs + ",\n"
                + "    \"wash_trade\": " + r.washTradeTimeMs + ",\n"
                + "    \"velocity\": " + r.velocityTimeMs + "\n"
                + "  },\n"
                + "  \"latency_combined_microseconds\": {\n"
                + "    \"p50\": " + cP50 + ",\n"
                + "    \"p90\": " + cP90 + ",\n"
                + "    \"p95\": " + cP95 + ",\n"
                + "    \"p99\": " + cP99 + ",\n"
                + "    \"p999\": " + cP999 + "\n"
                + "  },\n"
                + "  \"latency_per_detector_microseconds\": {\n"
                + "    \"spoofing\": { \"p50\": " + sP50 + ", \"p99\": " + sP99 + " },\n"
                + "    \"wash_trade\": { \"p50\": " + wP50 + ", \"p99\": " + wP99 + " },\n"
                + "    \"velocity\": { \"p50\": " + vP50 + ", \"p99\": " + vP99 + " }\n"
                + "  }\n"
                + "}\n";

        Files.writeString(outputPath, json);
        System.out.println("  Results saved to: " + outputPath);
    }

    private long getTotalGcTime() {
        long totalGcTime = 0;
        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            totalGcTime += gcBean.getCollectionTime();
        }
        return totalGcTime;
    }

    /**
     * Benchmark result record.
     */
    public static class BenchmarkResult {
        final int totalEvents;
        final int spoofingPatterns;
        final int washTradePatterns;
        final int velocityPatterns;
        final long totalElapsedMs;
        final double throughput;
        final double spoofingThroughput;
        final double washTradeThroughput;
        final double velocityThroughput;
        final long spoofingTimeMs;
        final long washTradeTimeMs;
        final long velocityTimeMs;
        final int alertCount;
        final long gcTimeMs;
        final long heapUsedMb;
        final long heapCommittedMb;
        final Histogram combinedLatency;
        final Histogram spoofingLatency;
        final Histogram washTradeLatency;
        final Histogram velocityLatency;

        BenchmarkResult(int totalEvents, int spoofingPatterns, int washTradePatterns, int velocityPatterns,
                        long totalElapsedMs, double throughput,
                        double spoofingThroughput, double washTradeThroughput, double velocityThroughput,
                        long spoofingTimeMs, long washTradeTimeMs, long velocityTimeMs,
                        int alertCount, long gcTimeMs, long heapUsedMb, long heapCommittedMb,
                        Histogram combinedLatency, Histogram spoofingLatency,
                        Histogram washTradeLatency, Histogram velocityLatency) {
            this.totalEvents = totalEvents;
            this.spoofingPatterns = spoofingPatterns;
            this.washTradePatterns = washTradePatterns;
            this.velocityPatterns = velocityPatterns;
            this.totalElapsedMs = totalElapsedMs;
            this.throughput = throughput;
            this.spoofingThroughput = spoofingThroughput;
            this.washTradeThroughput = washTradeThroughput;
            this.velocityThroughput = velocityThroughput;
            this.spoofingTimeMs = spoofingTimeMs;
            this.washTradeTimeMs = washTradeTimeMs;
            this.velocityTimeMs = velocityTimeMs;
            this.alertCount = alertCount;
            this.gcTimeMs = gcTimeMs;
            this.heapUsedMb = heapUsedMb;
            this.heapCommittedMb = heapCommittedMb;
            this.combinedLatency = combinedLatency;
            this.spoofingLatency = spoofingLatency;
            this.washTradeLatency = washTradeLatency;
            this.velocityLatency = velocityLatency;
        }
    }

    /**
     * JUnit test entry point.
     */
    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("Throughput benchmark: 100K events")
    void testThroughput() {
        run();
    }

    /**
     * Standalone entry point for running without JUnit.
     * Usage: mvn test -Dtest=ThroughputBenchmark -DargLine="--benchmark-json=benchmark_results.json"
     * Or: java -cp target/test-classes:target/classes:deps ThroughputBenchmark [--json output.json]
     */
    public static void main(String[] args) {
        ThroughputBenchmark benchmark = new ThroughputBenchmark();
        BenchmarkResult result = benchmark.run();

        // Check for --json flag
        for (int i = 0; i < args.length; i++) {
            if ("--json".equals(args[i]) && i + 1 < args.length) {
                try {
                    benchmark.saveToJson(result, Path.of(args[i + 1]));
                } catch (IOException e) {
                    System.err.println("Failed to save JSON: " + e.getMessage());
                }
                break;
            }
        }
    }
}
