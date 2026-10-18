package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.dto.DrtEvaluateRequest;
import com.auraboot.framework.decision.dto.DrtLogDTO;
import com.auraboot.framework.decision.dto.DrtTestRunRequest;
import com.auraboot.framework.decision.dto.DrtValidateRequest;
import com.auraboot.framework.decision.entity.DrtLogEntity;
import com.auraboot.framework.decision.entity.DrtVersionEntity;
import com.auraboot.framework.decision.entity.DecisionRolloutPolicyEntity;
import com.auraboot.framework.decision.mapper.DrtDefinitionMapper;
import com.auraboot.framework.decision.mapper.DrtLogMapper;
import com.auraboot.framework.decision.mapper.DrtVersionMapper;
import com.auraboot.framework.decision.model.DecisionKind;
import com.auraboot.framework.decision.model.DecisionResult;
import com.auraboot.framework.decision.model.DecisionRolloutSelection;
import com.auraboot.framework.decision.model.DecisionValidateResult;
import com.auraboot.framework.decision.model.DecisionStatus;
import com.auraboot.framework.decision.model.RuntimeAdapter;
import com.auraboot.framework.decision.model.VersionBinding;
import com.auraboot.framework.decision.model.VersionStatus;
import com.auraboot.framework.decision.runtime.DecisionRuntime;
import com.auraboot.framework.decision.runtime.ResolvedDecision;
import com.auraboot.framework.decision.service.DecisionModelFieldService;
import com.auraboot.framework.decision.service.DecisionRolloutService;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.user.service.UserService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DecisionEvaluationServiceImpl 分支覆盖单元测试（无 DB）：版本解析的绑定
 * 校验矩阵（LATEST/FIXED/VERSION_TAG/EFFECTIVE_TIME/ROLLOUT/不可绑定状态）、
 * 批量评估的故障隔离、testRun/validate 的枚举守卫、日志写入的 trace 关联
 * 与 ERROR 汇聚，以及日志查询的入参钳制。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DecisionEvaluationServiceImpl 分支覆盖")
class DecisionEvaluationServiceImplTest {

    @Mock
    private DrtDefinitionMapper definitionMapper;
    @Mock
    private DrtVersionMapper versionMapper;
    @Mock
    private DrtLogMapper logMapper;
    @Mock
    private DecisionRuntime decisionRuntime;
    @Mock
    private DecisionRolloutService rolloutService;
    @org.mockito.Spy
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Mock
    private ObjectProvider<com.auraboot.framework.meta.service.DynamicDataService> dynamicDataServiceProvider;
    @Mock
    private MetaModelMapper metaModelMapper;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private DecisionModelFieldService decisionModelFieldService;
    @Mock
    private UserService userService;

    @InjectMocks
    private DecisionEvaluationServiceImpl service;

    @BeforeEach
    void setUp() {
        MetaContext.setCurrentTenantId(42L);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    private DrtVersionEntity version(int number, String status) {
        DrtVersionEntity entity = new DrtVersionEntity();
        entity.setPid("ver-" + number);
        entity.setTenantId(42L);
        entity.setDecisionCode("sla_auto_approve");
        entity.setVersion(number);
        entity.setStatus(status);
        entity.setKind(DecisionKind.DECISION_TABLE.name());
        entity.setRuntimeAdapter(RuntimeAdapter.AST_EVALUATOR.name());
        try {
            entity.setContentJson((JsonNode) objectMapper.readTree("{\"rules\":[]}"));
        } catch (Exception ignored) {
        }
        return entity;
    }

    private DrtEvaluateRequest request() {
        DrtEvaluateRequest request = new DrtEvaluateRequest();
        request.setDecisionCode("sla_auto_approve");
        request.setCallerType("BPM");
        request.setCallerRef("task-1");
        request.setCorrelationId("corr-1");
        request.setContext(Map.of("fact", Map.of("amount", 100)));
        return request;
    }

    private DecisionResult result(DecisionStatus status, boolean matched, String traceId, List<String> errors) {
        DecisionResult.Builder builder = DecisionResult.builder("sla_auto_approve")
                .traceId(traceId)
                .status(status)
                .matched(matched);
        if (errors != null) builder.errors(errors);
        return builder.build();
    }

    @Nested
    @DisplayName("版本解析")
    class VersionResolution {

        @Test
        @DisplayName("租户上下文缺失拒绝")
        void tenantContextRequired() {
            MetaContext.clear();
            assertThatThrownBy(() -> service.evaluate(request()))
                    .isInstanceOf(ValidationException.class).hasMessageContaining("Tenant context required");
        }

        @Test
        @DisplayName("FIXED_VERSION 缺 fixedVersion 拒绝")
        void fixedVersionRequiresFixed() {
            DrtEvaluateRequest request = request();
            request.setBinding(VersionBinding.FIXED_VERSION);
            assertThatThrownBy(() -> service.evaluate(request))
                    .isInstanceOf(ValidationException.class).hasMessageContaining("fixedVersion is required");
        }

        @Test
        @DisplayName("VERSION_TAG 缺 tag 拒绝")
        void versionTagRequiresTag() {
            DrtEvaluateRequest request = request();
            request.setBinding(VersionBinding.VERSION_TAG);
            assertThatThrownBy(() -> service.evaluate(request))
                    .isInstanceOf(ValidationException.class).hasMessageContaining("versionTag is required");
        }

        @Test
        @DisplayName("EFFECTIVE_TIME 缺 asOf 拒绝")
        void effectiveTimeRequiresAsOf() {
            DrtEvaluateRequest request = request();
            request.setBinding(VersionBinding.EFFECTIVE_TIME);
            assertThatThrownBy(() -> service.evaluate(request))
                    .isInstanceOf(ValidationException.class).hasMessageContaining("asOf is required");
        }

        @Test
        @DisplayName("无候选版本 NOT_FOUND")
        void noVersionsNotFound() {
            when(versionMapper.findAllByCode(42L, "sla_auto_approve")).thenReturn(List.of());
            assertThatThrownBy(() -> service.evaluate(request()))
                    .isInstanceOf(ValidationException.class).hasMessageContaining("No version found");
        }

        @Test
        @DisplayName("仅 DRAFT 候选时 LATEST 报 NOT_FOUND（选择器先按状态过滤）")
        void draftOnlyLatestReportsNotFound() {
            when(versionMapper.findAllByCode(42L, "sla_auto_approve"))
                    .thenReturn(List.of(version(1, VersionStatus.DRAFT.name())));
            assertThatThrownBy(() -> service.evaluate(request()))
                    .isInstanceOf(ValidationException.class).hasMessageContaining("No version found");
        }
    }

    @Nested
    @DisplayName("评估与日志")
    class EvaluateAndLog {

        @BeforeEach
        void stubHappy() {
            when(versionMapper.findAllByCode(42L, "sla_auto_approve"))
                    .thenReturn(List.of(version(1, VersionStatus.DRAFT.name()),
                            version(2, VersionStatus.PUBLISHED.name())));
        }

        @Test
        @DisplayName("LATEST 取最高已发布版；日志与结果共享 traceId 并落快照")
        void evaluateWritesCorrelatedLog() {
            DecisionResult runtimeResult = DecisionResult.builder("sla_auto_approve")
                    .traceId("tr-123")
                    .status(DecisionStatus.MATCHED)
                    .matched(true)
                    .matchedRules(List.of(new DecisionResult.MatchedRule("r1", "高额自动通过", "amount>50")))
                    .outputs(Map.of("approve", true))
                    .build();
            when(decisionRuntime.evaluate(any(), any(), any())).thenReturn(runtimeResult);

            DecisionResult result = service.evaluate(request());

            assertThat(result).isSameAs(runtimeResult);
            ArgumentCaptor<DrtLogEntity> captor = ArgumentCaptor.forClass(DrtLogEntity.class);
            verify(logMapper).insert(captor.capture());
            DrtLogEntity logEntry = captor.getValue();
            // §22 audit contract: the log row carries the runtime-stamped trace id.
            assertThat(logEntry.getTraceId()).isEqualTo("tr-123");
            assertThat(logEntry.getSelectedVersion()).isEqualTo(2);
            assertThat(logEntry.getDecisionCode()).isEqualTo("sla_auto_approve");
            assertThat(logEntry.getMatched()).isTrue();
            assertThat(logEntry.getStatus()).isEqualTo(DecisionStatus.MATCHED.name());
            assertThat(logEntry.getMatchedRulesJson()).isNotNull();
            assertThat(logEntry.getOutputSnapshot()).isNotNull();
            assertThat(logEntry.getInputDigest()).hasSize(64);
        }

        @Test
        @DisplayName("ERROR 结果汇聚错误信息进日志")
        void errorResultRecordsMessage() {
            DecisionResult failure = DecisionResult.builder("sla_auto_approve")
                    .traceId("tr-err")
                    .status(DecisionStatus.ERROR)
                    .matched(false)
                    .errors(List.of("adapter exploded", "rule parse failed"))
                    .build();
            when(decisionRuntime.evaluate(any(), any(), any())).thenReturn(failure);

            service.evaluate(request());

            ArgumentCaptor<DrtLogEntity> captor = ArgumentCaptor.forClass(DrtLogEntity.class);
            verify(logMapper).insert(captor.capture());
            assertThat(captor.getValue().getErrorMessage())
                    .isEqualTo("adapter exploded; rule parse failed");
        }

        @Test
        @DisplayName("ROLLOUT 绑定走策略选择并把 rollout 元数据写进日志")
        void rolloutBindingSelectsAndLogsMetadata() {
            DrtVersionEntity selected = version(3, VersionStatus.PUBLISHED.name());
            DecisionRolloutPolicyEntity policy = new DecisionRolloutPolicyEntity();
            policy.setPid("policy-1");
            DecisionRolloutSelection selection = new DecisionRolloutSelection(
                    policy, selected, com.auraboot.framework.decision.model.DecisionRolloutArm.CANDIDATE, 17, "rk-1");
            when(rolloutService.select(eq(42L), any(DrtEvaluateRequest.class), anyList()))
                    .thenReturn(selection);
            DecisionResult runtimeResult = DecisionResult.builder("sla_auto_approve")
                    .traceId("tr-roll")
                    .status(DecisionStatus.NOT_MATCHED)
                    .matched(false)
                    .build();
            when(decisionRuntime.evaluate(any(), any(), any())).thenReturn(runtimeResult);

            DrtEvaluateRequest request = request();
            request.setBinding(VersionBinding.ROLLOUT);
            DecisionResult result = service.evaluate(request);

            assertThat(result).isSameAs(runtimeResult);
            ArgumentCaptor<DrtLogEntity> captor = ArgumentCaptor.forClass(DrtLogEntity.class);
            verify(logMapper).insert(captor.capture());
            assertThat(captor.getValue().getRolloutPolicyPid()).isEqualTo("policy-1");
            assertThat(captor.getValue().getRolloutArm()).isEqualTo("CANDIDATE");
            assertThat(captor.getValue().getRolloutBucket()).isEqualTo(17);
            assertThat(captor.getValue().getRoutingKey()).isEqualTo("rk-1");
        }

        @Test
        @DisplayName("未知 scope 被跳过，评估照常完成")
        void unknownScopeSkipped() {
            when(decisionRuntime.evaluate(any(), any(), any())).thenReturn(
                    DecisionResult.builder("sla_auto_approve").traceId("tr-x")
                            .status(DecisionStatus.MATCHED).matched(true).build());
            DrtEvaluateRequest request = request();
            request.setContext(Map.of("fact", Map.of("amount", 1), "bogus_scope", Map.of("x", 1)));

            assertThat(service.evaluate(request).matched()).isTrue();
        }
    }

    @Nested
    @DisplayName("批量评估")
    class BatchEvaluate {

        @Test
        @DisplayName("空列表短路")
        void emptyListShortCircuits() {
            assertThat(service.batchEvaluate(List.of())).isEmpty();
            verify(versionMapper, org.mockito.Mockito.never()).findAllByCode(anyLong(), anyString());
        }

        @Test
        @DisplayName("坏请求只产生 ERROR 结果，不拖垮整批")
        void oneFailureIsolated() {
            when(versionMapper.findAllByCode(42L, "sla_auto_approve"))
                    .thenReturn(List.of(version(1, VersionStatus.PUBLISHED.name())));
            when(decisionRuntime.evaluate(any(), any(), any())).thenReturn(
                    DecisionResult.builder("sla_auto_approve").traceId("tr-1")
                            .status(DecisionStatus.MATCHED).matched(true).build());
            DrtEvaluateRequest bad = new DrtEvaluateRequest();
            bad.setDecisionCode("ghost_decision");

            List<DecisionResult> results = service.batchEvaluate(List.of(request(), bad));

            assertThat(results).hasSize(2);
            assertThat(results.get(0).matched()).isTrue();
            assertThat(results.get(1).status()).isEqualTo(DecisionStatus.ERROR);
            assertThat(results.get(1).matched()).isFalse();
        }
    }

    @Nested
    @DisplayName("试跑与校验")
    class TestRunAndValidate {

        @Test
        @DisplayName("非法 kind / runtimeAdapter 拒绝")
        void enumGuards() {
            DrtTestRunRequest badKind = new DrtTestRunRequest();
            badKind.setKind("NOPE");
            badKind.setRuntimeAdapter("AVL");
            assertThatThrownBy(() -> service.testRun(badKind))
                    .isInstanceOf(ValidationException.class).hasMessageContaining("kind");

            DrtTestRunRequest badAdapter = new DrtTestRunRequest();
            badAdapter.setKind(DecisionKind.DECISION_TABLE.name());
            badAdapter.setRuntimeAdapter("WAT");
            assertThatThrownBy(() -> service.testRun(badAdapter))
                    .isInstanceOf(ValidationException.class).hasMessageContaining("runtimeAdapter");
        }

        @Test
        @DisplayName("testRun 走内存内容，不落库不查版本")
        void testRunInMemory() {
            DrtTestRunRequest request = new DrtTestRunRequest();
            request.setKind(DecisionKind.DECISION_TABLE.name());
            request.setRuntimeAdapter(RuntimeAdapter.AST_EVALUATOR.name());
            request.setContentJson(objectMapper.valueToTree(Map.of("rules", List.of())));
            DecisionResult expected = DecisionResult.builder("__test__")
                    .status(DecisionStatus.MATCHED).matched(true).build();
            when(decisionRuntime.testRun(any(), any(), any())).thenReturn(expected);

            DecisionResult result = service.testRun(request);

            assertThat(result).isSameAs(expected);
            ArgumentCaptor<ResolvedDecision> captor = ArgumentCaptor.forClass(ResolvedDecision.class);
            verify(decisionRuntime).testRun(captor.capture(), any(), any());
            assertThat(captor.getValue().decisionCode()).isEqualTo("__test__");
            verify(logMapper, org.mockito.Mockito.never())
                    .insert(any(DrtLogEntity.class));
        }

        @Test
        @DisplayName("validate 委托运行时")
        void validateDelegates() {
            DrtValidateRequest request = new DrtValidateRequest();
            request.setKind(DecisionKind.DECISION_TABLE.name());
            request.setRuntimeAdapter(RuntimeAdapter.AST_EVALUATOR.name());
            request.setContentJson(objectMapper.valueToTree(Map.of("rules", List.of())));
            DecisionValidateResult expected = DecisionValidateResult.ok(List.of(), List.of());
            when(decisionRuntime.validate(any())).thenReturn(expected);

            assertThat(service.validate(request)).isSameAs(expected);
        }
    }

    @Nested
    @DisplayName("日志查询")
    class LogQueries {

        @Test
        @DisplayName("findByPid 缺失返回 null，命中映射 DTO")
        void findByPidBranches() {
            when(logMapper.findByPid(42L, "ghost")).thenReturn(null);
            assertThat(service.findLogByPid("ghost")).isNull();

            DrtLogEntity entity = new DrtLogEntity();
            entity.setPid("log-1");
            entity.setTenantId(42L);
            entity.setTraceId("tr-1");
            when(logMapper.findByPid(42L, "log-1")).thenReturn(entity);
            DrtLogDTO dto = service.findLogByPid("log-1");
            assertThat(dto.getPid()).isEqualTo("log-1");
            assertThat(dto.getTraceId()).isEqualTo("tr-1");
        }

        @Test
        @DisplayName("findRecentLogs 钳制分页参数并映射 PageResult")
        void recentLogsClampPaging() {
            Page<DrtLogEntity> page = new Page<>(1, 100);
            page.setRecords(List.of());
            page.setTotal(0);
            when(logMapper.selectPage(any(), any())).thenReturn(page);

            PageResult<DrtLogDTO> result = service.findRecentLogs(
                    "keyword", "sla", "matched", "BPM", "task-1", true, "CONTROL",
                    10L, 500L,
                    -5, 999);

            assertThat(result.getRecords()).isEmpty();
            ArgumentCaptor<Page<DrtLogEntity>> captor =
                    ArgumentCaptor.forClass(Page.class);
            verify(logMapper).selectPage(captor.capture(), any());
            assertThat(captor.getValue().getCurrent()).isEqualTo(1);
            assertThat(captor.getValue().getSize()).isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("虚拟源富化")
    class VirtualSources {

        @org.junit.jupiter.api.BeforeEach
        void stubVersions() {
            when(versionMapper.findAllByCode(42L, "sla_auto_approve"))
                    .thenReturn(List.of(version(2, VersionStatus.PUBLISHED.name())));
        }

        private DrtEvaluateRequest metaRequest(Map<String, Object> metaScope) {
            DrtEvaluateRequest request = request();
            request.setContext(new java.util.HashMap<>(Map.of(
                    "meta", metaScope,
                    "record", new java.util.HashMap<>(Map.of("data", new java.util.HashMap<>(Map.of("amount", 9)))))));
            return request;
        }

        private void stubPublishedVirtualModel(String code, String sourceRef) {
            com.auraboot.framework.meta.entity.Model model = new com.auraboot.framework.meta.entity.Model();
            model.setCode(code);
            model.setStatus(com.auraboot.framework.common.constant.StatusConstants.PUBLISHED);
            model.setSourceType("virtual");
            model.setSourceRef(sourceRef);
            when(metaModelMapper.findCurrentByCode(code)).thenReturn(model);
        }

        private DecisionResult evaluateWith(DrtEvaluateRequest request) {
            DecisionResult runtimeResult = DecisionResult.builder("sla_auto_approve")
                    .traceId("tr-vs")
                    .status(DecisionStatus.NOT_MATCHED)
                    .matched(false)
                    .build();
            when(decisionRuntime.evaluate(any(), any(), any())).thenReturn(runtimeResult);
            return service.evaluate(request);
        }

        private String capturedTraceSnapshot() {
            ArgumentCaptor<DrtLogEntity> captor = ArgumentCaptor.forClass(DrtLogEntity.class);
            verify(logMapper).insert(captor.capture());
            JsonNode snapshot = captor.getValue().getTraceSnapshot();
            return snapshot == null ? "" : snapshot.toString();
        }

        @Test
        @DisplayName("无 META scope 时富化直接旁路")
        void noMetaScopeBypasses() {
            DrtEvaluateRequest request = request();
            evaluateWith(request);
            assertThat(capturedTraceSnapshot()).doesNotContain("virtualSources");
        }

        @Test
        @DisplayName("DynamicDataService 不可用时选择器 SKIPPED 且带原因")
        void missingServiceSkips() {
            when(dynamicDataServiceProvider.getIfAvailable()).thenReturn(null);
            evaluateWith(metaRequest(Map.of("virtualSources", List.of(
                    Map.of("sourceRef", "crm.customer", "recordId", "rec-1")))));
            String snapshot = capturedTraceSnapshot();
            assertThat(snapshot).contains("SKIPPED").contains("DynamicDataService unavailable");
        }

        @Test
        @DisplayName("缺 model/record 的选择器 SKIPPED")
        void incompleteSelectorSkipped() {
            com.auraboot.framework.meta.service.DynamicDataService dynamicData =
                    org.mockito.Mockito.mock(com.auraboot.framework.meta.service.DynamicDataService.class);
            when(dynamicDataServiceProvider.getIfAvailable()).thenReturn(dynamicData);
            evaluateWith(metaRequest(Map.of("virtualSources", List.of(
                    Map.of("note", "no identifiers")))));
            assertThat(capturedTraceSnapshot()).contains("Missing resolvable model or record");
        }

        @Test
        @DisplayName("显式模型未发布 → 选择器被拒")
        void unpublishedModelRejected() {
            com.auraboot.framework.meta.service.DynamicDataService dynamicData =
                    org.mockito.Mockito.mock(com.auraboot.framework.meta.service.DynamicDataService.class);
            when(dynamicDataServiceProvider.getIfAvailable()).thenReturn(dynamicData);
            com.auraboot.framework.meta.entity.Model draft = new com.auraboot.framework.meta.entity.Model();
            draft.setCode("crm_customer");
            draft.setStatus("DRAFT");
            when(metaModelMapper.findCurrentByCode("crm_customer")).thenReturn(draft);
            evaluateWith(metaRequest(Map.of("virtualSources", List.of(
                    Map.of("modelCode", "crm_customer", "recordId", "rec-1")))));
            assertThat(capturedTraceSnapshot()).contains("Missing resolvable model or record");
        }

        @Test
        @DisplayName("命中虚拟源：字段落 record 域并标 RESOLVED，租户列不外带")
        void resolvedFieldsFlowIntoRecordScope() {
            com.auraboot.framework.meta.service.DynamicDataService dynamicData =
                    org.mockito.Mockito.mock(com.auraboot.framework.meta.service.DynamicDataService.class);
            when(dynamicDataServiceProvider.getIfAvailable()).thenReturn(dynamicData);
            stubPublishedVirtualModel("crm_customer", "crm.customer");
            when(transactionManager.getTransaction(any()))
                    .thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
            when(dynamicData.getById("crm_customer", "rec-1")).thenReturn(Map.of(
                    "id", 1, "tenant_id", 42L, "level", "VIP"));

            evaluateWith(metaRequest(Map.of("virtualSources", List.of(
                    Map.of("modelCode", "crm_customer", "recordId", "rec-1")))));

            String snapshot = capturedTraceSnapshot();
            assertThat(snapshot).contains("RESOLVED").contains("level");
            assertThat(snapshot).doesNotContain("tenant_id");
        }

        @Test
        @DisplayName("虚拟源查询失败 fail-closed 为 ERROR")
        void queryFailureFailsClosed() {
            com.auraboot.framework.meta.service.DynamicDataService dynamicData =
                    org.mockito.Mockito.mock(com.auraboot.framework.meta.service.DynamicDataService.class);
            when(dynamicDataServiceProvider.getIfAvailable()).thenReturn(dynamicData);
            stubPublishedVirtualModel("crm_customer", "crm.customer");
            when(transactionManager.getTransaction(any()))
                    .thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
            when(dynamicData.getById(anyString(), anyString()))
                    .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));

            evaluateWith(metaRequest(Map.of("virtualSources", List.of(
                    Map.of("modelCode", "crm_customer", "recordId", "rec-1")))));

            assertThat(capturedTraceSnapshot()).contains("ERROR").contains("db down");
        }
    }
}
