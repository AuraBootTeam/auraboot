package com.auraboot.framework.openplatform.controller;

import com.auraboot.framework.application.security.ExternalMachineAuthenticator.MachinePrincipal;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.openplatform.service.OpenApiPublicationRegistry;
import com.auraboot.framework.openplatform.service.OpenApiProtocolTokenCodec;
import com.auraboot.framework.openplatform.service.OpenApiEventCatalog;
import com.auraboot.framework.openplatform.service.OpenApiEventPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Clock;
import java.util.List;

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
    private final OpenApiPublicationRegistry publications = new OpenApiPublicationRegistry();
    private final OpenApiProtocolTokenCodec tokenCodec = new OpenApiProtocolTokenCodec(
            "unit-test-open-platform-signing-key-at-least-32-bytes", "test", Clock.systemUTC());
    private final OpenApiEventPublisher eventPublisher = mock(OpenApiEventPublisher.class);
    private final OpenPlatformFacadeController controller = new OpenPlatformFacadeController(
            publications, dataService, commandExecutor, tokenCodec, new OpenApiEventCatalog(), eventPublisher);

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
                "pid", "asset-1", "row_version", 2, "tasset_as_code", "A-1", "tasset_as_status", "in_use"));

        String etag = tokenCodec.encodeEtag("assets", "asset-1", 1);

        Map<String, Object> result = controller.executeCommand("assets.assign", "idem-key-0001", etag,
                Map.of("targetPid", "asset-1", "input", Map.of("assignee", "alice")), principal);

        assertThat(permissionSeen).hasValue("tasset.asset.manage");
        assertThat(MetaContext.getExternalCommandPermission()).isNull();
        ArgumentCaptor<CommandExecuteRequest> request = ArgumentCaptor.forClass(CommandExecuteRequest.class);
        verify(commandExecutor).execute(eq("tasset:assign_asset"), request.capture());
        assertThat(request.getValue().getClientRequestId())
                .isEqualTo("open-api:inst-1:assets.assign:idem-key-0001");
        assertThat(request.getValue().getPayload()).containsEntry("tasset_as_assigned_to", "alice");
        assertThat(request.getValue().getExpectedVersion()).isEqualTo(1);
        assertThat(result).extractingByKey("command").isEqualTo("assets.assign");
        assertThat(result).containsKey("etag");
        verify(eventPublisher).publish(eq("assets.assignment.changed"), eq(1), eq("asset-1"), any(), eq(1L));
    }

    @Test
    void rejectsUnpublishedCommandAndWeakIdempotencyKey() {
        MachinePrincipal principal = new MachinePrincipal(1L, "app-1", "ERP", Set.of("assets.manage"));
        String etag = tokenCodec.encodeEtag("assets", "asset-1", 1);
        assertThatThrownBy(() -> controller.executeCommand("assets.assign", "short", etag,
                Map.of("targetPid", "asset-1", "input", Map.of("assignee", "alice")), principal))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Idempotency-Key");
        assertThatThrownBy(() -> controller.executeCommand("tasset:return_asset", "idem-key-0002", etag,
                Map.of("targetPid", "asset-1", "input", Map.of()), principal))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not published");
    }

    @Test
    void requiresStrongResourceBoundIfMatch() {
        MachinePrincipal principal = new MachinePrincipal(1L, "app-1", "ERP", Set.of("assets.manage"));
        assertThatThrownBy(() -> controller.executeCommand("assets.assign", "idem-key-0003", null,
                Map.of("targetPid", "asset-1", "input", Map.of("assignee", "alice")), principal))
                .isInstanceOf(com.auraboot.framework.openplatform.service.OpenApiPreconditionException.class);
        String other = tokenCodec.encodeEtag("assets", "asset-2", 1);
        assertThatThrownBy(() -> controller.executeCommand("assets.assign", "idem-key-0004", other,
                Map.of("targetPid", "asset-1", "input", Map.of("assignee", "alice")), principal))
                .isInstanceOf(com.auraboot.framework.openplatform.service.OpenApiPreconditionException.class);
    }

    @Test
    void listsProjectedResourcesWithAuthenticatedOpaqueContinuation() {
        when(dataService.list(eq("tasset_asset"), any())).thenReturn(PaginationResult.ofCursor(List.of(
                Map.of("pid", "asset-1", "tasset_as_code", "A-1", "private", "x"),
                Map.of("pid", "asset-2", "tasset_as_code", "A-2", "private", "y")),
                2L, 2, "asset-2"));

        Map<String, Object> first = controller.listResources("assets", 1, null);

        assertThat(first).containsEntry("hasMore", true);
        assertThat(String.valueOf(first.get("nextCursor"))).doesNotContain("asset-1");
        assertThat((List<?>) first.get("items")).hasSize(1);
        String cursor = String.valueOf(first.get("nextCursor"));

        controller.listResources("assets", 1, cursor);
        ArgumentCaptor<DynamicQueryRequest> request = ArgumentCaptor.forClass(DynamicQueryRequest.class);
        verify(dataService, org.mockito.Mockito.times(2)).list(eq("tasset_asset"), request.capture());
        assertThat(request.getAllValues().getFirst().getCursor()).isEmpty();
        assertThat(request.getAllValues().get(1).getCursor()).isEqualTo("asset-1");
        assertThat(request.getAllValues().get(1).getPageSize()).isEqualTo(2);
        assertThatThrownBy(() -> controller.listResources("inventory.stock-ins", 1, cursor))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("resource schema");
    }

    @Test
    void resourceReadReturnsStrongBoundEtagWithoutLeakingRowVersion() {
        when(dataService.getById("tasset_asset", "asset-1")).thenReturn(Map.of(
                "pid", "asset-1", "row_version", 4, "tasset_as_code", "A-1"));

        var response = controller.getResource("assets", "asset-1");

        assertThat(response.getHeaders().getETag()).startsWith("\"ab1.").endsWith("\"");
        assertThat(response.getBody()).containsEntry("pid", "asset-1").doesNotContainKey("row_version");
        assertThat(tokenCodec.decodeEtag(response.getHeaders().getETag(), "assets", "asset-1")).isEqualTo(4);
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
