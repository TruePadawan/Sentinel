package com.sentinel;

import com.sentinel.config.DetectorConfig;
import com.sentinel.config.KafkaConfig;
import com.sentinel.consumer.MarketEventConsumer;
import com.sentinel.detector.Detector;
import com.sentinel.detector.DetectorPipeline;
import com.sentinel.detector.SpoofingDetector;
import com.sentinel.detector.VelocityDetector;
import com.sentinel.detector.WashTradeDetector;
import com.sentinel.producer.AlertProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Main entry point for the Sentinel detection engine.
 * Initializes all detectors, starts the Kafka consumer on a Virtual Thread,
 * and registers a shutdown hook for graceful clean-up.
 */
public class SentinelApplication {

    private static final Logger logger = LoggerFactory.getLogger(SentinelApplication.class);

    public static void main(String[] args) {
        logger.info("Starting Sentinel detection engine...");

        // Load configuration
        DetectorConfig detectorConfig = new DetectorConfig();
        KafkaConfig kafkaConfig = new KafkaConfig();

        // Initialize detectors
        List<Detector> detectors = Arrays.asList(
                new SpoofingDetector(detectorConfig),
                new WashTradeDetector(detectorConfig),
                new VelocityDetector(detectorConfig)
        );

        // Initialize alert producer
        AlertProducer alertProducer = new AlertProducer(kafkaConfig);

        // Initialize detector pipeline
        DetectorPipeline pipeline = new DetectorPipeline(detectors, alertProducer);

        // Initialize consumer
        MarketEventConsumer consumer = new MarketEventConsumer(kafkaConfig, pipeline);

        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown signal received. Stopping Sentinel...");
            alertProducer.flush();
            alertProducer.close();
            consumer.close();
            logger.info("Sentinel stopped gracefully. Processed: {}, Errors: {}",
                    consumer.totalProcessed(), consumer.totalErrors());
        }));

        // Start consumer on a Virtual Thread
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(consumer::start);
            logger.info("Sentinel is running. Press Ctrl+C to stop.");

            // Keep the main thread alive
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.info("Main thread interrupted");
        }
    }
}
