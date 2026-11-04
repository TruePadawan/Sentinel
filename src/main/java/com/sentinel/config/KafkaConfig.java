package com.sentinel.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Loads Kafka consumer and producer configuration from {@code application.properties}.
 */
public class KafkaConfig {

    private final Properties properties;

    public KafkaConfig() {
        this.properties = new Properties();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            if (is != null) {
                properties.load(is);
            }
        } catch (IOException e) {
            // Use defaults if properties file is not found
        }
    }

    public KafkaConfig(Properties props) {
        this.properties = props;
    }

    private String get(String key, String defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) return defaultValue;
        return value.trim();
    }

    // Consumer properties
    public String bootstrapServers() {
        return get("kafka.bootstrap_servers", "localhost:9092");
    }

    public String consumerGroup() {
        return get("kafka.consumer.group", "sentinel-detection-group");
    }

    public int consumerMaxPollRecords() {
        try {
            return Integer.parseInt(get("kafka.consumer.max_poll", "500"));
        } catch (NumberFormatException e) {
            return 500;
        }
    }

    public String consumerAutoOffsetReset() {
        return get("kafka.consumer.auto_offset_reset", "earliest");
    }

    public boolean consumerEnableAutoCommit() {
        return Boolean.parseBoolean(get("kafka.consumer.enable_auto_commit", "false"));
    }

    // Producer properties
    public String producerAcks() {
        return get("kafka.producer.acks", "1");
    }
}
