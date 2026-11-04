package com.sentinel.consumer;

import com.sentinel.config.KafkaConfig;
import com.sentinel.detector.DetectorPipeline;
import com.sentinel.model.MarketEvent;
import com.sentinel.util.JsonSerializer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;

/**
 * Kafka consumer that polls the {@code market-events} topic, deserializes
 * JSON payloads, and processes each batch through the {@link DetectorPipeline}.
 * Commits offsets synchronously after each batch.
 */
public class MarketEventConsumer implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(MarketEventConsumer.class);

    private static final String TOPIC = "market-events";

    private final KafkaConsumer<String, String> consumer;
    private final DetectorPipeline pipeline;

    private long totalProcessed;
    private long totalErrors;

    public MarketEventConsumer(KafkaConfig config, DetectorPipeline pipeline) {
        this.pipeline = pipeline;

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, config.consumerGroup());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, config.consumerAutoOffsetReset());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, String.valueOf(config.consumerEnableAutoCommit()));
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, String.valueOf(config.consumerMaxPollRecords()));
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, "3000");
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "10000");
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, "300000");

        this.consumer = new KafkaConsumer<>(props);
        consumer.subscribe(Collections.singletonList(TOPIC));
        logger.info("MarketEventConsumer initialized, subscribed to topic: {}", TOPIC);
    }

    /**
     * Start the consumer poll loop. Blocks until interrupted.
     */
    public void start() {
        logger.info("Consumer poll loop started");
        try {
            while (!Thread.currentThread().isInterrupted()) {
                ConsumerRecords<String, String> records =
                        consumer.poll(Duration.ofMillis(100));

                if (!records.isEmpty()) {
                    processBatch(records);
                    consumer.commitSync();
                }
            }
            logger.info("Consumer interrupted, shutting down");
        } catch (Exception e) {
            logger.error("Consumer poll loop failed: {}", e.getMessage(), e);
        } finally {
            consumer.close();
            logger.info("Consumer closed. Processed: {}, Errors: {}", totalProcessed, totalErrors);
        }
    }

    /**
     * Process a batch of consumer records through the detector pipeline.
     */
    private void processBatch(ConsumerRecords<String, String> records) {
        for (ConsumerRecord<String, String> record : records) {
            try {
                MarketEvent event = JsonSerializer.deserializeEvent(record.value());
                pipeline.process(event);
                totalProcessed++;
            } catch (Exception e) {
                totalErrors++;
                logger.warn("Skipping malformed event at partition {} offset {}: {}",
                        record.partition(), record.offset(), e.getMessage());
            }
        }

        if (totalProcessed % 1000 == 0) {
            logger.info("Processed {} events, {} errors", totalProcessed, totalErrors);
        }
    }

    /**
     * Returns the total number of successfully processed events.
     */
    public long totalProcessed() {
        return totalProcessed;
    }

    /**
     * Returns the total number of failed/deserialization-error events.
     */
    public long totalErrors() {
        return totalErrors;
    }

    @Override
    public void close() {
        consumer.close();
        logger.info("MarketEventConsumer closed");
    }
}
