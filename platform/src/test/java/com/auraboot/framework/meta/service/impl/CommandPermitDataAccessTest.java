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
