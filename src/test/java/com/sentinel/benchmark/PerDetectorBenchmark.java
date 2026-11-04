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

/**
 * Per-detector benchmark that measures each detector in isolation.
 * This gives clean throughput numbers per algorithm without cross-detector interference.
 * <p>
 * Each detector processes 100K events with a deterministic pattern mix.
 * Results are printed to stdout and optionally saved to JSON.
 */
public class PerDetectorBenchmark {

    private static final int EVENTS_PER_DETECTOR = 100_000;
    private static final int WARMUP_EVENTS = 5_000;
    private static final long MAX_HISTOGRAM_VALUE = 300_000_000;
    private static final int HISTOGRAM_SIGFIGS = 5;

    private static final String[] SYMBOLS = {
            "AAPL", "GOOG", "MSFT", "AMZN", "TSLA", "META", "NVDA", "JPM",
            "BAC", "GS", "WMT", "DIS", "NFLX", "AMD", "INTC", "CRM", "ORCL",
            "CSCO", "PFE", "MRK"
    };
    private static final String[] SIDES = {"BUY", "SELL"};

    private final Random random = new Random(42);

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

    private MarketEvent generateEvent(long baseTimestamp) {
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
     * Benchmark a single detector in isolation.
     */
    public DetectorResult benchmarkDetector(String name, com.sentinel.detector.Detector detector, int events) {
        long baseTimestamp = System.currentTimeMillis();

        // Warmup
        for (int i = 0; i < WARMUP_EVENTS; i++) {
            detector.process(generateEvent(baseTimestamp));
        }

        Histogram histogram = new Histogram(MAX_HISTOGRAM_VALUE, HISTOGRAM_SIGFIGS);
        long gcBefore = getTotalGcTime();
        long start = System.nanoTime();
        int alertCount = 0;

        for (int i = 0; i < events; i++) {
            MarketEvent event = generateEvent(baseTimestamp);
            long eventStart = System.nanoTime();
            List<ComplianceAlert> alerts = detector.process(event);
            long eventEnd = System.nanoTime();
            histogram.recordValue(eventEnd - eventStart);
            alertCount += alerts.size();
        }

        long totalElapsedMs = (System.nanoTime() - start) / 1_000_000;
        long gcTimeMs = getTotalGcTime() - gcBefore;
        double throughput = events * 1000.0 / totalElapsedMs;

        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();

        return new DetectorResult(
                name, events, totalElapsedMs, throughput,
                alertCount, gcTimeMs,
                heapUsage.getUsed() / (1024 * 1024),
                histogram
        );
    }

    private static String padRight(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }

    private long getTotalGcTime() {
        long total = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += gc.getCollectionTime();
        }
        return total;
    }

    public void run() {
        DetectorConfig config = new DetectorConfig();

        System.out.println("=".repeat(70));
        System.out.println("PER-DETECTOR BENCHMARK");
        System.out.println("=".repeat(70));
        System.out.println("  Events per detector: " + String.format("%,d", EVENTS_PER_DETECTOR));
        System.out.println();

        List<DetectorResult> results = new ArrayList<>();

        // SpoofingDetector
        System.out.println("Benchmarking SpoofingDetector...");
        results.add(benchmarkDetector("SpoofingDetector", new SpoofingDetector(config), EVENTS_PER_DETECTOR));

        // WashTradeDetector
        System.out.println("Benchmarking WashTradeDetector...");
        results.add(benchmarkDetector("WashTradeDetector", new WashTradeDetector(config), EVENTS_PER_DETECTOR));

        // VelocityDetector
        System.out.println("Benchmarking VelocityDetector...");
        results.add(benchmarkDetector("VelocityDetector", new VelocityDetector(config), EVENTS_PER_DETECTOR));

        // Print results
        System.out.println();
        System.out.println("=".repeat(70));
        System.out.println("RESULTS");
        System.out.println("=".repeat(70));
        System.out.println();

        System.out.printf("  %-22s %12s %12s %10s %10s%n", "Detector", "Throughput", "P50 (μs)", "P99 (μs)", "Alerts");
        System.out.println("  " + "-".repeat(68));

        for (DetectorResult r : results) {
            long p50 = r.histogram.getValueAtPercentile(50.0);
            long p99 = r.histogram.getValueAtPercentile(99.0);
            String throughput = String.format("%,.0f", r.throughput);
            System.out.println("  " + r.name + "  " + throughput + " evts/s  "
                    + p50 + "us  " + p99 + "us  " + r.alertCount);
        }

        System.out.println();
        System.out.println("DETAILED LATENCY (microseconds)");
        System.out.println();

        for (DetectorResult r : results) {
            long p50 = r.histogram.getValueAtPercentile(50.0);
            long p90 = r.histogram.getValueAtPercentile(90.0);
            long p95 = r.histogram.getValueAtPercentile(95.0);
            long p99 = r.histogram.getValueAtPercentile(99.0);
            long p999 = r.histogram.getValueAtPercentile(99.9);
            System.out.println("  " + r.name + ":");
            System.out.println("    P50:   " + String.format("%,d", p50) + "μs (" + String.format("%.3f", p50 / 1000.0) + "ms)");
            System.out.println("    P90:   " + String.format("%,d", p90) + "μs (" + String.format("%.3f", p90 / 1000.0) + "ms)");
            System.out.println("    P95:   " + String.format("%,d", p95) + "μs (" + String.format("%.3f", p95 / 1000.0) + "ms)");
            System.out.println("    P99:   " + String.format("%,d", p99) + "μs (" + String.format("%.3f", p99 / 1000.0) + "ms)");
            System.out.println("    P99.9: " + String.format("%,d", p999) + "μs (" + String.format("%.3f", p999 / 1000.0) + "ms)");
            System.out.println("    Time:  " + r.totalElapsedMs + "ms, GC: " + r.gcTimeMs + "ms, Heap: " + r.heapUsedMb + "MB");
            System.out.println();
        }

        // Save JSON
        try {
            saveToJson(results, Path.of("benchmark_per_detector.json"));
        } catch (IOException e) {
            System.err.println("Failed to save JSON: " + e.getMessage());
        }
    }

    public void saveToJson(List<DetectorResult> results, Path path) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"benchmark\": \"per-detector\",\n  \"events_per_detector\": ").append(EVENTS_PER_DETECTOR).append(",\n  \"detectors\": [\n");
        for (int i = 0; i < results.size(); i++) {
            DetectorResult r = results.get(i);
            long p50 = r.histogram.getValueAtPercentile(50.0);
            long p99 = r.histogram.getValueAtPercentile(99.0);
            sb.append("    {\n");
            sb.append("      \"name\": \"").append(r.name).append("\",\n");
            sb.append("      \"throughput\": ").append(String.format("%.0f", r.throughput)).append(",\n");
            sb.append("      \"total_time_ms\": ").append(r.totalElapsedMs).append(",\n");
            sb.append("      \"alert_count\": ").append(r.alertCount).append(",\n");
            sb.append("      \"gc_time_ms\": ").append(r.gcTimeMs).append(",\n");
            sb.append("      \"heap_used_mb\": ").append(r.heapUsedMb).append(",\n");
            sb.append("      \"latency_p50_us\": ").append(p50).append(",\n");
            sb.append("      \"latency_p99_us\": ").append(p99).append("\n");
            sb.append("    }").append(i < results.size() - 1 ? "," : "").append("\n");
        }
        sb.append("  ]\n}\n");
        Files.writeString(path, sb.toString());
        System.out.println("  Results saved to: " + path);
    }

    public static class DetectorResult {
        final String name;
        final int events;
        final long totalElapsedMs;
        final double throughput;
        final int alertCount;
        final long gcTimeMs;
        final long heapUsedMb;
        final Histogram histogram;

        DetectorResult(String name, int events, long totalElapsedMs, double throughput,
                       int alertCount, long gcTimeMs, long heapUsedMb, Histogram histogram) {
            this.name = name;
            this.events = events;
            this.totalElapsedMs = totalElapsedMs;
            this.throughput = throughput;
            this.alertCount = alertCount;
            this.gcTimeMs = gcTimeMs;
            this.heapUsedMb = heapUsedMb;
            this.histogram = histogram;
        }
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("Per-detector benchmark: 100K events each")
    void testPerDetector() {
        run();
    }

    public static void main(String[] args) {
        new PerDetectorBenchmark().run();
    }
}
