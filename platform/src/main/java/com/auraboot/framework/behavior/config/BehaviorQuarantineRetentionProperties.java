package com.auraboot.framework.behavior.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "behavior.quarantine.retention")
public class BehaviorQuarantineRetentionProperties {

    private boolean enabled = true;
    private int days = 30;
    private int batchSize = 1000;
    private long initialDelayMs = 300_000L;
    private long fixedDelayMs = 3_600_000L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getDays() {
        return days;
    }

    public void setDays(int days) {
        this.days = days;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public long getInitialDelayMs() {
        return initialDelayMs;
    }

    public void setInitialDelayMs(long initialDelayMs) {
        this.initialDelayMs = initialDelayMs;
    }

    public long getFixedDelayMs() {
        return fixedDelayMs;
    }

    public void setFixedDelayMs(long fixedDelayMs) {
        this.fixedDelayMs = fixedDelayMs;
    }

    public int effectiveDays() {
        return Math.max(1, days);
    }

    public int effectiveBatchSize() {
        return Math.max(1, batchSize);
    }
}
