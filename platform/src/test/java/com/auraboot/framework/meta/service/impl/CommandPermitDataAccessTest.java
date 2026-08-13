package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Permit-plan grade is the data-layer row authority")
class CommandPermitDataAccessTest {

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("ALL permits every record and contributes no row predicate")
    void allPermitsEveryRecord() {
        MetaContext.runWithCommandPermitScope("ALL", () -> {
            assertThat(CommandPermitDataAccess.permitsRecord(Map.of("created_by", 200L), 100L)).isTrue();
            assertThat(CommandPermitDataAccess.rowFilter(100L)).isEmpty();
        });
    }

    @Test
    @DisplayName("SELF permits only records owned by the current user")
    void selfPermitsOnlyOwnRecord() {
        MetaContext.runWithCommandPermitScope("SELF", () -> {
            assertThat(CommandPermitDataAccess.permitsRecord(Map.of("created_by", 100L), 100L)).isTrue();
            assertThat(CommandPermitDataAccess.permitsRecord(Map.of("created_by", "100"), 100L)).isTrue();
            assertThat(CommandPermitDataAccess.permitsRecord(Map.of("created_by", 200L), 100L)).isFalse();
            assertThat(CommandPermitDataAccess.permitsRecord(Map.of(), 100L)).isFalse();
            assertThat(CommandPermitDataAccess.rowFilter(100L)).isEqualTo("AND created_by = 100");
        });
    }

    @Test
    @DisplayName("a targeted SELF plan applies only to its root model")
    void targetedSelfAppliesOnlyToRootModel() {
        MetaContext.runWithCommandPermitPlan("SELF", null, "personal_task", "task-1", () -> {
            assertThat(CommandPermitDataAccess.rowFilter("personal_task", 100L))
                    .isEqualTo("AND created_by = 100");
            assertThat(CommandPermitDataAccess.permitsRecord(
                    "personal_task", Map.of("created_by", 200L), 100L)).isFalse();

            assertThat(CommandPermitDataAccess.rowFilter("shared_config", 100L))
                    .as("a different model must evaluate its own data permission")
                    .isNull();
            assertThat(CommandPermitDataAccess.permitsRecord(
                    "shared_config", Map.of("created_by", 200L), 100L))
                    .as("without an applicable plan grade this helper must not manufacture authority")
                    .isFalse();
        });
    }

    @Test
    @DisplayName("a targeted ALL plan does not widen another model")
    void targetedAllDoesNotWidenAnotherModel() {
        MetaContext.runWithCommandPermitPlan("ALL", null, "personal_task", "task-1", () -> {
            assertThat(CommandPermitDataAccess.rowFilter("personal_task", 100L)).isEmpty();
            assertThat(CommandPermitDataAccess.rowFilter("private_reference", 100L)).isNull();
        });
    }

    @Test
    @DisplayName("TARGET permits only the public PID named by this model's command")
    void targetPermitsOnlyTheNamedPublicRecord() {
        MetaContext.runWithCommandPermitPlan("TARGET", 3L, "crm_account_common", "account-1", () -> {
            assertThat(CommandPermitDataAccess.rowFilter("crm_account_common", 100L))
                    .isEqualTo("AND pid = 'account-1'");
            assertThat(CommandPermitDataAccess.permitsRecord(
                    "crm_account_common", Map.of("pid", "account-1", "created_by", 200L), 100L))
                    .isTrue();
            assertThat(CommandPermitDataAccess.permitsRecord(
                    "crm_account_common", Map.of("pid", "account-2", "created_by", 100L), 100L))
                    .isFalse();
            assertThat(CommandPermitDataAccess.rowFilter("crm_contact_common", 100L)).isNull();
        });
    }

    @Test
    @DisplayName("missing, unknown, or unusable scope never manufactures authority")
    void unresolvedScopeNeverManufacturesAuthority() {
        assertThat(CommandPermitDataAccess.permitsRecord(Map.of("created_by", 100L), 100L)).isFalse();
        assertThat(CommandPermitDataAccess.rowFilter(100L)).isNull();

        MetaContext.runWithCommandPermitScope("FUTURE_SCOPE", () -> {
            assertThat(MetaContext.hasCommandPermitScope()).isTrue();
            assertThat(CommandPermitDataAccess.permitsRecord(Map.of("created_by", 100L), 100L)).isFalse();
            assertThat(CommandPermitDataAccess.rowFilter(100L)).isEqualTo("AND 1 = 0");
        });

        MetaContext.runWithCommandPermitScope("SELF", () -> {
            assertThat(CommandPermitDataAccess.permitsRecord(Map.of("created_by", 100L), null)).isFalse();
            assertThat(CommandPermitDataAccess.rowFilter(null)).isEqualTo("AND 1 = 0");
        });
    }
}
