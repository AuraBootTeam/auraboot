package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.exception.MetaServiceException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelMutationGuardTest {

    @Test
    void mutableModelsKeepExistingUpdateAndDeleteBehavior() {
        ModelDefinition model = ModelDefinition.builder()
                .code("mutable_master")
                .build();

        assertThatCode(() -> ModelMutationGuard.assertMutable(model, "updated"))
                .doesNotThrowAnyException();
        assertThatCode(() -> ModelMutationGuard.assertMutableForOperation(model, "delete"))
                .doesNotThrowAnyException();
    }

    @Test
    void immutableModelsRejectUpdateDeleteAndStateTransition() {
        ModelDefinition model = ModelDefinition.builder()
                .code("qdp_revision")
                .immutable(true)
                .build();

        for (String operation : new String[]{"update", "delete", "state_transition"}) {
            assertThatThrownBy(() -> ModelMutationGuard.assertMutableForOperation(model, operation))
                    .isInstanceOf(MetaServiceException.class)
                    .hasMessageContaining("qdp_revision")
                    .hasMessageContaining("immutable")
                    .hasMessageContaining(operation);
        }
    }

    @Test
    void createRemainsAllowedForImmutableModels() {
        ModelDefinition model = ModelDefinition.builder()
                .code("qdp_revision")
                .immutable(true)
                .build();

        assertThatCode(() -> ModelMutationGuard.assertMutableForOperation(model, "create"))
                .doesNotThrowAnyException();
    }
}
