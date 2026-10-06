package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.controller.PlatformAiController;
import com.auraboot.framework.agent.dto.*;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.QueryBuilderReadProtection;
import com.auraboot.framework.permission.engine.PermissionEvaluator;
import com.auraboot.framework.permission.engine.model.FieldPermissionSet;
import com.auraboot.framework.permission.service.FieldPermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Hermetic scoring-consumer tests; real source policies and persistence require integration evidence. */
@ExtendWith(MockitoExtension.class)
class PlatformAiScoringServiceTest {
    @Mock private LlmProviderFactory llmProviderFactory;
    @Mock private MetaModelService metaModelService;
    @Mock private DynamicDataService dynamicDataService;
    @Mock private QueryBuilderReadProtection readProtection;
    @Mock private PermissionEvaluator permissionEvaluator;
    @Mock private FieldPermissionService fieldPermissions;
    @Mock private LlmProvider llmProvider;
    @Mock private DataPermissionEngine dataPermissionEngine;
    private PlatformAiScoringServiceImpl service;
    private static final Long TENANT_ID = 1001L;
    private static final Long MEMBER_ID = 7L;
    private static final String MODEL_CODE = "test_model";
    private static final String TABLE_NAME = "mt_test_model";
    private static final String SCORE_FIELD = "ai_score";
    private static final Set<String> FIELD_CODES = Set.of("id", "pid", "name", "status", SCORE_FIELD, "created_at");

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, 3L, "test-user", "test-user");
        MetaContext.setMemberId(MEMBER_ID);
        service = new PlatformAiScoringServiceImpl(llmProviderFactory, metaModelService,
                dynamicDataService, readProtection, permissionEvaluator, fieldPermissions, dataPermissionEngine, new ObjectMapper());
        lenient().when(metaModelService.getModelDefinition(MODEL_CODE)).thenReturn(Optional.of(
                ModelDefinition.builder().code(MODEL_CODE).fields(FIELD_CODES.stream()
                        .map(code -> FieldDefinition.builder().code(code).columnName(code).build()).toList()).build()));
        lenient().when(metaModelService.getTableName(MODEL_CODE)).thenReturn(TABLE_NAME);
        lenient().when(metaModelService.getPrimaryKeyField(MODEL_CODE)).thenReturn(
                FieldDefinition.builder().code("id").columnName("id").primaryKey(true).build());
        lenient().when(permissionEvaluator.canAction(MEMBER_ID, MODEL_CODE, "read")).thenReturn(true);
        lenient().when(permissionEvaluator.canAction(MEMBER_ID, MODEL_CODE, "update")).thenReturn(true);
        lenient().when(fieldPermissions.getFieldPermissions(MEMBER_ID, MODEL_CODE))
                .thenReturn(FieldPermissionSet.allAllowed(FIELD_CODES));
        lenient().when(readProtection.prepareComparison(eq(MODEL_CODE), anyCollection(), anyMap(), eq(TABLE_NAME)))
                .thenReturn(new QueryBuilderReadProtection.Plan(Map.of(), null));
    }

    @AfterEach
    void clearContext() { MetaContext.clear(); }

    private PlatformAiScoreRequest buildRequest() {
        PlatformAiScoreRequest request = new PlatformAiScoreRequest();
        request.setModelCode(MODEL_CODE);
        request.setScoreField(SCORE_FIELD);
        request.setContextFields(List.of("name", "status"));
        var dimension = new PlatformAiScoreRequest.ScoringDimension();
        dimension.setFieldCode("name");
        dimension.setDescription("Evaluate completeness");
        dimension.setWeight(50);
        request.setScoringDimensions(List.of(dimension));
        return request;
    }

    private void providerReady() {
        var config = LlmProviderFactory.ProviderConfig.builder().providerCode("openai")
                .apiKey("test-key").baseUrl("https://api.openai.com").defaultModel("test-model").build();
        when(llmProviderFactory.resolveConfig(TENANT_ID, null)).thenReturn(config);
        when(llmProviderFactory.getProvider("openai")).thenReturn(llmProvider);
    }

    private void oneRecordAndResponse(String json) throws Exception {
        providerReady();
        when(readProtection.execute(any(), anyString(), anyMap())).thenReturn(List.of(
                Map.of("id", 101L, "pid", "pid001", "name", "Acme", "status", "active")));
        when(llmProvider.chat(any(), anyString(), anyString())).thenReturn(LlmChatResponse.builder()
                .content(List.of(LlmChatResponse.ContentBlock.builder().type("text").text(json).build()))
                .inputTokens(100).outputTokens(50).build());
    }

    @Test
    void score_shouldCallLlmAndWriteScoresBack() throws Exception {
        oneRecordAndResponse("[{\"id\":\"pid001\",\"score\":85}]");
        var result = service.score(buildRequest(), TENANT_ID);
        assertThat(result.getScoredCount()).isEqualTo(1);
        assertThat(result.getFailedCount()).isZero();
        assertThat(result.getScores()).containsExactlyEntriesOf(Map.of("pid001", 85));
        assertThat(result.getTotalInputTokens()).isEqualTo(100);
        assertThat(result.getTotalOutputTokens()).isEqualTo(50);
        verify(dynamicDataService).update(MODEL_CODE, "101", Map.of(SCORE_FIELD, 85));
        verify(readProtection).prepareComparison(eq(MODEL_CODE),
                argThat(fields -> fields.containsAll(List.of("pid", "id", "name", "status", "created_at"))), anyMap(), eq(TABLE_NAME));
    }

    @Test
    void score_rejectsImmutableModelBeforeProviderOrDataAccess() {
        when(metaModelService.getModelDefinition(MODEL_CODE)).thenReturn(Optional.of(
                ModelDefinition.builder().code(MODEL_CODE).immutable(true).build()));
        assertThatThrownBy(() -> service.score(buildRequest(), TENANT_ID))
                .isInstanceOf(MetaServiceException.class).hasMessageContaining("immutable");
        verifyNoInteractions(llmProviderFactory, readProtection, dynamicDataService);
    }

    @Test
    void score_rejectsDirectWriteToCommandOwnedScoreField() {
        when(metaModelService.getModelDefinition(MODEL_CODE)).thenReturn(Optional.of(
                ModelDefinition.builder().fields(List.of(FieldDefinition.builder().code(SCORE_FIELD)
                        .allowedWriterCommands(List.of("crm:recalculate_score")).build())).build()));
        assertThatThrownBy(() -> service.score(buildRequest(), TENANT_ID))
                .isInstanceOf(MetaServiceException.class).hasMessageContaining("FIELD_WRITER_DENIED");
        verifyNoInteractions(llmProviderFactory, readProtection, dynamicDataService);
    }

    @Test
    void score_shouldThrow_whenNoLlmProvider() {
        assertThatThrownBy(() -> service.score(buildRequest(), TENANT_ID))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("No LLM provider configured");
        verify(readProtection, never()).execute(any(), anyString(), anyMap());
    }

    @Test
    void score_shouldThrow_whenModelNotFound() {
        when(metaModelService.getModelDefinition(MODEL_CODE)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.score(buildRequest(), TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Model not found");
        verifyNoInteractions(llmProviderFactory, readProtection, dynamicDataService);
    }

    @Test
    void score_shouldHandleEmptyRecords() throws Exception {
        providerReady();
        when(readProtection.execute(any(), anyString(), anyMap())).thenReturn(List.of());
        var result = service.score(buildRequest(), TENANT_ID);
        assertThat(result.getScoredCount()).isZero();
        assertThat(result.getFailedCount()).isZero();
        assertThat(result.getScores()).isEmpty();
        assertThat(result.getTotalInputTokens()).isZero();
        verifyNoInteractions(llmProvider, dynamicDataService);
    }

    @Test
    void score_requiresReadAndUpdateBeforeProviderOrSourceAccess() {
        for (String action : List.of("read", "update")) {
            when(permissionEvaluator.canAction(MEMBER_ID, MODEL_CODE, action)).thenReturn(false);
            assertThatThrownBy(() -> service.score(buildRequest(), TENANT_ID)).isInstanceOf(AccessDeniedException.class);
            if ("read".equals(action)) when(permissionEvaluator.canAction(MEMBER_ID, MODEL_CODE, action)).thenReturn(true);
        }
        verifyNoInteractions(llmProviderFactory, readProtection, dynamicDataService);
    }

    @Test
    void score_rejectsHiddenOrReadOnlyOutputFieldBeforeProvider() {
        for (var fields : List.of(new FieldPermissionSet(FIELD_CODES, Set.of(), Set.of()),
                new FieldPermissionSet(FIELD_CODES, FIELD_CODES, Set.of(SCORE_FIELD)))) {
            when(fieldPermissions.getFieldPermissions(MEMBER_ID, MODEL_CODE)).thenReturn(fields);
            assertThatThrownBy(() -> service.score(buildRequest(), TENANT_ID)).isInstanceOf(AccessDeniedException.class);
        }
        verifyNoInteractions(llmProviderFactory, readProtection, dynamicDataService);
    }

    @Test
    void score_sourceProtectionDenialPreventsProviderAndWrites() {
        var denial = new AccessDeniedException("Protected input inference denied");
        when(readProtection.prepareComparison(anyString(), anyCollection(), anyMap(), anyString())).thenThrow(denial);
        assertThatThrownBy(() -> service.score(buildRequest(), TENANT_ID)).isSameAs(denial);
        verifyNoInteractions(llmProviderFactory, dynamicDataService);
    }

    @Test
    void score_rejectsUnregisteredContextBeforeProvider() {
        var request = buildRequest();
        request.setContextFields(List.of("unregistered_secret"));
        assertThatThrownBy(() -> service.score(request, TENANT_ID)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(llmProviderFactory, readProtection, dynamicDataService);
    }

    @Test
    void score_rejectsUnknownBatchIdBeforeAnyWrite() throws Exception {
        oneRecordAndResponse("[{\"id\":\"pid001\",\"score\":85},{\"id\":\"foreign\",\"score\":99}]");
        var result = service.score(buildRequest(), TENANT_ID);
        assertThat(result.getScores()).isEmpty();
        assertThat(result.getScoredCount()).isZero();
        assertThat(result.getFailedCount()).isEqualTo(1);
        verifyNoInteractions(dynamicDataService);
    }

    @Test
    void score_rejectsDuplicateBatchIdBeforeAnyWrite() throws Exception {
        oneRecordAndResponse("[{\"id\":\"pid001\",\"score\":85},{\"id\":\"pid001\",\"score\":99}]");
        assertThat(service.score(buildRequest(), TENANT_ID).getScores()).isEmpty();
        verifyNoInteractions(dynamicDataService);
    }

    @Test
    void score_bindsRequestedPidsRatherThanInterpolatingSql() throws Exception {
        providerReady();
        var request = buildRequest();
        String malicious = "x' OR 1=1 --";
        request.setRecordPids(List.of(malicious));
        when(readProtection.execute(any(), anyString(), anyMap())).thenReturn(List.of());
        service.score(request, TENANT_ID);
        verify(readProtection).execute(any(), argThat(sql -> sql.contains("#{params.pid0}") && !sql.contains(malicious)),
                argThat(params -> TENANT_ID.equals(params.get("tenantId")) && malicious.equals(params.get("pid0"))));
    }

    @Test
    void score_writeDenialIsNotConvertedToPartialSuccess() throws Exception {
        oneRecordAndResponse("[{\"id\":\"pid001\",\"score\":85}]");
        var denial = new AccessDeniedException("Revoked write");
        when(dynamicDataService.update(anyString(), anyString(), anyMap())).thenThrow(denial);
        assertThatThrownBy(() -> service.score(buildRequest(), TENANT_ID)).isSameAs(denial);
    }

    @Test
    void score_rejectsForeignTenantAndInvalidBatchBeforeProvider() {
        assertThatThrownBy(() -> service.score(buildRequest(), TENANT_ID + 1)).isInstanceOf(AccessDeniedException.class);
        var request = buildRequest();
        request.setBatchSize(0);
        assertThatThrownBy(() -> service.score(request, TENANT_ID)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(llmProviderFactory, readProtection, dynamicDataService);
    }

    @Test
    void controller_preservesPermissionDenial() throws Exception {
        PlatformAiScoringService scoring = mock(PlatformAiScoringService.class);
        var request = buildRequest();
        var denial = new AccessDeniedException("Target permission denied");
        when(scoring.score(request, TENANT_ID)).thenThrow(denial);
        assertThatThrownBy(() -> new PlatformAiController(scoring).scoreRecords(request)).isSameAs(denial);
    }

    @Test
    void score_rejectsProtectedPhysicalAliasOfOutputField() {
        List<FieldDefinition> definitions = new ArrayList<>(FIELD_CODES.stream()
                .map(code -> FieldDefinition.builder().code(code).columnName(code.equals(SCORE_FIELD) ? "score_column" : code).build()).toList());
        definitions.add(FieldDefinition.builder().code("private_score").columnName("score_column").build());
        when(metaModelService.getModelDefinition(MODEL_CODE)).thenReturn(Optional.of(
                ModelDefinition.builder().fields(definitions).build()));
        when(fieldPermissions.getFieldPermissions(MEMBER_ID, MODEL_CODE)).thenReturn(
                new FieldPermissionSet(FIELD_CODES, FIELD_CODES, Set.of("private_score")));
        assertThatThrownBy(() -> service.score(buildRequest(), TENANT_ID)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(llmProviderFactory, readProtection, dynamicDataService);
    }

    @Test
    void score_limitsInputsToUpdateScopeAsWellAsProtectedReadScope() throws Exception {
        providerReady();
        when(dataPermissionEngine.buildRowFilter(TENANT_ID, MODEL_CODE, "update", 3L))
                .thenReturn("AND created_by = 3");
        when(readProtection.execute(any(), anyString(), anyMap())).thenReturn(List.of());
        service.score(buildRequest(), TENANT_ID);
        verify(readProtection).execute(any(), argThat(sql -> sql.contains("AND created_by = 3")), anyMap());
    }

    @Test
    void score_rechecksUpdatePermissionAfterProviderBeforeWriting() throws Exception {
        oneRecordAndResponse("[{\"id\":\"pid001\",\"score\":85}]");
        when(permissionEvaluator.canAction(MEMBER_ID, MODEL_CODE, "update")).thenReturn(true, false);
        assertThatThrownBy(() -> service.score(buildRequest(), TENANT_ID)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(dynamicDataService);
    }
}
