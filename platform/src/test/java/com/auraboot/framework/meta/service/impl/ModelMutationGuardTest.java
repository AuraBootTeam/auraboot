package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.exception.MetaServiceException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
import java.util.HashMap;

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

    @Test
    void exactWritersRejectGenericAndUnrelatedCommands() {
        ModelDefinition model=ModelDefinition.builder().code("ab_announcement").extension(Map.of(
            "commandWriters", Map.of("create",java.util.List.of("notice:create"),
                "update",java.util.List.of("notice:save"),"delete",java.util.List.of("notice:delete")))).build();
        assertThatThrownBy(()->ModelMutationGuard.assertCreateAllowed(model)).isInstanceOf(MetaServiceException.class);
        try {
            MetaContext.runWithCommandPermitPlan("ALL",null,"ab_announcement","n-1",()-> {
                MetaContext.runWithAuthorizedCommandCode("other:save",()-> {
                    assertThatThrownBy(()->ModelMutationGuard.assertMutable(model,"updated"))
                        .isInstanceOf(MetaServiceException.class);
                });
                MetaContext.runWithAuthorizedCommandCode("notice:save",()-> {
                    assertThatCode(()->ModelMutationGuard.assertMutable(model,"updated")).doesNotThrowAnyException();
                    assertThatThrownBy(()->ModelMutationGuard.assertDeleteAllowed(model))
                        .isInstanceOf(MetaServiceException.class);
                });
            });
        } finally { MetaContext.clear(); }
    }

    @Test
    void malformedWriterPolicyFailsClosed() {
        ModelDefinition model=ModelDefinition.builder().code("ab_announcement")
            .extension(Map.of("commandWriters",Map.of("update",java.util.List.of("save")))).build();
        assertThatThrownBy(()->ModelMutationGuard.assertMutable(model,"updated"))
            .isInstanceOf(MetaServiceException.class).hasMessageContaining("Invalid");
    }

    @Test
    void appendOnlyPolicyAllowsOnlyItsExactCreateCommand() {
        ModelDefinition model = ModelDefinition.builder().code("audit_record").immutable(true)
                .extension(Map.of("commandWriters", Map.of(
                        "create", List.of("audit:create"), "update", List.of(), "delete", List.of())))
                .build();
        assertThatThrownBy(() -> ModelMutationGuard.assertCreateAllowed(model))
                .isInstanceOf(MetaServiceException.class).hasMessageContaining("exact authorized command");
        try {
            MetaContext.runWithCommandPermitPlan("ALL", null, "audit_record", "audit-1", () -> {
                assertThatThrownBy(() -> ModelMutationGuard.assertCreateAllowed(model))
                        .isInstanceOf(MetaServiceException.class).hasMessageContaining("exact authorized command");
                MetaContext.runWithAuthorizedCommandCode("other:create", () ->
                        assertThatThrownBy(() -> ModelMutationGuard.assertCreateAllowed(model))
                                .isInstanceOf(MetaServiceException.class)
                                .hasMessageContaining("exact authorized command"));
                MetaContext.runWithAuthorizedCommandCode("audit:create", () -> {
                    assertThatCode(() -> ModelMutationGuard.assertCreateAllowed(model)).doesNotThrowAnyException();
                    for (String operation : List.of("update", "delete", "state_transition")) {
                        assertThatThrownBy(() -> ModelMutationGuard.assertMutableForOperation(model, operation))
                                .isInstanceOf(MetaServiceException.class)
                                .hasMessageContaining("exact authorized command");
                    }
                    assertThatThrownBy(() -> ModelMutationGuard.assertMutableOrInsertedInThisCommand(
                            model, "completed", Map.of("audit_record_inserted", 1, "recordPid", "audit-1"), "audit-1"))
                            .isInstanceOf(MetaServiceException.class).hasMessageContaining("exact authorized command");
                });
            });
        } finally {
            MetaContext.clear();
        }
    }

    @Test
    void allEmptyWriterListsDenyEveryOperationEvenWithAnExactCommandScope() {
        ModelDefinition model = ModelDefinition.builder().code("readonly_record")
                .extension(Map.of("commandWriters", Map.of(
                        "create", List.of(), "update", List.of(), "delete", List.of())))
                .build();
        try {
            MetaContext.runWithCommandPermitPlan("ALL", null, "readonly_record", "r-1", () ->
                    MetaContext.runWithAuthorizedCommandCode("record:write", () -> {
                        for (String operation : List.of("create", "update", "delete", "state_transition")) {
                            assertThatThrownBy(() -> ModelMutationGuard.assertMutableForOperation(model, operation))
                                    .isInstanceOf(MetaServiceException.class)
                                    .hasMessageContaining("exact authorized command");
                        }
                    }));
        } finally {
            MetaContext.clear();
        }
    }

    @Test
    void matchingWriterCannotOverrideImmutableUpdateOrDelete() {
        ModelDefinition model = ModelDefinition.builder().code("audit_record").immutable(true)
                .extension(Map.of("commandWriters", Map.of(
                        "create", List.of(), "update", List.of("audit:write"), "delete", List.of("audit:write"))))
                .build();
        try {
            MetaContext.runWithCommandPermitPlan("ALL", null, "audit_record", "audit-1", () ->
                    MetaContext.runWithAuthorizedCommandCode("audit:write", () -> {
                        for (String operation : List.of("update", "delete", "state_transition")) {
                            assertThatThrownBy(() -> ModelMutationGuard.assertMutableForOperation(model, operation))
                                    .isInstanceOf(MetaServiceException.class).hasMessageContaining("immutable");
                        }
                    }));
        } finally {
            MetaContext.clear();
        }
    }

    @Test
    void emptyWriterSupportDoesNotAcceptMalformedPolicies() {
        List<Object> malformed = new java.util.ArrayList<>();
        malformed.add("invalid");
        malformed.add(Map.of("create", List.of(), "update", List.of()));
        malformed.add(Map.of("create", List.of(), "update", List.of(), "delete", List.of(), "extra", List.of()));
        for (Object invalid : java.util.Arrays.asList(null, "save", 1, List.of(7), List.of(""),
                List.of("  "), java.util.Arrays.asList("valid", null))) {
            Map<String, Object> policy = new HashMap<>();
            policy.put("create", List.of());
            policy.put("update", invalid);
            policy.put("delete", List.of());
            malformed.add(policy);
        }
        for (Object policy : malformed) {
            ModelDefinition model = ModelDefinition.builder().code("audit_record")
                    .extension(Map.of("commandWriters", policy)).build();
            assertThatThrownBy(() -> ModelMutationGuard.assertCreateAllowed(model))
                    .isInstanceOf(MetaServiceException.class).hasMessageContaining("Invalid model commandWriters policy");
        }
    }
}
