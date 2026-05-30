package com.auraboot.framework.connector.normalization;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * In-process {@link NormalizationEngine} implementation for the L3 data-cleansing
 * layer (PRD 16 §semantic.yml / strategy patch §三 hybrid 图).
 *
 * <p>Applies field-level rules from a {@link NormalizationConfig} to a lazy
 * {@code Stream<Map<String,Object>>} of raw SaaS connector records. The stream
 * transformation is itself lazy — no records are buffered beyond the window
 * needed to apply a single rule.
 *
 * <h2>Rule semantics</h2>
 * <dl>
 *   <dt>TIMESTAMP</dt>
 *   <dd>Converts timestamp representations between {@code iso8601},
 *       {@code epoch_millis}, and {@code epoch_seconds}. Non-parseable values
 *       are left unchanged with a WARN log — not thrown — to preserve the
 *       pipeline under dirty data.</dd>
 *   <dt>NUMERIC_UNIT</dt>
 *   <dd>Scales a numeric value using well-known unit pairs ({@code dollars}↔{@code cents},
 *       {@code cents}↔{@code dollars}) or an explicit {@code multiplier} param.
 *       Uses {@link BigDecimal} arithmetic to avoid floating-point drift.</dd>
 *   <dt>ENUM_MAP</dt>
 *   <dd>Looks up the raw string value in the {@code mapping} param map. If no entry
 *       is found the original value is passed through unchanged (open-world).</dd>
 *   <dt>RENAME</dt>
 *   <dd>Copies the value from {@code source} key to {@code target} key and removes
 *       the source key. If {@code target} already exists it is overwritten.</dd>
 * </dl>
 *
 * <h2>Flink reservation</h2>
 * <p>A {@code FlinkNormalizationEngine} should replace this implementation when
 * a customer scenario requires sub-second SLA with complex windowing (see
 * {@code 15-数据平台战略决策补丁.md} §六 for trigger criteria). The SPI interface is
 * binary-compatible with both execution models.
 *
 * @since 5.3.0
 */
@Component
public class InMemoryNormalizationEngine implements NormalizationEngine {

    private static final Logger log = LoggerFactory.getLogger(InMemoryNormalizationEngine.class);

    // ---------------------------------------------------------------------------
    // Known unit-conversion multipliers (from → to)
    // ---------------------------------------------------------------------------

    private static final Map<String, Map<String, BigDecimal>> UNIT_MULTIPLIERS;

    static {
        Map<String, Map<String, BigDecimal>> m = new HashMap<>();
        m.put("dollars", Map.of("cents", new BigDecimal("100")));
        m.put("cents",   Map.of("dollars", new BigDecimal("0.01")));
        // Extend here as more unit pairs are needed; or supply an explicit
        // 'multiplier' param for ad-hoc ratios.
        UNIT_MULTIPLIERS = Map.copyOf(m);
    }

    // ---------------------------------------------------------------------------
    // NormalizationEngine
    // ---------------------------------------------------------------------------

    @Override
    public Stream<Map<String, Object>> apply(NormalizationConfig cfg,
                                             Stream<Map<String, Object>> source) {
        List<NormalizationConfig.FieldRule> fields = cfg.fields();
        if (fields.isEmpty()) {
            return source;
        }
        return source.map(record -> applyRules(fields, record));
    }

    // ---------------------------------------------------------------------------
    // Record-level rule application
    // ---------------------------------------------------------------------------

    /**
     * Apply all rules to one record, returning a new map.
     *
     * <p>Rules are applied in declaration order on a working copy of the record.
     * Fields not mentioned in any rule are copied unchanged (open-world assumption).
     */
    private Map<String, Object> applyRules(List<NormalizationConfig.FieldRule> fields,
                                            Map<String, Object> record) {
        // Mutable working copy; LinkedHashMap preserves insertion order for tests.
        Map<String, Object> out = new LinkedHashMap<>(record);
        for (NormalizationConfig.FieldRule rule : fields) {
            applyRule(rule, out);
        }
        return out;
    }

    private void applyRule(NormalizationConfig.FieldRule rule, Map<String, Object> record) {
        switch (rule.type()) {
            case TIMESTAMP     -> applyTimestamp(rule, record);
            case NUMERIC_UNIT  -> applyNumericUnit(rule, record);
            case ENUM_MAP      -> applyEnumMap(rule, record);
            case RENAME        -> applyRename(rule, record);
        }
    }

    // ---------------------------------------------------------------------------
    // Rule: TIMESTAMP
    // ---------------------------------------------------------------------------

    private void applyTimestamp(NormalizationConfig.FieldRule rule, Map<String, Object> record) {
        Object raw = record.get(rule.source());
        if (raw == null) {
            // Propagate null to target, remove source if source != target.
            writeAndRemoveSource(rule, record, null);
            return;
        }

        String fromFormat = rule.param("from_format");
        String toFormat   = rule.param("to_format");

        Instant instant = parseToInstant(raw.toString(), fromFormat, rule.source());
        if (instant == null) {
            // Non-parseable: leave source value unchanged, skip transformation.
            log.warn("TIMESTAMP rule: could not parse '{}' as {} for field '{}'; leaving unchanged",
                    raw, fromFormat, rule.source());
            return;
        }

        Object converted = formatFromInstant(instant, toFormat);
        writeAndRemoveSource(rule, record, converted);
    }

    /**
     * Parse an arbitrary string to an {@link Instant} given a format hint.
     *
     * @return parsed instant, or {@code null} if parsing fails (caller logs and skips)
     */
    private Instant parseToInstant(String value, String format, String fieldName) {
        if (format == null) {
            // Default attempt: try iso8601 then epoch_millis
            Instant attempt = tryParseIso8601(value);
            if (attempt != null) return attempt;
            return tryParseEpochMillis(value);
        }
        return switch (format) {
            case "iso8601" -> tryParseIso8601(value);
            case "epoch_millis" -> tryParseEpochMillis(value);
            case "epoch_seconds" -> tryParseEpochSeconds(value);
            default -> {
                log.warn("TIMESTAMP rule: unknown from_format '{}' for field '{}'; attempting iso8601",
                        format, fieldName);
                yield tryParseIso8601(value);
            }
        };
    }

    private Instant tryParseIso8601(String value) {
        try {
            // Handles both UTC Z and offset notation; ZonedDateTime covers offset variants.
            return ZonedDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME).toInstant();
        } catch (DateTimeParseException e1) {
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    private Instant tryParseEpochMillis(String value) {
        try {
            return Instant.ofEpochMilli(Long.parseLong(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Instant tryParseEpochSeconds(String value) {
        try {
            return Instant.ofEpochSecond(Long.parseLong(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Object formatFromInstant(Instant instant, String toFormat) {
        if (toFormat == null) {
            return instant.toString(); // iso8601 default
        }
        return switch (toFormat) {
            case "iso8601"      -> instant.toString();
            case "epoch_millis" -> instant.toEpochMilli();
            case "epoch_seconds" -> instant.getEpochSecond();
            default -> {
                log.warn("TIMESTAMP rule: unknown to_format '{}'; defaulting to iso8601", toFormat);
                yield instant.toString();
            }
        };
    }

    // ---------------------------------------------------------------------------
    // Rule: NUMERIC_UNIT
    // ---------------------------------------------------------------------------

    private void applyNumericUnit(NormalizationConfig.FieldRule rule, Map<String, Object> record) {
        Object raw = record.get(rule.source());
        if (raw == null) {
            writeAndRemoveSource(rule, record, null);
            return;
        }

        BigDecimal multiplier = resolveMultiplier(rule);
        if (multiplier == null) {
            log.warn("NUMERIC_UNIT rule: could not resolve multiplier for field '{}'; leaving unchanged",
                    rule.source());
            return;
        }

        BigDecimal input;
        try {
            input = new BigDecimal(raw.toString().trim());
        } catch (NumberFormatException e) {
            log.warn("NUMERIC_UNIT rule: could not parse '{}' as numeric for field '{}'; leaving unchanged",
                    raw, rule.source());
            return;
        }

        BigDecimal result = input.multiply(multiplier);

        // Return Long if result is a whole number and multiplier > 1 (e.g. dollars→cents)
        // to keep type consistency with integer-typed downstream storage.
        Object converted;
        if (result.stripTrailingZeros().scale() <= 0
                && multiplier.compareTo(BigDecimal.ONE) > 0) {
            converted = result.longValueExact();
        } else {
            converted = result.stripTrailingZeros();
        }

        writeAndRemoveSource(rule, record, converted);
    }

    /**
     * Resolve the effective multiplier from rule params.
     * Priority: explicit {@code multiplier} param > well-known {@code from}/{@code to} pair.
     */
    private BigDecimal resolveMultiplier(NormalizationConfig.FieldRule rule) {
        String explicitMultiplier = rule.param("multiplier");
        if (explicitMultiplier != null) {
            try {
                return new BigDecimal(explicitMultiplier);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        String from = rule.param("from");
        String to   = rule.param("to");
        if (from == null || to == null) {
            return null;
        }
        Map<String, BigDecimal> toMap = UNIT_MULTIPLIERS.get(from);
        if (toMap == null) {
            return null;
        }
        return toMap.get(to);
    }

    // ---------------------------------------------------------------------------
    // Rule: ENUM_MAP
    // ---------------------------------------------------------------------------

    private void applyEnumMap(NormalizationConfig.FieldRule rule, Map<String, Object> record) {
        Object raw = record.get(rule.source());
        if (raw == null) {
            writeAndRemoveSource(rule, record, null);
            return;
        }

        Map<String, String> mapping = rule.mappingParam();
        String mapped = (mapping != null) ? mapping.get(raw.toString()) : null;
        // If no mapping entry found, pass through the original value unchanged.
        Object result = (mapped != null) ? mapped : raw;
        writeAndRemoveSource(rule, record, result);
    }

    // ---------------------------------------------------------------------------
    // Rule: RENAME
    // ---------------------------------------------------------------------------

    private void applyRename(NormalizationConfig.FieldRule rule, Map<String, Object> record) {
        Object value = record.get(rule.source());
        writeAndRemoveSource(rule, record, value);
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /**
     * Write {@code value} under {@code rule.target()}, and if source ≠ target
     * remove the original source key from the record.
     */
    private void writeAndRemoveSource(NormalizationConfig.FieldRule rule,
                                       Map<String, Object> record,
                                       Object value) {
        record.put(rule.target(), value);
        if (!rule.source().equals(rule.target())) {
            record.remove(rule.source());
        }
    }
}
