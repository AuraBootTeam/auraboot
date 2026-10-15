package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.ModelDefinition;

/** Synchronous owner-transaction hook; failures must roll back the source mutation. */
public interface ModelMutationObserver {
    enum Operation {
        CREATED, UPDATED, DELETED;
        public String code() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    /** Delete is observed before removal so its last committed shape can be captured. */
    void observe(ModelDefinition model, String pid, Operation operation);
}
