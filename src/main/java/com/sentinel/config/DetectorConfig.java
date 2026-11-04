package com.sentinel.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Loads detector threshold configuration from {@code application.properties}.
 * Provides default values if properties are missing.
 */
public class DetectorConfig {

    private final Properties properties;

    public DetectorConfig() {
        this.properties = new Properties();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            if (is != null) {
                properties.load(is);
            }
        } catch (IOException e) {
            // Use defaults if properties file is not found
        }
    }

    public DetectorConfig(Properties props) {
        this.properties = props;
    }

    private double getDouble(String key, double defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) return defaultValue;
        return Double.parseDouble(value.trim());
    }

    private int getInt(String key, int defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) return defaultValue;
        return Integer.parseInt(value.trim());
    }

    private long getLong(String key, long defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) return defaultValue;
        return Long.parseLong(value.trim());
    }

    // Spoofing thresholds
    public long spoofCancelWindowMs() {
        return getLong("spoof.cancel_window_ms", 50);
    }

    public double spoofOcRatioThreshold() {
        return getDouble("spoof.oc_ratio_threshold", 10.0);
    }

    public int spoofOcMinSamples() {
        return getInt("spoof.oc_min_samples", 20);
    }

    public double spoofQuantityThreshold() {
        return getDouble("spoof.quantity_threshold", 1000);
    }

    // Wash trading thresholds
    public long washWindowMs() {
        return getLong("wash.window_ms", 5000);
    }

    public int washMaxCycleLength() {
        return getInt("wash.max_cycle_length", 5);
    }

    // Velocity thresholds
    public double velocityDailyNotionalLimit() {
        return getDouble("velocity.daily_notional_limit", 10_000_000.0);
    }

    public double velocityMinuteNotionalLimit() {
        return getDouble("velocity.minute_notional_limit", 1_000_000.0);
    }
}
