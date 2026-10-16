package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.security.ExternalMachineAuthenticator;
import com.auraboot.framework.application.security.MachineDataAccessContext;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class MachineDataAccessGuardsTest {
    @Test void valuesAreBoundAndFieldsCannotExpandThroughCommandPermits() {
        var principal = new ExternalMachineAuthenticator.MachinePrincipal(7L, "token", "app", Set.of(), "app", "installation", "staging", "token");
        var decision = new MachineDataAccessContext.Decision(7L, "installation", "crm_customer", Set.of("read", "update"),
                Set.of("pid", "name"), Set.of("name"), List.of(new MachineDataAccessContext.Constraint("region", "region", List.of("x' OR true --"))));
        try (var request = MachineDataAccessContext.begin(principal);
             var approved = MachineDataAccessContext.authorize(Map.of("crm_customer", decision))) {
            Map<String, Object> parameters = new LinkedHashMap<>();
            assertThat(MachineDataAccessGuards.predicate("crm_customer", "update", parameters, "scope"))
                    .isEqualTo("1 = 1 AND region IN (#{params.scope_0_0})").doesNotContain("OR true");
            assertThat(parameters).containsExactly(Map.entry("scope_0_0", "x' OR true --"));
            assertThatThrownBy(() -> MachineDataAccessGuards.predicate("crm_customer", "update", parameters, "scope"))
                    .isInstanceOf(AccessDeniedException.class);
            assertThat(MachineDataAccessGuards.filterFields("crm_customer", Map.of("pid", "record", "name", "Alice", "phone", "secret", "row_version", 1)))
                    .containsExactlyInAnyOrderEntriesOf(Map.of("pid", "record", "name", "Alice", "row_version", 1));
            assertThatThrownBy(() -> MachineDataAccessGuards.requireWritable("crm_customer", Map.of("phone", "changed")))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatCode(() -> MachineDataAccessGuards.requireWritable("crm_customer", Map.of("name", "changed", "_expectedVersion", 1)))
                    .doesNotThrowAnyException();
            assertThat(com.auraboot.framework.application.tenant.MetaContext.runWithCommandPermitScope("ALL",
                    () -> CommandPermitDataAccess.permitsRecord("crm_customer", Map.of("region", "west"), null))).isFalse();
        }
    }
}
