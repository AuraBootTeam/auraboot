package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.connector.service.ApiConnectorService;
import com.auraboot.framework.entitlement.spi.EntitlementChecker;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.exception.ConflictException;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.mapper.BindingRuleMapper;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.ChangeTracker;
import com.auraboot.framework.meta.service.ConcurrencyGuard;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.IdempotencyService;
import com.auraboot.framework.meta.service.InvariantEngine;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.ValidationService;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipeline;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.meta.service.impl.pipeline.RecordSnapshotReader;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import com.auraboot.framework.webhook.service.WebhookDispatcher;
import com.auraboot.module.meta.event.DomainEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CommandExecutorFailureAuditTest {

    @Mock
    private CommandDefinitionMapper commandDefinitionMapper;

    @Mock
    private BindingRuleMapper bindingRuleMapper;

    @Mock
    private CommandMetadataCacheService commandMetadataCache;

    @Mock
    private DynamicDataMapper dynamicDataMapper;

    @Mock
    private DynamicDataService dynamicDataService;

    @Mock
    private ApplicationContext applicationContext;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private MetaModelService metaModelService;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private ConcurrencyGuard concurrencyGuard;

    @Mock
    private InvariantEngine invariantEngine;

    @Mock
    private ChangeTracker changeTracker;

    @Mock
    private WebhookDispatcher webhookDispatcher;

    @Mock
    private ApiConnectorService apiConnectorService;

    @Mock
    private ExtensionRegistry extensionRegistry;

    @Mock
    private CommandSpelEvaluator spelEvaluator;

    @Mock
    private CommandCascadeDeleteExecutor cascadeDeleteExecutor;

    @Mock
    private CommandSideEffectExecutor sideEffectExecutor;

    @Mock
    private DomainEventPublisher domainEventPublisher;

    @Mock
    private CommandFieldMapExecutor fieldMapExecutor;

    @Mock
    private CommandStateCheckExecutor stateCheckExecutor;

    @Mock
    private CommandEffectExecutor effectExecutor;

    @Mock
    private CommandAutoSetExecutor autoSetExecutor;

    @Mock
    private ValidationService validationService;

    @Mock
    private EntitlementChecker entitlementChecker;

    @Mock
    private RollUpFieldRegistry rollUpFieldRegistry;

    @Mock
    private RollUpSummaryService rollUpSummaryService;

    @Mock
    private PayloadTemporalNormalizer payloadTemporalNormalizer;

    @Mock
    private RecordSnapshotReader recordSnapshotReader;

    @Mock
    private CommandPipeline commandPipeline;

    @InjectMocks
    private CommandExecutorImpl commandExecutor;

    @Captor
    private ArgumentCaptor<Map<String, Object>> requestPayloadCaptor;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(commandExecutor, "commandPipeline", commandPipeline);
    }

    @AfterEach
    void clearMetaContext() {
        MetaContext.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void executePersistsAuditContextWhenAuthorizationPhaseRejectsCommand() {
        MetaContext.setContext(77L, 42L, "user_42", "Operator");
        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setPayload(new LinkedHashMap<>(Map.of("name", "export")));
        request.setAuditContext(new LinkedHashMap<>(Map.of(
                "source", "unified-designer-runtime-preview",
                "pageId", "page_schema_list",
                "blockId", "action_export",
                "permissionCode", "dashboard.manage")));

        org.mockito.Mockito.doAnswer(invocation -> {
                    CommandPipelineContext context = invocation.getArgument(0);
                    context.transitionTo("authorization");
                    throw new BusinessException(
                            ResponseCode.FORBIDDEN,
                            "Command permission denied: required one of dashboard.manage");
                })
                .when(commandPipeline)
                .executePreGuardPhases(any(CommandPipelineContext.class));

        assertThatThrownBy(() -> commandExecutor.execute("dashboard.export", request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Command permission denied")
                .extracting("responseCode")
                .isEqualTo(ResponseCode.FORBIDDEN);

        verify(effectExecutor).saveAuditLog(
                eq(77L),
                eq("dashboard.export"),
                isNull(),
                eq(42L),
                requestPayloadCaptor.capture(),
                isNull(),
                eq(false),
                contains("Command permission denied"),
                anyLong(),
                eq("authorization"),
                any());

        Map<String, Object> auditPayload = requestPayloadCaptor.getValue();
        assertThat(auditPayload).containsEntry("name", "export");
        assertThat((Map<String, Object>) auditPayload.get(CommandAuditPayloads.AUDIT_CONTEXT_KEY))
                .containsEntry("source", "unified-designer-runtime-preview")
                .containsEntry("pageId", "page_schema_list")
                .containsEntry("blockId", "action_export")
                .containsEntry("permissionCode", "dashboard.manage");
    }

    @Test
    void executePreservesConflictExceptionForHttp409Mapping() {
        MetaContext.setContext(77L, 42L, "user_42", "Operator");
        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setPayload(Map.of("expectedVersion", 2));

        org.mockito.Mockito.doThrow(new ConflictException(
                        "iot.error.version_conflict:expected=2,actual=3"))
                .when(commandPipeline)
                .executeGuardedPhases(any(CommandPipelineContext.class));

        assertThatThrownBy(() -> commandExecutor.execute("iot.task.transition", request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("iot.error.version_conflict");

        verify(effectExecutor).saveAuditLog(
                eq(77L), eq("iot.task.transition"), isNull(), eq(42L), any(), isNull(),
                eq(false), contains("iot.error.version_conflict"), anyLong(), any(), any());
    }
}
