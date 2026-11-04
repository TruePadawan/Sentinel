package com.sentinel.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sentinel.model.ComplianceAlert;
import com.sentinel.model.MarketEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Jackson-based serializer/deserializer for market events and compliance alerts.
 * Configured for polymorphic deserialization of {@link MarketEvent} subtypes via the "type" property.
 */
public class JsonSerializer {

    private static final Logger logger = LoggerFactory.getLogger(JsonSerializer.class);

    private static final ObjectMapper objectMapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
        .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    /**
     * Deserializes a JSON string into a polymorphic MarketEvent.
     * Uses the "type" field to determine the concrete subtype.
     *
     * @param json the JSON payload
     * @return the deserialized MarketEvent
     * @throws RuntimeException if deserialization fails
     */
    public static MarketEvent deserializeEvent(String json) {
        try {
            return objectMapper.readValue(json, MarketEvent.class);
        } catch (JsonProcessingException e) {
            logger.error("Failed to deserialize market event: {}", json, e);
            throw new RuntimeException("Failed to deserialize market event", e);
        }
    }

    /**
     * Serializes a MarketEvent to a JSON string.
     *
     * @param event the event to serialize
     * @return JSON string with the "type" discriminator field
     */
    public static String serializeEvent(MarketEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize market event: {}", event, e);
            throw new RuntimeException("Failed to serialize market event", e);
        }
    }

    /**
     * Serializes a ComplianceAlert to a JSON string.
     *
     * @param alert the alert to serialize
     * @return JSON string
     */
    public static String serializeAlert(ComplianceAlert alert) {
        try {
            return objectMapper.writeValueAsString(alert);
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize compliance alert: {}", alert, e);
            throw new RuntimeException("Failed to serialize compliance alert", e);
        }
    }

    /**
     * Deserializes a JSON string into a ComplianceAlert.
     *
     * @param json the JSON payload
     * @return the deserialized ComplianceAlert
     * @throws RuntimeException if deserialization fails
     */
    public static ComplianceAlert deserializeAlert(String json) {
        try {
            return objectMapper.readValue(json, ComplianceAlert.class);
        } catch (JsonProcessingException e) {
            logger.error("Failed to deserialize compliance alert: {}", json, e);
            throw new RuntimeException("Failed to deserialize compliance alert", e);
        }
    }

    /**
     * Generates a unique alert ID.
     */
    public static String generateAlertId() {
        return "alert-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Returns the shared ObjectMapper instance for custom serialization needs.
     */
    public static ObjectMapper getObjectMapper() {
        return objectMapper;
    }
}
