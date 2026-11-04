package com.sentinel.detector;

import com.sentinel.model.ComplianceAlert;
import com.sentinel.model.MarketEvent;

import java.util.List;

/**
 * Functional interface for a compliance detector.
 * Each detector processes a market event and returns zero or more compliance alerts.
 */
@FunctionalInterface
public interface Detector {

    /**
     * Process a market event and return any generated compliance alerts.
     *
     * @param event the market event to evaluate
     * @return list of compliance alerts; empty list if no violations detected
     */
    List<ComplianceAlert> process(MarketEvent event);

    /**
     * Returns the human-readable name of this detector.
     */
    default String name() {
        return this.getClass().getSimpleName();
    }
}
