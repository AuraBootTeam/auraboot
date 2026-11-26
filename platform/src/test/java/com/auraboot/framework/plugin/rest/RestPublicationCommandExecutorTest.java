package com.auraboot.framework.plugin.rest;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.plugin.extension.IdentityDirectoryAccessor;
import com.auraboot.framework.plugin.extension.RestPublication;
import com.auraboot.framework.plugin.extension.RestPublicationCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RestPublicationCommandExecutorTest {
    private final IdentityDirectoryAccessor identities = mock(IdentityDirectoryAccessor.class);
    private final UserPermissionService permissions = mock(UserPermissionService.class);
    private final CommandExecutor commands = mock(CommandExecutor.class);
    private final RestPublicationCommandExecutor executor = new RestPublicationCommandExecutor(identities, permissions, commands);
    private final RestPublicationCommand declared = new RestPublicationCommand(
            new RestPublication("form", "id"), "crm:create_lead", "crm.lead.manage", Set.of("company"));
    private final RestPublicationStore.CommandGrant grant =
            new RestPublicationStore.CommandGrant(31L, "publisher", "crm:create_lead", Map.of());

    @BeforeEach void startAnonymousTransaction() {
        MetaContext.setContext(31L, 0L, "public", "public");
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach void clear() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        MetaContext.clear();
    }

    @Test void invokesOnlyTheFixedNativeCommandAndRestoresTheAnonymousActor() {
        stubPublisher();
        when(permissions.hasPermission(41L, "crm.lead.manage")).thenReturn(true);
        when(commands.execute(eq("crm:create_lead"), any())).thenAnswer(call -> {
            assertThat(MetaContext.getCurrentTenantId()).isEqualTo(31L);
            assertThat(MetaContext.getCurrentUserId()).isEqualTo(41L);
            return CommandExecuteResult.builder()
                    .data(Map.of("recordPid", "LEAD1", "internal", "not returned")).build();
        });
        assertThat(executor.execute(grant, declared, Map.of("company", "Example"))).isEqualTo("LEAD1");
        assertThat(MetaContext.getCurrentUserId()).isZero();
    }

    @Test void deniesRevokedPublisherPermissionAndUnapprovedFields() {
        stubPublisher();
        assertThatThrownBy(() -> executor.execute(grant, declared, Map.of("company", "Example")))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(MetaContext.getCurrentUserId()).isZero();
        assertThatThrownBy(() -> executor.execute(grant, declared, Map.of("owner", "Other")))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(commands);
    }

    private void stubPublisher() {
        var identity = new IdentityDirectoryAccessor.UserIdentity(41L, "publisher", "publisher", "Publisher");
        when(identities.findEnabledTenantMembership(31L, "publisher"))
                .thenReturn(new IdentityDirectoryAccessor.TenantUserIdentity(identity, "membership", 51L));
    }
}
