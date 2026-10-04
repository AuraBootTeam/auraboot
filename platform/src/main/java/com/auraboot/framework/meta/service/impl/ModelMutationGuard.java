package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.exception.ImmutableModelMutationException;
import com.auraboot.framework.meta.exception.MetaServiceException;

import java.util.Map;
import java.util.Objects;

/**
 * Enforces model mutation invariants at runtime write boundaries.
 */
public final class ModelMutationGuard {

    private ModelMutationGuard() {
    }

    public static void assertMutable(ModelDefinition model, String operation) {
        if (model == null || !model.isImmutable()) {
            return;
        }
        String modelCode = model.getCode() != null ? model.getCode() : "<unknown>";
        throw new ImmutableModelMutationException(
                "Model '" + modelCode + "' is immutable; records cannot be " + operation);
    }

    public static void assertMutableForOperation(ModelDefinition model, String operationType) {
        if (operationType == null) {
            return;
        }
        if ("delete".equalsIgnoreCase(operationType)) {
            assertDeleteAllowed(model);
            return;
        }
        if ("update".equalsIgnoreCase(operationType)
                || "state_transition".equalsIgnoreCase(operationType)) {
            assertMutable(model, operationType.toLowerCase(java.util.Locale.ROOT));
        }
    }

    /** Require command authorization for deletes without preventing review or revocation updates. */
    public static void assertDeleteAllowed(ModelDefinition model) {
        assertMutable(model, "deleted");
        if (model == null || model.getExtension() == null) {
            return;
        }
        Object policy = model.getExtension().get("commandOnlyDelete");
        if (policy == null) {
            return;
        }
        if (!(policy instanceof Boolean)) {
            throw new MetaServiceException("Model '" + model.getCode()
                    + "' has invalid commandOnlyDelete policy; expected a boolean");
        }
        if (Boolean.TRUE.equals(policy) && !MetaContext.hasCommandPermitScope()) {
            throw new MetaServiceException("Model '" + model.getCode()
                    + "' can only be deleted through an authorized command");
        }
    }

    /** Protect sole-writer facts from generic create APIs while retaining authorized Commands. */
    public static void assertCreateAllowed(ModelDefinition model) {
        if (model == null || !model.isCommandOnlyCreate()
                || MetaContext.hasCommandPermitScope()) {
            return;
        }
        String modelCode = model.getCode() != null ? model.getCode() : "<unknown>";
        throw new MetaServiceException(
                "Model '" + modelCode + "' can only be created through an authorized command");
    }

    /**
     * Permit only the same-transaction completion writes that follow a FIELD_MAP insert.
     * The marker is produced by {@link CommandFieldMapExecutor}, not by caller payload, and must
     * identify the exact model and pid being completed. All later updates remain forbidden.
     */
    public static void assertMutableOrInsertedInThisCommand(
            ModelDefinition model,
            String operation,
            Map<String, Object> fieldMapResults,
            String recordId) {
        if (model == null || !model.isImmutable()) {
            return;
        }
        String modelCode = model.getCode();
        Object inserted = fieldMapResults == null || modelCode == null
                ? null
                : fieldMapResults.get(modelCode + "_inserted");
        Object insertedPid = fieldMapResults == null ? null : fieldMapResults.get("recordPid");
        boolean insertedOnce = inserted instanceof Number number && number.longValue() == 1L;
        if (insertedOnce && insertedPid != null
                && Objects.equals(String.valueOf(insertedPid), recordId)) {
            return;
        }
        assertMutable(model, operation);
    }
}
