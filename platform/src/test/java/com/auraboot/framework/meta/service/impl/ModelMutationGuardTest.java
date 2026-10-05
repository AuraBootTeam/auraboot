package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.exception.MetaServiceException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelMutationGuardTest {

    @Test
    void invalidDeletionPolicyFailsClosedInsteadOfSilentlyAllowingDeletion() {
        ModelDefinition model = ModelDefinition.builder().code("oi_disclosure_package")
                .extension(Map.of("commandOnlyDelete", "true")).build();
        assertThatThrownBy(() -> ModelMutationGuard.assertMutableForOperation(model, "delete"))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("invalid commandOnlyDelete policy");
    }

    @Test
    void commandAuthorizationCannotOverrideImmutableAuditDeletion() {
        ModelDefinition model = ModelDefinition.builder().code("oi_disclosure_access_log")
                .immutable(true).extension(Map.of("commandOnlyDelete", true)).build();
        try {
            assertThatThrownBy(() -> MetaContext.runWithCommandPermitPlan(
                    "ALL", null, "oi_disclosure_access_log", "audit-1",
                    () -> ModelMutationGuard.assertMutableForOperation(model, "delete")))
                    .isInstanceOf(MetaServiceException.class).hasMessageContaining("immutable");
        } finally {
            MetaContext.clear();
        }
    }

    @Test
    void commandOnlyDeleteRejectsGenericDeleteWithoutBlockingLifecycleUpdates() {
        ModelDefinition model = ModelDefinition.builder()
                .code("oi_disclosure_package")
                .extension(Map.of("commandOnlyDelete", true))
                .build();
        try {
            assertThatThrownBy(() -> ModelMutationGuard.assertMutableForOperation(model, "DELETE"))
                    .isInstanceOf(MetaServiceException.class)
                    .hasMessageContaining("authorized command");
            assertThatCode(() -> ModelMutationGuard.assertMutableForOperation(model, "update"))
                    .doesNotThrowAnyException();
            assertThatCode(() -> MetaContext.runWithCommandPermitPlan(
                    "ALL", null, "oi_disclosure_package", "dp-1",
                    () -> ModelMutationGuard.assertMutableForOperation(model, "delete")))
                    .doesNotThrowAnyException();
        } finally {
            MetaContext.clear();
        }
    }

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

    @Test
    void sameTransactionInsertMarkerAllowsOnlyTheExactCreateCompletionWrite() {
        ModelDefinition model = ModelDefinition.builder()
                .code("qdp_revision")
                .immutable(true)
                .build();
        Map<String, Object> inserted = Map.of(
                "qdp_revision_inserted", 1,
                "recordPid", "qdp-1");

        assertThatCode(() -> ModelMutationGuard.assertMutableOrInsertedInThisCommand(
                model, "completed", inserted, "qdp-1"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ModelMutationGuard.assertMutableOrInsertedInThisCommand(
                model, "completed", inserted, "qdp-other"))
                .isInstanceOf(MetaServiceException.class);
        assertThatThrownBy(() -> ModelMutationGuard.assertMutableOrInsertedInThisCommand(
                model, "completed", Map.of("recordPid", "qdp-1"), "qdp-1"))
                .isInstanceOf(MetaServiceException.class);
    }

    @Test
    void commandOnlyCreateRejectsGenericWritersAndAllowsAnAuthorizedCommand() {
        ModelDefinition model = ModelDefinition.builder()
                .code("qdp_revision")
                .immutable(true)
                .commandOnlyCreate(true)
                .build();

        assertThatThrownBy(() -> ModelMutationGuard.assertCreateAllowed(model))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("authorized command");
        assertThatCode(() -> MetaContext.runWithCommandPermitPlan(
                "ALL", null, "crm_customer_request_common", "cr-1",
                () -> ModelMutationGuard.assertCreateAllowed(model)))
                .doesNotThrowAnyException();
        MetaContext.clear();
    }
}
