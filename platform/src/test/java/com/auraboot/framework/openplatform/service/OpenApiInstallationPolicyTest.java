package com.auraboot.framework.openplatform.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.auraboot.framework.openplatform.service.OpenApiResourceContract.*;
import static org.assertj.core.api.Assertions.*;

class OpenApiInstallationPolicyTest {
    private OpenApiResourceContract contract() {
        return new OpenApiResourceContract("customers", "crm_customer", 1,
                Set.of(Operation.GET, Operation.CREATE), Map.of(Operation.GET, "customers.read", Operation.CREATE, "customers.create"),
                DataPolicy.INSTALLATION_FILTER, Map.of(
                    "pid", new Field("pid", Type.PID, true, false, false, false, Set.of(), false, null, Map.of(), null),
                    "name", new Field("crm_name", Type.STRING, true, true, true, false, Set.of("EQ"), false, null, Map.of(), null),
                    "phone", new Field("crm_phone", Type.STRING, true, true, false, true, Set.of(), false, null, Map.of(), null)),
                50, 8, 5000, Set.of("created"));
    }

    private OpenApiInstallationPolicy policy() {
        return new OpenApiInstallationPolicy(Set.of(Operation.GET), Set.of("pid", "name"), Set.of("name"),
                List.of(new OpenApiInstallationPolicy.RowConstraint("name", List.of("Alice"))));
    }

    @Test void narrowsProjectionAndRejectsForbiddenWriteFields() {
        var contract = contract();
        var policy = policy();
        policy.validate(contract);
        assertThat(policy.project(contract, Map.of("pid", "customer-pid", "crm_name", "Alice", "crm_phone", "private")))
                .containsExactlyInAnyOrderEntriesOf(Map.of("pid", "customer-pid", "name", "Alice"));
        assertThatThrownBy(() -> policy.mapWrite(contract, Map.of("phone", "changed"), false))
                .hasMessageContaining("installation field policy");
        assertThat(policy.permitsRow(contract, Map.of("crm_name", "Alice"))).isTrue();
        assertThat(policy.permitsRow(contract, Map.of("crm_name", "Bob"))).isFalse();
        assertThat(policy.permitsRow(contract, Map.of())).isFalse();
    }

    @Test void rejectsBroadeningAndMissingRequiredRowPolicy() {
        assertThatThrownBy(() -> new OpenApiInstallationPolicy(Set.of(Operation.DELETE), Set.of("pid"), Set.of(),
                policy().rowConstraints()).validate(contract())).hasMessageContaining("operations");
        assertThatThrownBy(() -> new OpenApiInstallationPolicy(Set.of(Operation.GET), Set.of("pid", "secret"), Set.of(),
                policy().rowConstraints()).validate(contract())).hasMessageContaining("readable");
        assertThatThrownBy(() -> new OpenApiInstallationPolicy(Set.of(Operation.GET), Set.of("pid"), Set.of(),
                List.of()).validate(contract())).hasMessageContaining("row policy");
        assertThatThrownBy(() -> new OpenApiInstallationPolicy(Set.of(Operation.GET), Set.of("pid"), Set.of(),
                List.of(new OpenApiInstallationPolicy.RowConstraint("phone", List.of("secret")))).validate(contract()))
                .hasMessageContaining("filterable");
    }

    @Test void creationCannotHideRequiredInputAndRowConstraintsAreConjunctive() {
        assertThatThrownBy(() -> new OpenApiInstallationPolicy(Set.of(Operation.CREATE), Set.of("pid"), Set.of(),
                policy().rowConstraints()).validate(contract())).hasMessageContaining("required input");
        assertThatThrownBy(() -> new OpenApiInstallationPolicy.RowConstraint("name", List.of())).hasMessageContaining("bounded");
        assertThatThrownBy(() -> new OpenApiInstallationPolicy(Set.of(Operation.GET), Set.of("pid"), Set.of(),
                List.of(policy().rowConstraints().getFirst(), policy().rowConstraints().getFirst())))
                .hasMessageContaining("Duplicate");
    }
}
