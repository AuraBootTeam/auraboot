package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.exception.MetaServiceException;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Enforces exact-command provenance for command-owned dynamic fields. */
public final class FieldWriterGuard {

    private FieldWriterGuard() {
    }

    /** New records have no stored value, so every supplied protected field is a write. */
    public static void assertCreateAllowed(ModelDefinition model, Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return;
        }
        assertFieldsAllowed(model, data.keySet());
    }

    /**
     * Preserve full-row round trips: an unchanged protected value is not a write, while every
     * changed protected value must come from its exact authorized command.
     */
    public static void assertUpdateAllowed(
            ModelDefinition model,
            Map<String, Object> submitted,
            Map<String, Object> existingRecord) {
        if (model == null || model.getFields() == null || submitted == null || submitted.isEmpty()) {
            return;
        }
        for (FieldDefinition field : model.getFields()) {
            if (!isProtected(field) || !submitted.containsKey(field.getCode())) {
                continue;
            }
            if (existingRecord != null
                    && !ValidationServiceImpl.valueChanges(
                    existingRecord.get(field.getCode()), submitted.get(field.getCode()))) {
                continue;
            }
            assertFieldWriteAllowed(model, field);
        }
    }

    /** Use for write paths that deliberately materialize the named fields (FIELD_MAP, handlers). */
    public static void assertFieldsAllowed(ModelDefinition model, Collection<String> fieldCodes) {
        if (model == null || model.getFields() == null || fieldCodes == null || fieldCodes.isEmpty()) {
            return;
        }
        for (FieldDefinition field : model.getFields()) {
            if (field != null && fieldCodes.contains(field.getCode())) {
                assertFieldWriteAllowed(model, field);
            }
        }
    }

    public static void assertFieldWriteAllowed(ModelDefinition model, String fieldCode) {
        if (model == null || model.getFields() == null || fieldCode == null) {
            return;
        }
        model.getFields().stream()
                .filter(Objects::nonNull)
                .filter(field -> fieldCode.equals(field.getCode()))
                .findFirst()
                .ifPresent(field -> assertFieldWriteAllowed(model, field));
    }

    private static void assertFieldWriteAllowed(ModelDefinition model, FieldDefinition field) {
        if (!isProtected(field)) {
            return;
        }
        String commandCode = MetaContext.getAuthorizedCommandCode();
        List<String> allowed = field.getAllowedWriterCommands();
        boolean exactMatch = MetaContext.hasCommandPermitScope()
                && commandCode != null
                && allowed.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .anyMatch(commandCode::equals);
        if (exactMatch) {
            return;
        }
        String modelCode = model.getCode() != null ? model.getCode() : "<unknown>";
        throw new MetaServiceException("FIELD_WRITER_DENIED: field '" + field.getCode()
                + "' on model '" + modelCode
                + "' may only be written by its declared authorized command");
    }

    private static boolean isProtected(FieldDefinition field) {
        return field != null && field.getAllowedWriterCommands() != null;
    }
}
