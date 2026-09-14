package com.auraboot.framework.openplatform.controller;

import com.auraboot.framework.application.security.ExternalMachineAuthenticator.MachinePrincipal;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.openplatform.service.OpenApiPublicationRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenPlatformFacadeControllerTest {
    private final DynamicDataService dataService = mock(DynamicDataService.class);
    private final CommandExecutor commandExecutor = mock(CommandExecutor.class);
    private final OpenPlatformFacadeController controller = new OpenPlatformFacadeController(
            new OpenApiPublicationRegistry(), dataService, commandExecutor);

    @Test
    void commandUsesInstallationScopedIdempotencyAndNarrowMachineGrant() {
        MachinePrincipal principal = new MachinePrincipal(1L, "app-1", "ERP", Set.of("assets.manage"),
                "app-1", "inst-1", "production", "token-1");
        AtomicReference<String> permissionSeen = new AtomicReference<>();
        when(commandExecutor.execute(eq("tasset:assign_asset"), any())).thenAnswer(invocation -> {
            permissionSeen.set(MetaContext.getExternalCommandPermission());
            return CommandExecuteResult.builder().commandCode("tasset:assign_asset").data(Map.of()).build();
        });
        when(dataService.getById("tasset_asset", "asset-1")).thenReturn(Map.of(
                "pid", "asset-1", "tasset_as_code", "A-1", "tasset_as_status", "in_use"));

        Map<String, Object> result = controller.executeCommand("assets.assign", "idem-key-0001",
                Map.of("targetPid", "asset-1", "input", Map.of("assignee", "alice")), principal);

        assertThat(permissionSeen).hasValue("tasset.asset.manage");
        assertThat(MetaContext.getExternalCommandPermission()).isNull();
        ArgumentCaptor<CommandExecuteRequest> request = ArgumentCaptor.forClass(CommandExecuteRequest.class);
        verify(commandExecutor).execute(eq("tasset:assign_asset"), request.capture());
        assertThat(request.getValue().getClientRequestId())
                .isEqualTo("open-api:inst-1:assets.assign:idem-key-0001");
        assertThat(request.getValue().getPayload()).containsEntry("tasset_as_assigned_to", "alice");
        assertThat(result).extractingByKey("command").isEqualTo("assets.assign");
    }

    @Test
    void rejectsUnpublishedCommandAndWeakIdempotencyKey() {
        MachinePrincipal principal = new MachinePrincipal(1L, "app-1", "ERP", Set.of("assets.manage"));
        assertThatThrownBy(() -> controller.executeCommand("assets.assign", "short",
                Map.of("targetPid", "asset-1", "input", Map.of("assignee", "alice")), principal))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Idempotency-Key");
        assertThatThrownBy(() -> controller.executeCommand("tasset:return_asset", "idem-key-0002",
                Map.of("targetPid", "asset-1", "input", Map.of()), principal))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not published");
    }

    @Test
    void mapsFacadeValidationFailuresToStablePublicError() {
        var response = controller.invalidRequest(new IllegalArgumentException("Invalid Idempotency-Key"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("success", false)
                .containsEntry("code", "invalid_request")
                .containsEntry("message", "Invalid Idempotency-Key");
    }
}
