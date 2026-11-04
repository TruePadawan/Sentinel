package com.sentinel.producer;

import com.sentinel.model.ComplianceAlert;

import java.util.List;

/**
 * Interface for publishing compliance alerts.
 * Allows testing with a no-op implementation.
 */
public interface AlertSender {

    /**
     * Publish a single compliance alert.
     *
     * @param alert the alert to publish
     */
    void send(ComplianceAlert alert);

    /**
     * Publish a batch of compliance alerts.
     *
     * @param alerts the alerts to publish
     */
    default void sendAll(List<ComplianceAlert> alerts) {
        for (ComplianceAlert alert : alerts) {
            send(alert);
        }
    }

    /**
     * Flush pending records to ensure delivery.
     */
    void flush();
}
