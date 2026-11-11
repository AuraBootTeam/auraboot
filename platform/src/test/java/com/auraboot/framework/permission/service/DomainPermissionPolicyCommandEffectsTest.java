package com.auraboot.framework.permission.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.entity.Permission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Cross-plugin technical effects must never become independent model grants. */
class DomainPermissionPolicyCommandEffectsTest {
    private static final String CHILD = "crm_customer_request_common";

    @BeforeEach void context() {
        MetaContext.setContext(100L, 1L, "actor-pid", "actor");
        MetaContext.setMemberId(5L);
    }
    @AfterEach void clear() { MetaContext.clear(); }

    private Permission policy(Object effects) {
        var permission = new Permission();
        permission.setCode("qo.cap.write");
        permission.setResourceCode("qo");
        permission.setResourceType("operation");
        permission.setAction("write");
        permission.setExtension(Map.of("domainModels", Map.of("qo_quote_common", List.of("read", "create")),
                "commandEffects", effects));
        return permission;
    }
    private Permission policy() { return policy(Map.of(CHILD, List.of("read", "create"))); }
    private void admitted(String authority, Runnable body) {
        MetaContext.runWithCommandPermitPlan("SELF", null, "qo_quote_common", null, () ->
                MetaContext.runWithAuthorizedCommandCode("qo_quote_common:create", () ->
                        MetaContext.runWithCommandAuthority(authority, body)));
    }

    @Test void standaloneAccessDoesNotGainHiddenCrmAuthority() {
        var policy = policy();
        assertThat(DomainPermissionPolicy.owns(policy, CHILD)).isFalse();
        assertThat(DomainPermissionPolicy.permits(policy, CHILD, "read")).isFalse();
        assertThat(DomainPermissionPolicy.permits(policy, CHILD, "create")).isFalse();
    }
    @Test void admittedWriteCanCreateAndReadOnlyItsDeclaredEffects() {
        var policy = policy();
        admitted("qo.cap.write", () -> {
            assertThat(DomainPermissionPolicy.owns(policy, CHILD)).isTrue();
            assertThat(DomainPermissionPolicy.permits(policy, CHILD, "read")).isTrue();
            assertThat(DomainPermissionPolicy.isCurrentCommandMutation(policy, CHILD, "create")).isTrue();
            assertThat(DomainPermissionPolicy.permits(policy, CHILD, "update")).isFalse();
            assertThat(DomainPermissionPolicy.isCurrentCommandMutation(policy, CHILD, "delete")).isFalse();
            assertThat(DomainPermissionPolicy.owns(policy, "crm_account_common")).isFalse();
        });
        assertThat(DomainPermissionPolicy.owns(policy, CHILD)).isFalse();
    }
    @Test void anotherDomainCannotBorrowTheQuoteEffects() {
        admitted("bom.cap.write", () -> assertThat(DomainPermissionPolicy.owns(policy(), CHILD)).isFalse());
    }
    @Test void commandNameWithoutAdmittedPermitDoesNotLendEffects() {
        assertThatThrownBy(() -> MetaContext.runWithAuthorizedCommandCode("qo_quote_common:create", () ->
                MetaContext.runWithCommandAuthority("qo.cap.write", () ->
                        assertThat(DomainPermissionPolicy.owns(policy(), CHILD)).isFalse())))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void readonlyLevelCannotDeclareMutationEffects() {
        var permission = policy(); permission.setCode("qo.cap.read"); permission.setAction("read");
        assertThatThrownBy(() -> DomainPermissionPolicy.owns(permission, CHILD)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void wildcardModelIsRejected() {
        assertThatThrownBy(() -> DomainPermissionPolicy.owns(policy(Map.of("*", List.of("create"))), CHILD))
                .isInstanceOf(AccessDeniedException.class);
    }
    @Test void unknownOrDuplicateActionsAreRejected() {
        for (var actions : List.of(List.of("execute"), List.of("create", "create"))) {
            assertThatThrownBy(() -> DomainPermissionPolicy.owns(policy(Map.of(CHILD, actions)), CHILD))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }
    @Test void malformedOrEmptyEffectCatalogIsRejected() {
        for (Object effects : List.of("all", Map.of(), Map.of(CHILD, List.of()))) {
            assertThatThrownBy(() -> DomainPermissionPolicy.owns(policy(effects), CHILD))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }
}
