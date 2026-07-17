package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.AsyncTaskDTO;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.DataDomainService;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.meta.service.impl.AsyncTaskServiceImpl;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.meta.service.impl.pipeline.RecordSnapshotReader;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.infrastructure.storage.StorageProvider;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.FileAccessor;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.context.ApplicationContext;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HandlerPhaseTest {

    private static final String BUSINESS_COMMAND_CODE = "pr:submit_purchase_order";
    private static final String PLUGIN_HANDLER_CODE = "pr:start_approval_flow";

    @Mock
    private ApplicationContext applicationContext;

    @Mock
    private ExtensionRegistry extensionRegistry;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private DynamicDataService dynamicDataService;

    @Mock
    private DynamicDataMapper dynamicDataMapper;

    @Mock
    private MetaModelService metaModelService;

    @Mock
    private RecordSnapshotReader snapshotReader;

    @Mock
    private DataPermissionEngine dataPermissionEngine;

    @Mock
    private DataDomainService dataDomainService;

    @Mock
    private FileService fileService;

    @Mock
    private StorageProvider storageProvider;

    @Mock
    private AsyncTaskServiceImpl asyncTaskService;

    @InjectMocks
    private HandlerPhase phase;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(phase, "fileService", fileService);
        ReflectionTestUtils.setField(phase, "storageProvider", storageProvider);
        ReflectionTestUtils.setField(phase, "asyncTaskService", asyncTaskService);
        // Default: no chained secondaries (the common case). Individual tests override.
        lenient().when(extensionRegistry.getSecondaryCommandHandlers(any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void execute_usesConfiguredPluginHandlerAndPassesHandlerParamsAsSettings() throws Exception {
        RecordingPluginHandler handler = new RecordingPluginHandler(PLUGIN_HANDLER_CODE);
        when(extensionRegistry.getCommandHandler(PLUGIN_HANDLER_CODE)).thenReturn(Optional.of(handler));
        when(metaModelService.getModelDefinition("pr_purchase_order")).thenReturn(Optional.empty());

        CommandPipelineContext ctx = buildContext(BUSINESS_COMMAND_CODE, "pr_purchase_order", Map.of(
                "type", "state_transition",
                "handler", PLUGIN_HANDLER_CODE,
                "handlerParams", Map.of(
                        "processKey", "po_approval",
                        "amountField", "pr_po_total_amount",
                        "statusField", "pr_po_status"
                )
        ));

        phase.execute(ctx);

        verify(extensionRegistry).getCommandHandler(PLUGIN_HANDLER_CODE);
        verify(extensionRegistry, never()).getCommandHandler(BUSINESS_COMMAND_CODE);
        assertThat(handler.capturedContext.get()).isNotNull();
        assertThat(handler.capturedContext.get().commandType()).isEqualTo(PLUGIN_HANDLER_CODE);
        assertThat(handler.capturedContext.get().namespace()).isEqualTo("pr");
        assertThat(handler.capturedContext.get().recordId()).isEqualTo("po-1");
        assertThat(handler.capturedContext.get().settings())
                .containsEntry("processKey", "po_approval")
                .containsEntry("amountField", "pr_po_total_amount")
                .containsEntry("statusField", "pr_po_status")
                .containsEntry("__commandCode", BUSINESS_COMMAND_CODE)
                .containsEntry("__handlerCode", PLUGIN_HANDLER_CODE)
                .containsKey(CommandHandlerExtension.DATA_ACCESSOR_KEY)
                .containsKey(CommandHandlerExtension.FILE_ACCESSOR_KEY);
        assertThat(handler.capturedContext.get().fileAccessor()).isInstanceOf(FileAccessor.class);
        assertThat(ctx.getHandlerResults()).containsEntry("observedProcessKey", "po_approval");
    }

    @Test
    void execute_runsChainedSecondaryAfterPrimary_sharingTheSameContext() throws Exception {
        RecordingPluginHandler primary = new RecordingPluginHandler(PLUGIN_HANDLER_CODE);
        RecordingSecondaryHandler secondary = new RecordingSecondaryHandler();
        when(extensionRegistry.getCommandHandler(PLUGIN_HANDLER_CODE)).thenReturn(Optional.of(primary));
        when(extensionRegistry.getSecondaryCommandHandlers(PLUGIN_HANDLER_CODE))
                .thenReturn(List.of(secondary));
        when(metaModelService.getModelDefinition("pr_purchase_order")).thenReturn(Optional.empty());

        CommandPipelineContext ctx = buildContext(BUSINESS_COMMAND_CODE, "pr_purchase_order", Map.of(
                "type", "state_transition", "handler", PLUGIN_HANDLER_CODE));

        phase.execute(ctx);

        // both the primary and the chained secondary ran, against the same record/context
        assertThat(primary.capturedContext.get()).isNotNull();
        assertThat(secondary.capturedContext.get()).isNotNull();
        assertThat(secondary.capturedContext.get().recordId()).isEqualTo("po-1");
        assertThat(secondary.capturedContext.get().settings())
                .containsKey(CommandHandlerExtension.DATA_ACCESSOR_KEY);
        // the secondary's result is merged into handlerResults (without clobbering the primary's)
        assertThat(ctx.getHandlerResults())
                .containsEntry("observedCommandType", PLUGIN_HANDLER_CODE) // from the primary
                .containsEntry("secondaryRan", true);                      // from the secondary
    }

    @Test
    void execute_runsPluginHandlersInsideDynamicDataQueryScopeAndClosesIt() throws Exception {
        ScopeRecordingHandler handler = new ScopeRecordingHandler(PLUGIN_HANDLER_CODE);
        when(extensionRegistry.getCommandHandler(PLUGIN_HANDLER_CODE)).thenReturn(Optional.of(handler));
        when(metaModelService.getModelDefinition("pr_purchase_order")).thenReturn(Optional.empty());

        CommandPipelineContext ctx = buildContext(BUSINESS_COMMAND_CODE, "pr_purchase_order", Map.of(
                "type", "state_transition", "handler", PLUGIN_HANDLER_CODE));

        phase.execute(ctx);

        assertThat(handler.scopeActiveDuringExecute.get()).isTrue();
        assertThat(com.auraboot.framework.meta.service.impl.DynamicDataQueryScope.isActive()).isFalse();
    }

    @Test
    void execute_chainedSecondaryFailure_propagatesAndAbortsTheCommand() {
        RecordingPluginHandler primary = new RecordingPluginHandler(PLUGIN_HANDLER_CODE);
        CommandHandlerExtension boom = new CommandHandlerExtension() {
            @Override public String getCommandType() { return PLUGIN_HANDLER_CODE; }
            @Override public boolean chainsAfterPrimary() { return true; }
            @Override public Object execute(CommandContext context) {
                throw new IllegalStateException("over credit limit");
            }
        };
        when(extensionRegistry.getCommandHandler(PLUGIN_HANDLER_CODE)).thenReturn(Optional.of(primary));
        when(extensionRegistry.getSecondaryCommandHandlers(PLUGIN_HANDLER_CODE))
                .thenReturn(List.of(boom));
        // (no metaModelService stub: the secondary throws before result persistence runs)

        CommandPipelineContext ctx = buildContext(BUSINESS_COMMAND_CODE, "pr_purchase_order", Map.of(
                "type", "state_transition", "handler", PLUGIN_HANDLER_CODE));

        // a failing secondary aborts the whole command (so the transaction rolls back)
        assertThatThrownBy(() -> phase.execute(ctx))
                .hasMessageContaining("over credit limit");
        assertThat(com.auraboot.framework.meta.service.impl.DynamicDataQueryScope.isActive()).isFalse();
    }

    @Test
    void execute_mapsPluginVersionConflictToHttpConflictSemantic() {
        CommandHandlerExtension staleWrite = new CommandHandlerExtension() {
            @Override public String getCommandType() { return PLUGIN_HANDLER_CODE; }
            @Override public Object execute(CommandContext context) {
                throw new IllegalStateException("iot.error.version_conflict:expected=2,actual=3");
            }
        };
        when(extensionRegistry.getCommandHandler(PLUGIN_HANDLER_CODE)).thenReturn(Optional.of(staleWrite));

        CommandPipelineContext ctx = buildContext(BUSINESS_COMMAND_CODE, "pr_purchase_order", Map.of(
                "type", "state_transition", "handler", PLUGIN_HANDLER_CODE));

        assertThatThrownBy(() -> phase.execute(ctx))
                .isInstanceOf(com.auraboot.framework.exception.ConflictException.class)
                .hasMessageContaining("iot.error.version_conflict");
        assertThat(com.auraboot.framework.meta.service.impl.DynamicDataQueryScope.isActive()).isFalse();
    }

    @Test
    void execute_fallsBackToCommandCodeWhenNoConfiguredPluginHandler() throws Exception {
        RecordingPluginHandler handler = new RecordingPluginHandler(BUSINESS_COMMAND_CODE);
        when(extensionRegistry.getCommandHandler(BUSINESS_COMMAND_CODE)).thenReturn(Optional.of(handler));

        CommandPipelineContext ctx = buildContext(BUSINESS_COMMAND_CODE, null, Map.of("type", "custom"));

        phase.execute(ctx);

        verify(extensionRegistry).getCommandHandler(BUSINESS_COMMAND_CODE);
        assertThat(handler.capturedContext.get()).isNotNull();
        assertThat(handler.capturedContext.get().commandType()).isEqualTo(BUSINESS_COMMAND_CODE);
        assertThat(handler.capturedContext.get().settings())
                .containsEntry("__commandCode", BUSINESS_COMMAND_CODE)
                .containsEntry("__handlerCode", BUSINESS_COMMAND_CODE);
    }

    @Test
    void execute_dispatchesAsyncWhenHandlerParamsAsyncTrue() throws Exception {
        RecordingPluginHandler handler = new RecordingPluginHandler(PLUGIN_HANDLER_CODE);
        when(extensionRegistry.getCommandHandler(PLUGIN_HANDLER_CODE)).thenReturn(Optional.of(handler));
        AsyncTaskDTO dto = new AsyncTaskDTO();
        dto.setTaskCode("TASK-ASYNC-1");
        when(asyncTaskService.submitTask(any(), eq(1L), eq(2L))).thenReturn(dto);

        CommandPipelineContext ctx = buildContext(BUSINESS_COMMAND_CODE, "pr_purchase_order", Map.of(
                "type", "custom",
                "handler", PLUGIN_HANDLER_CODE,
                "handlerParams", Map.of("async", true)
        ));

        phase.execute(ctx);

        // Handler must NOT run inline on the request thread.
        assertThat(handler.capturedContext.get()).isNull();
        assertThat(ctx.getHandlerResults())
                .containsEntry("async", true)
                .containsEntry("taskCode", "TASK-ASYNC-1")
                .containsEntry("taskType", "command-handler")
                // The async envelope surfaces the target record pid so a form that
                // submits a model-bound async command can redirect to the new
                // record's detail page instead of falling back to the list route.
                .containsEntry("recordPid", "po-1");
        verify(asyncTaskService).submitTask(any(), eq(1L), eq(2L));
    }

    @Test
    void execute_runsInlineUnderDryRunEvenWhenAsyncDeclared() throws Exception {
        RecordingPluginHandler handler = new RecordingPluginHandler(PLUGIN_HANDLER_CODE);
        when(extensionRegistry.getCommandHandler(PLUGIN_HANDLER_CODE)).thenReturn(Optional.of(handler));

        CommandPipelineContext ctx = buildContext(BUSINESS_COMMAND_CODE, "pr_purchase_order", Map.of(
                "type", "custom",
                "handler", PLUGIN_HANDLER_CODE,
                "handlerParams", Map.of("async", true)
        ));
        ctx.getRequest().setDryRun(true);

        phase.execute(ctx);

        // Dry-run never goes async — it must stay inside the JDBC rollback envelope.
        verify(asyncTaskService, never()).submitTask(any(), any(), any());
    }

    /**
     * Regression for PB-2: a custom handler that returns a full record map (lifecycle
     * status-transition handlers do exactly this — they return the whole updated row)
     * must persist jsonb host columns via {@code updateWithJsonb}, NOT the plain
     * {@code update} provider. Before the fix, a jsonb column in the handler result
     * (e.g. cr_cj_seed_urls) was bound as varchar → "column is of type jsonb but
     * expression is of type character varying".
     */
    @Test
    void persistHandlerResults_usesJsonbAwareUpdateWhenResultIncludesJsonbColumn() throws Exception {
        RecordingFullRowHandler handler = new RecordingFullRowHandler(BUSINESS_COMMAND_CODE);
        when(extensionRegistry.getCommandHandler(BUSINESS_COMMAND_CODE)).thenReturn(Optional.of(handler));

        String modelCode = "cr_crawl_job";
        String tableName = "mt_cr_crawl_job";

        com.auraboot.framework.meta.dto.FieldDefinition statusField =
                com.auraboot.framework.meta.dto.FieldDefinition.builder()
                        .code("cr_cj_status").columnName("cr_cj_status").dataType("string").build();
        com.auraboot.framework.meta.dto.FieldDefinition seedUrlsField =
                com.auraboot.framework.meta.dto.FieldDefinition.builder()
                        .code("cr_cj_seed_urls").columnName("cr_cj_seed_urls").dataType("array").build(); // jsonb host
        com.auraboot.framework.meta.dto.ModelDefinition model =
                com.auraboot.framework.meta.dto.ModelDefinition.builder()
                        .code(modelCode).tableName(tableName)
                        .fields(java.util.List.of(statusField, seedUrlsField)).build();

        when(metaModelService.getModelDefinition(modelCode)).thenReturn(Optional.of(model));
        when(metaModelService.getTableName(modelCode)).thenReturn(tableName);
        when(dynamicDataMapper.findJsonbColumns(tableName)).thenReturn(java.util.Set.of("cr_cj_seed_urls"));
        // pid → db id lookup
        when(dynamicDataMapper.selectByQuery(any(), any()))
                .thenReturn(java.util.List.of(Map.of("id", 42L)));

        CommandPipelineContext ctx = buildContext(BUSINESS_COMMAND_CODE, modelCode, Map.of("type", "custom"));
        phase.execute(ctx);

        // jsonb-aware path must be taken (cr_cj_seed_urls is jsonb)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Set<String>> jsonbCaptor = ArgumentCaptor.forClass(java.util.Set.class);
        verify(dynamicDataMapper).updateWithJsonb(eq(tableName), any(), any(), jsonbCaptor.capture());
        assertThat(jsonbCaptor.getValue()).contains("cr_cj_seed_urls");
        verify(dynamicDataMapper, never()).update(eq(tableName), any(), any());
    }

    @Test
    void persistHandlerResults_usesScopedSqlGuardsWhenUserContextExists() throws Exception {
        MetaContext.setContext(1L, 2L, "user-pid", "tester");
        RecordingFullRowHandler handler = new RecordingFullRowHandler(BUSINESS_COMMAND_CODE);
        when(extensionRegistry.getCommandHandler(BUSINESS_COMMAND_CODE)).thenReturn(Optional.of(handler));

        String modelCode = "cr_crawl_job";
        String tableName = "mt_cr_crawl_job";
        com.auraboot.framework.meta.dto.FieldDefinition statusField =
                com.auraboot.framework.meta.dto.FieldDefinition.builder()
                        .code("cr_cj_status").columnName("cr_cj_status").dataType("string").build();
        com.auraboot.framework.meta.dto.FieldDefinition seedUrlsField =
                com.auraboot.framework.meta.dto.FieldDefinition.builder()
                        .code("cr_cj_seed_urls").columnName("cr_cj_seed_urls").dataType("array").build();
        com.auraboot.framework.meta.dto.ModelDefinition model =
                com.auraboot.framework.meta.dto.ModelDefinition.builder()
                        .code(modelCode).tableName(tableName)
                        .fields(java.util.List.of(statusField, seedUrlsField)).build();

        when(metaModelService.getModelDefinition(modelCode)).thenReturn(Optional.of(model));
        when(metaModelService.getTableName(modelCode)).thenReturn(tableName);
        when(dynamicDataMapper.findJsonbColumns(tableName)).thenReturn(java.util.Set.of("cr_cj_seed_urls"));
        when(dynamicDataMapper.selectByQuery(any(), any()))
                .thenReturn(java.util.List.of(Map.of("id", 42L)));
        when(dataPermissionEngine.buildRowFilter(1L, modelCode, 2L))
                .thenReturn("AND created_by = 2");
        when(dataDomainService.buildDomainFilter(modelCode, 2L))
                .thenReturn("AND domain_id = 7");
        when(dynamicDataMapper.updateByQuery(any(), any())).thenReturn(1);

        CommandPipelineContext ctx = buildContext(BUSINESS_COMMAND_CODE, modelCode, Map.of("type", "custom"));
        phase.execute(ctx);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(dynamicDataMapper).updateByQuery(sqlCaptor.capture(), paramsCaptor.capture());
        verify(dynamicDataMapper, never()).update(eq(tableName), any(), any());
        verify(dynamicDataMapper, never()).updateWithJsonb(eq(tableName), any(), any(), any());
        assertThat(sqlCaptor.getValue())
                .contains("UPDATE " + tableName + " SET")
                .containsPattern("cr_cj_seed_urls = #\\{params\\.set\\d+,jdbcType=OTHER,typeHandler=.*JsonbStringTypeHandler}::jsonb")
                .contains("WHERE id = #{params.recordId}")
                .contains("tenant_id = #{params.tenantId}")
                .contains("created_by = 2")
                .contains("domain_id = 7");
        assertThat(paramsCaptor.getValue())
                .containsEntry("recordId", 42L)
                .containsEntry("tenantId", 1L)
                .containsValue("[\"https://example.com/\"]");
    }

    private CommandPipelineContext buildContext(String commandCode, String modelCode, Map<String, Object> execConfig) {
        CommandDefinition command = new CommandDefinition();
        command.setCode(commandCode);
        command.setModelCode(modelCode);

        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setPayload(Collections.emptyMap());
        request.setDryRun(false);
        request.setTargetRecordId("po-1");

        return CommandPipelineContext.builder()
                .commandCode(command.getCode())
                .request(request)
                .tenantId(1L)
                .userId(2L)
                .startTime(System.currentTimeMillis())
                .command(command)
                .payload(new HashMap<>())
                .execConfig(new HashMap<>(execConfig))
                .rulesByType(new HashMap<>())
                .fieldMapResults(new HashMap<>())
                .handlerResults(new HashMap<>())
                .build();
    }

    /**
     * Returns a full record map including a jsonb host column, mirroring what
     * lifecycle status-transition handlers return (the whole updated row).
     */
    private static class RecordingFullRowHandler implements CommandHandlerExtension {

        private final String commandType;

        private RecordingFullRowHandler(String commandType) {
            this.commandType = commandType;
        }

        @Override
        public String getCommandType() {
            return commandType;
        }

        @Override
        public Object execute(CommandContext context) {
            Map<String, Object> fullRow = new HashMap<>();
            fullRow.put("cr_cj_status", "PAUSED");
            // A handler may return a structured value instead of a JDBC-ready
            // string. The final scoped SQL binding must serialize it explicitly.
            fullRow.put("cr_cj_seed_urls", java.util.List.of("https://example.com/"));
            return fullRow;
        }
    }

    private static class RecordingPluginHandler implements CommandHandlerExtension {

        private final String commandType;
        private final AtomicReference<CommandContext> capturedContext = new AtomicReference<>();

        private RecordingPluginHandler(String commandType) {
            this.commandType = commandType;
        }

        @Override
        public String getCommandType() {
            return commandType;
        }

        @Override
        public Object execute(CommandContext context) {
            capturedContext.set(context);
            Map<String, Object> result = new HashMap<>();
            if (context.settings().containsKey("processKey")) {
                result.put("observedProcessKey", context.settings().get("processKey"));
            }
            result.put("observedCommandType", context.commandType());
            return result;
        }
    }

    /** A chained secondary that records its context and returns a distinct result key. */
    private static class RecordingSecondaryHandler implements CommandHandlerExtension {

        private final AtomicReference<CommandContext> capturedContext = new AtomicReference<>();

        @Override
        public String getCommandType() {
            return PLUGIN_HANDLER_CODE;
        }

        @Override
        public boolean chainsAfterPrimary() {
            return true;
        }

        @Override
        public Object execute(CommandContext context) {
            capturedContext.set(context);
            Map<String, Object> result = new HashMap<>();
            result.put("secondaryRan", true);
            // attempt to clobber a primary key — the merge is putIfAbsent, so the primary wins
            result.put("observedCommandType", "SECONDARY_SHOULD_NOT_WIN");
            return result;
        }
    }

    private static class ScopeRecordingHandler implements CommandHandlerExtension {

        private final String commandType;
        private final AtomicReference<Boolean> scopeActiveDuringExecute = new AtomicReference<>(false);

        private ScopeRecordingHandler(String commandType) {
            this.commandType = commandType;
        }

        @Override
        public String getCommandType() {
            return commandType;
        }

        @Override
        public Object execute(CommandContext context) {
            scopeActiveDuringExecute.set(
                    com.auraboot.framework.meta.service.impl.DynamicDataQueryScope.isActive());
            return Map.of("scopeObserved", true);
        }
    }
}
