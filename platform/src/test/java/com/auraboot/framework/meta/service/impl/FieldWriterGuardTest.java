package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.exception.MetaServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Exact command field writer guard")
class FieldWriterGuardTest {

    private static final String RELEASE_COMMAND = "crm:release_qdp";

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("a direct create cannot forge a command-owned fact")
    void directCreateIsDenied() {
        assertThatThrownBy(() -> FieldWriterGuard.assertCreateAllowed(
                protectedModel(), Map.of("route_package", "pcba-crm")))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("FIELD_WRITER_DENIED")
                .hasMessageContaining("route_package");
    }

    @Test
    @DisplayName("an authorized exact command may write its owned field")
    void exactAuthorizedCommandIsAllowed() {
        assertThatCode(() -> underAuthorizedCommand(RELEASE_COMMAND,
                () -> FieldWriterGuard.assertCreateAllowed(
                        protectedModel(), Map.of("route_package", "pcba-crm"))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a different command sharing the same platform boundary is denied")
    void differentAuthorizedCommandIsDenied() {
        assertThatThrownBy(() -> underAuthorizedCommand("crm:update_request",
                () -> FieldWriterGuard.assertCreateAllowed(
                        protectedModel(), Map.of("route_package", "pcba-crm"))))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("FIELD_WRITER_DENIED");
    }

    @Test
    @DisplayName("an unchanged full-row update remains compatible")
    void unchangedUpdateIsAllowedWithoutCommand() {
        assertThatCode(() -> FieldWriterGuard.assertUpdateAllowed(
                protectedModel(),
                Map.of("route_package", "pcba-crm"),
                Map.of("route_package", "pcba-crm")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a changed protected field requires its exact command")
    void changedUpdateIsDeniedWithoutCommand() {
        assertThatThrownBy(() -> FieldWriterGuard.assertUpdateAllowed(
                protectedModel(),
                Map.of("route_package", "footwear"),
                Map.of("route_package", "pcba-crm")))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("FIELD_WRITER_DENIED");
    }

    @Test
    @DisplayName("an explicitly empty writer list fails closed")
    void emptyWriterListDeniesEveryWriter() {
        ModelDefinition denyAll = modelWithWriters(List.of());

        assertThatThrownBy(() -> underAuthorizedCommand(RELEASE_COMMAND,
                () -> FieldWriterGuard.assertFieldWriteAllowed(denyAll, "route_package")))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("FIELD_WRITER_DENIED");
    }

    @Test
    @DisplayName("fields without a writer declaration retain existing behavior")
    void undeclaredFieldRemainsWritable() {
        ModelDefinition unrestricted = modelWithWriters(null);

        assertThatCode(() -> FieldWriterGuard.assertCreateAllowed(
                unrestricted, Map.of("route_package", "pcba-crm")))
                .doesNotThrowAnyException();
    }

    private void underAuthorizedCommand(String commandCode, Runnable action) {
        MetaContext.runWithCommandPermitPlan(
                "ALL", null, "crm_customer_request_common", "cr-1",
                () -> MetaContext.runWithAuthorizedCommandCode(commandCode, action));
    }

    private ModelDefinition protectedModel() {
        return modelWithWriters(List.of(RELEASE_COMMAND));
    }

    private ModelDefinition modelWithWriters(List<String> allowedWriterCommands) {
        return ModelDefinition.builder()
                .code("crm_customer_request_common")
                .fields(List.of(FieldDefinition.builder()
                        .code("route_package")
                        .allowedWriterCommands(allowedWriterCommands)
                        .build()))
                .build();
    }
}
