package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.dto.RelationDefinition;
import com.auraboot.framework.meta.exception.TemporalParseException;
import com.auraboot.framework.meta.service.MetaModelService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Normalizes temporal string values in a command payload to typed Java objects.
 *
 * <p>Runs after SCHEMA_VALIDATE and before ASSERT in the command execution pipeline.
 * Converts String → LocalDate (for DATE fields) or String → Instant (for DATETIME fields).
 * Throws TemporalParseException (→ HTTP 400) on invalid or offset-free datetime strings.
 *
 * <p>Idempotent: skips values already typed as LocalDate or Instant.
 *
 * <p>Recursive: if the model has ONE_TO_MANY relations and the payload contains a corresponding
 * List value, each child row is normalized using the target model's field definitions.
 * Maximum recursion depth is capped at {@value #MAX_DEPTH} to guard against pathological payloads.
 */
@Slf4j
@Component
class PayloadTemporalNormalizer {

    private static final Set<String> DATE_TYPES = Set.of(
        "date", "localdate"
    );
    private static final Set<String> DATETIME_TYPES = Set.of(
        "datetime", "timestamp", "localdatetime"
    );

    /** Guard against runaway recursion in deeply-nested payloads. */
    static final int MAX_DEPTH = 10;

    private final Counter unexpectedTypeCounter;
    private final MetaModelService metaModelService;

    PayloadTemporalNormalizer(MeterRegistry meterRegistry, MetaModelService metaModelService) {
        this.unexpectedTypeCounter = Counter.builder("temporal.normalizer.unexpected_type")
            .description("Payload field has a temporal type but unexpected Java type at normalization")
            .register(meterRegistry);
        this.metaModelService = metaModelService;
    }

    /**
     * Normalize all temporal fields in the payload according to the model definition.
     * Also recursively normalizes ONE_TO_MANY child rows present in the payload.
     *
     * @param payload mutable payload map (modified in-place)
     * @param model   model definition providing field type info
     * @throws TemporalParseException if a field value cannot be parsed
     */
    void normalize(Map<String, Object> payload, ModelDefinition model) {
        normalizeRecursive(payload, model, 0);
    }

    /**
     * Internal recursive implementation with depth guard.
     */
    @SuppressWarnings("unchecked")
    private void normalizeRecursive(Map<String, Object> payload, ModelDefinition model, int depth) {
        if (depth >= MAX_DEPTH) {
            log.warn("temporal.normalizer: max recursion depth {} reached for model '{}', stopping",
                MAX_DEPTH, model.getCode());
            return;
        }

        // Normalize top-level temporal fields
        if (model.getFields() != null) {
            for (FieldDefinition field : model.getFields()) {
                String fieldCode = field.getCode();
                if (!payload.containsKey(fieldCode)) continue;

                Object value = payload.get(fieldCode);
                if (value == null) continue;

                String dataType = field.getDataType() == null
                    ? null : field.getDataType().toLowerCase();

                if (DATE_TYPES.contains(dataType)) {
                    Object normalized = normalizeDate(fieldCode, value);
                    if (normalized != null) payload.put(fieldCode, normalized);
                } else if (DATETIME_TYPES.contains(dataType)) {
                    Object normalized = normalizeDatetime(fieldCode, value);
                    if (normalized != null) payload.put(fieldCode, normalized);
                }
            }
        }

        // Recursively normalize ONE_TO_MANY child rows present in the payload
        if (model.getRelations() == null) return;
        for (RelationDefinition relation : model.getRelations()) {
            if (relation.getRelationType() != RelationDefinition.RelationType.ONE_TO_MANY) continue;
            String relationName = relation.getName();
            if (relationName == null || !payload.containsKey(relationName)) continue;

            Object childValue = payload.get(relationName);
            if (!(childValue instanceof List<?> childList)) continue;

            String targetModel = relation.getTargetModel();
            if (targetModel == null) continue;

            ModelDefinition childModel = metaModelService.getModelDefinition(targetModel).orElse(null);
            if (childModel == null) {
                log.debug("temporal.normalizer: child model '{}' not found for relation '{}', skipping",
                    targetModel, relationName);
                continue;
            }

            for (Object row : childList) {
                if (row instanceof Map<?, ?> rowMap) {
                    normalizeRecursive((Map<String, Object>) rowMap, childModel, depth + 1);
                }
            }
        }
    }

    private LocalDate normalizeDate(String fieldCode, Object value) {
        // Already typed — idempotent
        if (value instanceof LocalDate ld) return ld;

        if (value instanceof String str) {
            try {
                return LocalDate.parse(str);
            } catch (DateTimeParseException e) {
                throw new TemporalParseException(fieldCode, str, "ISO-8601 date (yyyy-MM-dd)");
            }
        }

        // Unexpected Java type (e.g. Integer) — count and skip
        unexpectedTypeCounter.increment();
        log.warn("temporal.normalizer: field '{}' has unexpected type {} for DATE, skipping",
            fieldCode, value.getClass().getSimpleName());
        return null;
    }

    private Instant normalizeDatetime(String fieldCode, Object value) {
        // Already typed — idempotent
        if (value instanceof Instant inst) return inst;

        if (value instanceof String str) {
            // Require explicit offset (Z or ±HH:MM) — no fallback guessing
            if (!str.endsWith("Z") && !str.matches(".*[+-]\\d{2}:\\d{2}$")) {
                throw new TemporalParseException(
                    fieldCode, str,
                    "ISO-8601 datetime with offset (e.g. 2026-03-18T10:30:00+08:00 or ...Z)"
                );
            }
            try {
                return OffsetDateTime.parse(str).toInstant();
            } catch (DateTimeParseException e) {
                throw new TemporalParseException(
                    fieldCode, str,
                    "ISO-8601 datetime with offset (e.g. 2026-03-18T10:30:00+08:00 or ...Z)"
                );
            }
        }

        // Unexpected Java type — count and skip
        unexpectedTypeCounter.increment();
        log.warn("temporal.normalizer: field '{}' has unexpected type {} for DATETIME, skipping",
            fieldCode, value.getClass().getSimpleName());
        return null;
    }
}
