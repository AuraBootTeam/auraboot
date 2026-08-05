package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.exception.MetaServiceException;

/**
 * Enforces model-level append-only invariants at runtime write boundaries.
 */
public final class ModelMutationGuard {

    private ModelMutationGuard() {
    }

    public static void assertMutable(ModelDefinition model, String operation) {
        if (model == null || !model.isImmutable()) {
            return;
        }
        String modelCode = model.getCode() != null ? model.getCode() : "<unknown>";
        throw new MetaServiceException(
                "Model '" + modelCode + "' is immutable; records cannot be " + operation);
    }

    public static void assertMutableForOperation(ModelDefinition model, String operationType) {
        if (operationType == null) {
            return;
        }
        if ("update".equalsIgnoreCase(operationType)
                || "delete".equalsIgnoreCase(operationType)
                || "state_transition".equalsIgnoreCase(operationType)) {
            assertMutable(model, operationType.toLowerCase(java.util.Locale.ROOT));
        }
    }
}
