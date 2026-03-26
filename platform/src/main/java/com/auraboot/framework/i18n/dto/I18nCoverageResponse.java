package com.auraboot.framework.i18n.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Response DTO for i18n translation coverage statistics.
 *
 * @author AuraBoot
 */
@Data
@Builder
public class I18nCoverageResponse {

    /** The locale used as the authoritative key set (e.g. "zh-CN") */
    private String baseLocale;

    /** Total number of translation keys in the base locale */
    private long totalKeys;

    /** Per-locale coverage breakdown */
    private List<LocaleCoverage> locales;

    /**
     * Sample of keys missing in at least one non-base locale.
     * Limited to 50 entries to keep the response small.
     */
    private List<MissingKeyEntry> missingKeys;

    // -------------------------------------------------------------------------

    @Data
    @Builder
    public static class LocaleCoverage {

        /** BCP-47 language tag, e.g. "en-US" */
        private String locale;

        /** Number of keys that have a translation */
        private long translated;

        /** Number of keys absent from this locale */
        private long missing;

        /** Percentage of keys covered, 0.0 – 100.0, rounded to one decimal */
        private double coverage;
    }

    @Data
    @Builder
    public static class MissingKeyEntry {

        /** The i18n key that is missing in one or more locales */
        private String key;

        /** Locales where this key is absent */
        private List<String> missingIn;
    }
}
