package com.auraboot.framework.meta.synthesized;

import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension.CommandContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DynamicWriteCommandHandlerTest {

    @Mock
    private DynamicDataService dynamicDataService;
    @Mock
    private UserPermissionService userPermissionService;

    private DynamicWriteCommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new DynamicWriteCommandHandler(dynamicDataService, userPermissionService);
        com.auraboot.framework.application.tenant.MetaContext.setContext(42L, 7L, "tester", "Tester");
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        com.auraboot.framework.application.tenant.MetaContext.clear();
    }

    private CommandContext ctx(String commandType, Map<String, Object> payload) {
        return new CommandContext(42L, "platform", "dynamic", commandType, "m1", (String) payload.get("recordPid"), payload, Map.of(), false);
    }

    // ===== dispatch =====

    @Test
    void create_dispatchesWithModelAndData() {
        handler.execute(ctx(DynamicWriteCommandHandler.CREATE,
                Map.of("modelCode", "m1", "data", Map.of("name", "x"))));
        org.mockito.Mockito.verify(dynamicDataService).create("m1", Map.of("name", "x"));
    }

    @Test
    void update_usesRecordIdAndTranslatesExpectedVersion() {
        Map<String, Object> data = new java.util.HashMap<>(Map.of("name", "y"));
        handler.execute(ctx(DynamicWriteCommandHandler.UPDATE,
                Map.of("modelCode", "m1", "data", data, "recordPid", "rp1", "expectedVersion", 7)));
        org.mockito.Mockito.verify(dynamicDataService).update(eq("m1"), eq("rp1"),
                org.mockito.ArgumentMatchers.argThat(m -> Long.valueOf(7).equals(m.get("_expectedVersion"))));
    }

    @Test
    void delete_dispatchesAndReturnsNull() {
        Object result = handler.execute(ctx(DynamicWriteCommandHandler.DELETE,
                Map.of("modelCode", "m1", "recordPid", "rp1")));
        assertThat(result).isNull();
        org.mockito.Mockito.verify(dynamicDataService).delete("m1", "rp1");
    }

    @Test
    void batchDispatch_delegateToLists() {
        java.util.List<Map<String, Object>> dataList = java.util.List.of(new java.util.HashMap<>(Map.of("name", "a")));
        handler.execute(ctx(DynamicWriteCommandHandler.BATCH_CREATE,
                Map.of("modelCode", "m1", "dataList", dataList)));
        org.mockito.Mockito.verify(dynamicDataService).batchCreate("m1", dataList);

        handler.execute(ctx(DynamicWriteCommandHandler.BATCH_DELETE,
                Map.of("modelCode", "m1", "recordPids", java.util.List.of("r1", "r2"))));
        org.mockito.Mockito.verify(dynamicDataService).batchDelete("m1", java.util.List.of("r1", "r2"));
    }

    @Test
    void unknownCommandType_throws() {
        assertThatThrownBy(() -> handler.execute(ctx("dynamic.no_such", Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ownsPersistence_dslPersistenceDisabled() {
        assertThat(handler.requiresDslPersistence(DynamicWriteCommandHandler.UPDATE, Map.of(), null)).isFalse();
    }

    // ===== invocation authorization mirrors the HTTP-layer permission codes =====

    @Test
    void authorizeInvocation_derivesModelPagePermissionAndAllows() {
        when(userPermissionService.hasPermission(7L, "model.orders.update")).thenReturn(true);
        var invocation = new com.auraboot.framework.plugin.extension.CommandInvocationAuthorizer.InvocationContext(
                42L, 7L, "user-pid", "dynamic.update.orders", DynamicWriteCommandHandler.UPDATE,
                "orders", "rp1", Map.of("pageKey", "orders", "modelCode", "orders", "recordPid", "rp1"));
        assertThat(handler.authorizeInvocation(invocation)).isTrue();
    }

    @Test
    void authorizeInvocation_deniesWhenPermissionMissing() {
        when(userPermissionService.hasPermission(7L, "model.orders.update")).thenReturn(false);
        var invocation = new com.auraboot.framework.plugin.extension.CommandInvocationAuthorizer.InvocationContext(
                42L, 7L, "user-pid", "dynamic.update.orders", DynamicWriteCommandHandler.UPDATE,
                "orders", "rp1", Map.of("pageKey", "orders", "modelCode", "orders", "recordPid", "rp1"));
        assertThat(handler.authorizeInvocation(invocation)).isFalse();
    }

    @Test
    void authorizeInvocation_deniesBlankPageKeyPayload() {
        var invocation = new com.auraboot.framework.plugin.extension.CommandInvocationAuthorizer.InvocationContext(
                42L, 7L, "user-pid", DynamicWriteCommandHandler.CREATE, DynamicWriteCommandHandler.HANDLER_CODE,
                null, null, Map.of());
        assertThat(handler.authorizeInvocation(invocation)).isFalse();
        verifyNoInteractions(dynamicDataService);
    }

    @Test
    void invocationAndExecutionRejectAClientModelDifferentFromTheDefinition() {
        var invocation = new com.auraboot.framework.plugin.extension.CommandInvocationAuthorizer.InvocationContext(
                42L, 7L, "user-pid", "dynamic.update.orders", DynamicWriteCommandHandler.UPDATE,
                "orders", "r1", Map.of("modelCode", "private", "pageKey", "orders"));
        assertThat(handler.authorizeInvocation(invocation)).isFalse();
        assertThatThrownBy(() -> handler.execute(ctx(DynamicWriteCommandHandler.UPDATE,
                Map.of("modelCode", "private", "data", Map.of("name", "x"), "recordPid", "r1"))))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(dynamicDataService, userPermissionService);
    }

    @Test
    void aPayloadRecordCannotReplaceTheTargetCheckedByThePipeline() {
        for (String target : new String[] {"", "other"}) {
            var invocation = new com.auraboot.framework.plugin.extension.CommandInvocationAuthorizer.InvocationContext(
                    42L, 7L, "user-pid", "dynamic.update.orders", DynamicWriteCommandHandler.UPDATE,
                    "orders", target, Map.of("modelCode", "orders", "recordPid", "r1"));
            assertThat(handler.authorizeInvocation(invocation)).isFalse();
        }
        var bypass = new CommandContext(42L, "platform", "dynamic", DynamicWriteCommandHandler.DELETE,
                "m1", null, Map.of("modelCode", "m1", "recordPid", "r1"), Map.of(), false);
        assertThatThrownBy(() -> handler.execute(bypass)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(dynamicDataService, userPermissionService);
    }

    @Test
    void seededCommandsHaveRealModelBindingsAndTheRegisteredOperationHandler() throws Exception {
        var mapper = org.mockito.Mockito.mock(com.auraboot.framework.meta.mapper.CommandDefinitionMapper.class);
        when(mapper.insertIdempotent(any())).thenReturn(1);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var seeds = new DynamicWriteCommandSeedService(mapper, json);
        assertThat(seeds.ensureSeeded("dynamic.update", "orders")).isEqualTo("dynamic.update.orders");
        assertThat(seeds.ensureSeeded("dynamic.update", "invoices")).isEqualTo("dynamic.update.invoices");
        var commands = org.mockito.ArgumentCaptor.forClass(com.auraboot.framework.meta.entity.CommandDefinition.class);
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.times(2)).insertIdempotent(commands.capture());
        assertThat(commands.getAllValues()).extracting(com.auraboot.framework.meta.entity.CommandDefinition::getModelCode)
                .containsExactly("orders", "invoices");
        for (var command : commands.getAllValues()) {
            assertThat(json.readTree(command.getTargetModels()).get(0).asText()).isEqualTo(command.getModelCode());
            var config = json.readTree(command.getExecutionConfig());
            assertThat(config.get("handler").asText()).isEqualTo(DynamicWriteCommandHandler.UPDATE);
            assertThat(handler.supports(config.get("handler").asText())).isTrue();
            assertThat(config.get("type").asText()).isEqualTo("update");
        }
    }

    @Test
    void concurrentSeedChecksTheWinnerAndNeverOverwritesAnotherModel() throws Exception {
        var mapper = org.mockito.Mockito.mock(com.auraboot.framework.meta.mapper.CommandDefinitionMapper.class);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var existing = new com.auraboot.framework.meta.entity.CommandDefinition();
        existing.setModelCode("orders");
        existing.setStatus(com.auraboot.framework.common.constant.StatusConstants.PUBLISHED);
        existing.setExecutionConfig("{ \"type\": \"update\", \"handlerParams\": {\"invocationAuthorization\": \"handler\"}, \"handler\": \"dynamic.update\" }");
        when(mapper.findCurrentByCode("dynamic.update.orders")).thenReturn(null, existing);
        when(mapper.insertIdempotent(any())).thenReturn(0);
        var seeds = new DynamicWriteCommandSeedService(mapper, json);
        assertThat(seeds.ensureSeeded("dynamic.update", "orders")).isEqualTo("dynamic.update.orders");
        existing.setModelCode("private");
        assertThatThrownBy(() -> seeds.ensureSeeded("dynamic.update", "orders")).isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.times(1)).insertIdempotent(any());
    }

    @Test
    void unsupportedSeedDoesNotWriteMetadata() {
        var mapper = org.mockito.Mockito.mock(com.auraboot.framework.meta.mapper.CommandDefinitionMapper.class);
        var seeds = new DynamicWriteCommandSeedService(mapper, new com.fasterxml.jackson.databind.ObjectMapper());
        assertThatThrownBy(() -> seeds.ensureSeeded("dynamic.unknown", "orders")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> seeds.ensureSeeded("dynamic.update", "bad\"model")).isInstanceOf(IllegalArgumentException.class);
        org.mockito.Mockito.verifyNoInteractions(mapper);
    }
}
