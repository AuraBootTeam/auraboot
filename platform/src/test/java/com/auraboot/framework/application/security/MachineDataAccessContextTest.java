package com.auraboot.framework.application.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class MachineDataAccessContextTest {
    private ExternalMachineAuthenticator.MachinePrincipal principal() {
        return new ExternalMachineAuthenticator.MachinePrincipal(7L, "token", "application", Set.of("customers.read"),
                "application", "installation", "staging", "token");
    }

    private MachineDataAccessContext.Decision decision(Long tenant, String installation) {
        return new MachineDataAccessContext.Decision(tenant, installation, "crm_customer", Set.of("read", "update"),
                Set.of("pid", "name"), Set.of("name"), List.of(new MachineDataAccessContext.Constraint("region", "region", List.of("east"))));
    }

    @Test void absentAuthorityDeniesAndScopedDecisionsCannotCrossModelTenantOrInstallation() {
        try (var request = MachineDataAccessContext.begin(principal())) {
            assertThatThrownBy(() -> MachineDataAccessContext.requireDecision("crm_customer")).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> MachineDataAccessContext.authorize(Map.of("crm_customer", decision(8L, "installation"))))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> MachineDataAccessContext.authorize(Map.of("crm_customer", decision(7L, "other-installation"))))
                    .isInstanceOf(AccessDeniedException.class);
            try (var approved = MachineDataAccessContext.authorize(Map.of("crm_customer", decision(7L, "installation")))) {
                var authority = MachineDataAccessContext.requireDecision("crm_customer");
                assertThat(authority.permits(Map.of("region", "east", "tenant_id", 7L))).isTrue();
                assertThat(authority.permits(Map.of("region", "west", "tenant_id", 7L))).isFalse();
                assertThat(authority.permits(Map.of("region", "east", "tenant_id", 8L))).isFalse();
                assertThatThrownBy(() -> MachineDataAccessContext.requireDecision("crm_order")).isInstanceOf(AccessDeniedException.class);
                assertThatThrownBy(() -> authority.requireAction("delete")).isInstanceOf(AccessDeniedException.class);
            }
            assertThatThrownBy(() -> MachineDataAccessContext.requireDecision("crm_customer")).isInstanceOf(AccessDeniedException.class);
        }
        assertThat(MachineDataAccessContext.isActive()).isFalse();
    }

    @Test void failedNestedExecutionRestoresAuthorityAndRequestCleanupRemovesIdentity() {
        assertThatThrownBy(() -> {
            try (var request = MachineDataAccessContext.begin(principal())) {
                try (var approved = MachineDataAccessContext.authorize(Map.of("crm_customer", decision(7L, "installation")))) {
                    throw new IllegalStateException("transaction failed");
                }
            }
        }).isInstanceOf(IllegalStateException.class);
        assertThat(MachineDataAccessContext.isActive()).isFalse();
        assertThat(MachineDataAccessContext.principal()).isNull();
    }
    @Test void requiredReferenceReadsAreExplicitInvocationLocalAndRestoreLegacyAuthority() {
        var decisions=Map.of("crm_customer",decision(7L,"installation"));
        try(var request=MachineDataAccessContext.begin(principal())) {
            assertThat(MachineDataAccessContext.requiredReferenceReadScope()).isNull();
            try(var legacy=MachineDataAccessContext.authorize(decisions)) {
                assertThat(MachineDataAccessContext.requiredReferenceReadScope()).isNull();
                try(var approved=MachineDataAccessContext.authorizeRequiredReferenceReads(decisions)) {
                    Object original=MachineDataAccessContext.requiredReferenceReadScope();
                    assertThat(original).isNotNull();
                    try(var same=MachineDataAccessContext.authorize(decisions)) {
                        assertThat(MachineDataAccessContext.requiredReferenceReadScope()).isNotSameAs(original).isNotNull();
                    }
                    assertThat(MachineDataAccessContext.requiredReferenceReadScope()).isSameAs(original);
                    try(var oldInvocation=MachineDataAccessContext.authorizeWithoutRequiredReferenceReads(decisions)) {
                        assertThat(MachineDataAccessContext.requiredReferenceReadScope()).isNull();
                    }
                    assertThat(MachineDataAccessContext.requiredReferenceReadScope()).isSameAs(original);
                }
                assertThat(MachineDataAccessContext.requiredReferenceReadScope()).isNull();
            }
        }
        assertThat(MachineDataAccessContext.isActive()).isFalse();
    }

}
