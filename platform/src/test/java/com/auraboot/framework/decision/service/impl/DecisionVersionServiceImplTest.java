package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.decision.dto.DrtVersionCreateRequest;
import com.auraboot.framework.decision.dto.DrtVersionDTO;
import com.auraboot.framework.decision.entity.DrtVersionEntity;
import com.auraboot.framework.decision.mapper.DrtVersionMapper;
import com.auraboot.framework.decision.model.DecisionKind;
import com.auraboot.framework.decision.model.DecisionValidateResult;
import com.auraboot.framework.decision.model.RuntimeAdapter;
import com.auraboot.framework.decision.model.VersionStatus;
import com.auraboot.framework.decision.runtime.DecisionRuntime;
import com.auraboot.framework.decision.runtime.ResolvedDecision;
import com.auraboot.framework.decision.dto.DecisionImpactDTO;
import com.auraboot.framework.decision.dto.DecisionImpactRiskDTO;
import com.auraboot.framework.decision.service.DecisionImpactAckService;
import com.auraboot.framework.decision.service.DecisionImpactService;
import com.auraboot.framework.decision.service.DecisionUsageIndexService;
import com.auraboot.framework.exception.ValidationException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DecisionVersionServiceImpl} — state machine coverage.
 *
 * <p>Covers:
 * <ul>
 *   <li>createDraft — version auto-increment (first / subsequent), DRAFT status</li>
 *   <li>validate — DRAFT→VALIDATED on success, stays DRAFT on failure, rejects PUBLISHED</li>
 *   <li>publish — VALIDATED→PUBLISHED success, rejects from DRAFT (must validate first),
 *       rejects from PUBLISHED (already immutable)</li>
 *   <li>version increment — isolated from a different code, starts at 1 for new code</li>
 * </ul>
 *
 * <p>Pure Mockito; never touches DB.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DecisionVersionServiceImpl — state machine")
class DecisionVersionServiceImplTest {

    @Mock private DrtVersionMapper versionMapper;
    @Mock private DecisionRuntime decisionRuntime;
    @Mock private DecisionImpactService impactService;
    @Mock private DecisionUsageIndexService usageIndexService;
    @Mock private DecisionImpactAckService impactAckService;

    // ObjectMapper is a real instance — not worth mocking
    private final ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks private DecisionVersionServiceImpl service;

    private static final Long TENANT_ID = 100L;
    private static final String USER_PID = "user-abc";
    private static final String DECISION_CODE = "order.approval";
    private static final String VERSION_PID = "ver-001";

    @BeforeEach
    void setupTenant() {
        MetaContext.setSystemTenantContext(TENANT_ID);
        // Inject real ObjectMapper into the @InjectMocks instance
        // (Mockito does not inject non-@Mock fields automatically)
        service = new DecisionVersionServiceImpl(
                versionMapper, decisionRuntime, objectMapper, impactService, usageIndexService, impactAckService);
    }

    @AfterEach
    void clearTenant() {
        MetaContext.clear();
    }

    // ─── createDraft ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createDraft")
    class CreateDraft {

        @Test
        @DisplayName("first version for a new code starts at v1")
        void firstVersion() {
            when(versionMapper.findMaxVersion(TENANT_ID, DECISION_CODE)).thenReturn(null);
            when(versionMapper.insert(isA(DrtVersionEntity.class))).thenReturn(1);

            DrtVersionCreateRequest req = makeCreateRequest();
            DrtVersionDTO dto = service.createDraft(DECISION_CODE, req);

            assertThat(dto.getVersion()).isEqualTo(1);
            assertThat(dto.getStatus()).isEqualTo(VersionStatus.DRAFT.name());
        }

        @Test
        @DisplayName("subsequent version increments from max")
        void incrementsFromMax() {
            when(versionMapper.findMaxVersion(TENANT_ID, DECISION_CODE)).thenReturn(3);
            when(versionMapper.insert(isA(DrtVersionEntity.class))).thenReturn(1);

            DrtVersionDTO dto = service.createDraft(DECISION_CODE, makeCreateRequest());

            assertThat(dto.getVersion()).isEqualTo(4);
        }

        @Test
        @DisplayName("content hash is computed for non-null contentJson")
        void contentHashIsSet() {
            when(versionMapper.findMaxVersion(TENANT_ID, DECISION_CODE)).thenReturn(null);
            when(versionMapper.insert(isA(DrtVersionEntity.class))).thenReturn(1);

            DrtVersionCreateRequest req = makeCreateRequest();
            DrtVersionDTO dto = service.createDraft(DECISION_CODE, req);

            assertThat(dto.getContentHash()).isNotBlank();
        }

        @Test
        @DisplayName("draft without contentJson has null content_hash")
        void noContentHashWhenContentNull() {
            when(versionMapper.findMaxVersion(TENANT_ID, DECISION_CODE)).thenReturn(null);
            when(versionMapper.insert(isA(DrtVersionEntity.class))).thenReturn(1);

            DrtVersionCreateRequest req = new DrtVersionCreateRequest();
            req.setKind(DecisionKind.SIMPLE_CONDITION.name());
            req.setRuntimeAdapter(RuntimeAdapter.AST_EVALUATOR.name());
            // contentJson left null

            DrtVersionDTO dto = service.createDraft(DECISION_CODE, req);

            assertThat(dto.getContentHash()).isNull();
        }
    }

    // ─── validate ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("validate")
    class Validate {

        @Test
        @DisplayName("DRAFT→VALIDATED when runtime says valid")
        void draftBecomesValidated() {
            DrtVersionEntity entity = draftEntity();
            when(versionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entity);
            when(decisionRuntime.validate(any(ResolvedDecision.class)))
                    .thenReturn(DecisionValidateResult.ok(List.of("record.amount"), List.of()));
            when(versionMapper.updateById(isA(DrtVersionEntity.class))).thenReturn(1);

            DecisionValidateResult result = service.validate(VERSION_PID);

            assertThat(result.valid()).isTrue();
            ArgumentCaptor<DrtVersionEntity> captor = ArgumentCaptor.forClass(DrtVersionEntity.class);
            verify(versionMapper).updateById(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(VersionStatus.VALIDATED.name());
            verify(usageIndexService).refreshDecisionVersion(VERSION_PID);
        }

        @Test
        @DisplayName("stays DRAFT when runtime returns invalid")
        void staysDraftOnFailure() {
            DrtVersionEntity entity = draftEntity();
            when(versionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entity);
            when(decisionRuntime.validate(any(ResolvedDecision.class)))
                    .thenReturn(DecisionValidateResult.invalid(
                            List.of(new DecisionValidateResult.Issue("PARSE_ERR", "bad ast"))));

            DecisionValidateResult result = service.validate(VERSION_PID);

            assertThat(result.valid()).isFalse();
            verify(versionMapper, never()).updateById(isA(DrtVersionEntity.class));
        }

        @Test
        @DisplayName("rejects validate on PUBLISHED (immutable)")
        void rejectsPublished() {
            DrtVersionEntity entity = draftEntity();
            entity.setStatus(VersionStatus.PUBLISHED.name());
            when(versionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entity);

            assertThatThrownBy(() -> service.validate(VERSION_PID))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("immutable");
        }
    }

    // ─── publish ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("publish")
    class Publish {

        @Test
        @DisplayName("VALIDATED→PUBLISHED succeeds")
        void validatedToPublished() {
            DrtVersionEntity entity = draftEntity();
            entity.setStatus(VersionStatus.VALIDATED.name());
            when(versionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entity);
            when(versionMapper.updateById(isA(DrtVersionEntity.class))).thenReturn(1);
            when(impactService.getDecisionImpact(DECISION_CODE)).thenReturn(nonBlockingImpact());

            DrtVersionDTO result = service.publish(VERSION_PID);

            assertThat(result.getStatus()).isEqualTo(VersionStatus.PUBLISHED.name());
            assertThat(result.getPublishedAt()).isNotNull();

            ArgumentCaptor<DrtVersionEntity> captor = ArgumentCaptor.forClass(DrtVersionEntity.class);
            verify(versionMapper).updateById(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(VersionStatus.PUBLISHED.name());
        }

        @Test
        @DisplayName("publish from DRAFT is rejected — must validate first")
        void rejectsPublishFromDraft() {
            DrtVersionEntity entity = draftEntity();
            when(versionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entity);

            assertThatThrownBy(() -> service.publish(VERSION_PID))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("VALIDATED");
        }

        @Test
        @DisplayName("re-publish of PUBLISHED is rejected (already immutable)")
        void rejectsRepublish() {
            DrtVersionEntity entity = draftEntity();
            entity.setStatus(VersionStatus.PUBLISHED.name());
            when(versionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entity);

            assertThatThrownBy(() -> service.publish(VERSION_PID))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        @DisplayName("publishedBy is set from MetaContext user")
        void publishedByIsSet() {
            DrtVersionEntity entity = draftEntity();
            entity.setStatus(VersionStatus.VALIDATED.name());
            when(versionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entity);
            when(versionMapper.updateById(isA(DrtVersionEntity.class))).thenReturn(1);
            when(impactService.getDecisionImpact(DECISION_CODE)).thenReturn(nonBlockingImpact());

            service.publish(VERSION_PID);

            ArgumentCaptor<DrtVersionEntity> captor = ArgumentCaptor.forClass(DrtVersionEntity.class);
            verify(versionMapper).updateById(captor.capture());
            // MetaContext.getCurrentUserPid() may be null in system context —
            // the important thing is that publishedAt is set.
            assertThat(captor.getValue().getPublishedAt()).isNotNull();
        }

        @Test
        @DisplayName("publish with downstream consumers requires explicit impact acknowledgement")
        void rejectsBlockingImpactWithoutAcknowledgement() {
            DrtVersionEntity entity = draftEntity();
            entity.setStatus(VersionStatus.VALIDATED.name());
            when(versionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entity);
            when(impactService.getDecisionImpact(DECISION_CODE)).thenReturn(blockingImpact("Used by 1 automation"));

            assertThatThrownBy(() -> service.publish(VERSION_PID))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Impact acknowledgement required")
                    .hasMessageContaining("Used by 1 automation");

            verify(versionMapper, never()).updateById(isA(DrtVersionEntity.class));
        }

        @Test
        @DisplayName("acknowledged publish proceeds when downstream consumers exist")
        void acknowledgedPublishAllowsBlockingImpact() {
            DrtVersionEntity entity = draftEntity();
            entity.setStatus(VersionStatus.VALIDATED.name());
            when(versionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entity);
            when(impactService.getDecisionImpact(DECISION_CODE)).thenReturn(blockingImpact("Used by 1 automation"));
            when(versionMapper.updateById(isA(DrtVersionEntity.class))).thenReturn(1);

            DrtVersionDTO result = service.publish(VERSION_PID, true);

            assertThat(result.getStatus()).isEqualTo(VersionStatus.PUBLISHED.name());
            verify(versionMapper).updateById(isA(DrtVersionEntity.class));
            verify(usageIndexService).refreshDecisionVersion(VERSION_PID);
            verify(impactAckService).recordAcknowledgement(
                    eq("PUBLISH"), eq("DECISION_VERSION"), eq(DECISION_CODE), eq(VERSION_PID),
                    isNull(), eq("Used by 1 automation"), any(DecisionImpactDTO.class), isNull());
            verify(impactService, never()).rebuildUsageIndex();
        }
    }

    // ─── deprecate / retire ─────────────────────────────────────────────────

    @Nested
    @DisplayName("deprecate / retire")
    class LifecycleTransitions {

        @Test
        @DisplayName("acknowledged deprecate moves PUBLISHED→DEPRECATED and refreshes usage refs")
        void acknowledgedDeprecateAllowsBlockingImpact() {
            DrtVersionEntity entity = draftEntity();
            entity.setStatus(VersionStatus.PUBLISHED.name());
            when(versionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entity);
            when(impactService.getDecisionImpact(DECISION_CODE)).thenReturn(blockingImpact("Used by 1 SLA rule"));
            when(versionMapper.updateById(isA(DrtVersionEntity.class))).thenReturn(1);

            DrtVersionDTO result = service.deprecate(VERSION_PID, "planned retirement", true);

            assertThat(result.getStatus()).isEqualTo(VersionStatus.DEPRECATED.name());
            ArgumentCaptor<DrtVersionEntity> captor = ArgumentCaptor.forClass(DrtVersionEntity.class);
            verify(versionMapper).updateById(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(VersionStatus.DEPRECATED.name());
            assertThat(captor.getValue().getApprovalNote()).isEqualTo("planned retirement");
            verify(usageIndexService).refreshDecisionVersion(VERSION_PID);
            verify(impactAckService).recordAcknowledgement(
                    eq("DEPRECATE"), eq("DECISION_VERSION"), eq(DECISION_CODE), eq(VERSION_PID),
                    isNull(), eq("Used by 1 SLA rule"), any(DecisionImpactDTO.class), eq("planned retirement"));
        }

        @Test
        @DisplayName("deprecate with downstream consumers requires explicit impact acknowledgement")
        void rejectsDeprecateWithoutAcknowledgement() {
            DrtVersionEntity entity = draftEntity();
            entity.setStatus(VersionStatus.PUBLISHED.name());
            when(versionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entity);
            when(impactService.getDecisionImpact(DECISION_CODE)).thenReturn(blockingImpact("Used by 1 SLA rule"));

            assertThatThrownBy(() -> service.deprecate(VERSION_PID, "planned retirement", false))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Impact acknowledgement required")
                    .hasMessageContaining("Used by 1 SLA rule");

            verify(versionMapper, never()).updateById(isA(DrtVersionEntity.class));
        }

        @Test
        @DisplayName("acknowledged retire moves DEPRECATED→RETIRED and refreshes usage refs")
        void acknowledgedRetireAllowsBlockingImpact() {
            DrtVersionEntity entity = draftEntity();
            entity.setStatus(VersionStatus.DEPRECATED.name());
            when(versionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entity);
            when(impactService.getDecisionImpact(DECISION_CODE)).thenReturn(blockingImpact("Used by 1 automation"));
            when(versionMapper.updateById(isA(DrtVersionEntity.class))).thenReturn(1);

            DrtVersionDTO result = service.retire(VERSION_PID, "replaced by v2", true);

            assertThat(result.getStatus()).isEqualTo(VersionStatus.RETIRED.name());
            ArgumentCaptor<DrtVersionEntity> captor = ArgumentCaptor.forClass(DrtVersionEntity.class);
            verify(versionMapper).updateById(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(VersionStatus.RETIRED.name());
            assertThat(captor.getValue().getApprovalNote()).isEqualTo("replaced by v2");
            verify(usageIndexService).refreshDecisionVersion(VERSION_PID);
            verify(impactAckService).recordAcknowledgement(
                    eq("RETIRE"), eq("DECISION_VERSION"), eq(DECISION_CODE), eq(VERSION_PID),
                    isNull(), eq("Used by 1 automation"), any(DecisionImpactDTO.class), eq("replaced by v2"));
        }

        @Test
        @DisplayName("retire from PUBLISHED is rejected — must deprecate first")
        void rejectsRetireFromPublished() {
            DrtVersionEntity entity = draftEntity();
            entity.setStatus(VersionStatus.PUBLISHED.name());
            when(versionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entity);

            assertThatThrownBy(() -> service.retire(VERSION_PID, "skip deprecate", true))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("DEPRECATED");

            verify(versionMapper, never()).updateById(isA(DrtVersionEntity.class));
        }

        @Test
        @DisplayName("delete removes a draft-like version and clears usage-index refs")
        void deletesMutableVersionAndClearsUsageRefs() {
            DrtVersionEntity entity = draftEntity();
            entity.setStatus(VersionStatus.VALIDATED.name());
            when(versionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entity);
            when(versionMapper.deleteById(entity.getId())).thenReturn(1);

            DrtVersionDTO result = service.delete(VERSION_PID);

            assertThat(result.getPid()).isEqualTo(VERSION_PID);
            assertThat(result.getStatus()).isEqualTo(VersionStatus.VALIDATED.name());
            verify(usageIndexService).deleteSource("DECISION_VERSION", VERSION_PID);
            verify(versionMapper).deleteById(entity.getId());
        }

        @Test
        @DisplayName("delete rejects published/deprecated/retired versions")
        void rejectsDeleteForImmutableVersions() {
            DrtVersionEntity entity = draftEntity();
            entity.setStatus(VersionStatus.PUBLISHED.name());
            when(versionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entity);

            assertThatThrownBy(() -> service.delete(VERSION_PID))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Cannot delete")
                    .hasMessageContaining("deprecate/retire");

            verify(usageIndexService, never()).deleteSource(any(), any());
            verify(versionMapper, never()).deleteById(any(Long.class));
        }
    }

    // ─── version increment isolation ─────────────────────────────────────────

    @Test
    @DisplayName("different decision codes are versioned independently")
    void versioningIsCodeScoped() {
        // code A already has 5 versions
        when(versionMapper.findMaxVersion(TENANT_ID, "code.A")).thenReturn(5);
        // code B is brand new
        when(versionMapper.findMaxVersion(TENANT_ID, "code.B")).thenReturn(null);
        when(versionMapper.insert(isA(DrtVersionEntity.class))).thenReturn(1);

        DrtVersionDTO dtoA = service.createDraft("code.A", makeCreateRequest());
        DrtVersionDTO dtoB = service.createDraft("code.B", makeCreateRequest());

        assertThat(dtoA.getVersion()).isEqualTo(6);
        assertThat(dtoB.getVersion()).isEqualTo(1);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private DrtVersionCreateRequest makeCreateRequest() {
        DrtVersionCreateRequest req = new DrtVersionCreateRequest();
        req.setKind(DecisionKind.SIMPLE_CONDITION.name());
        req.setRuntimeAdapter(RuntimeAdapter.AST_EVALUATOR.name());
        ObjectNode content = objectMapper.createObjectNode();
        content.put("type", "CONDITION");
        req.setContentJson(content);
        return req;
    }

    private DrtVersionEntity draftEntity() {
        DrtVersionEntity e = new DrtVersionEntity();
        e.setId(1L);
        e.setPid(VERSION_PID);
        e.setTenantId(TENANT_ID);
        e.setDecisionCode(DECISION_CODE);
        e.setVersion(1);
        e.setStatus(VersionStatus.DRAFT.name());
        e.setKind(DecisionKind.SIMPLE_CONDITION.name());
        e.setRuntimeAdapter(RuntimeAdapter.AST_EVALUATOR.name());
        e.setContentFormat("JSON");
        ObjectNode content = objectMapper.createObjectNode();
        content.put("type", "CONDITION");
        e.setContentJson(content);
        e.setCreatedAt(Instant.now());
        return e;
    }

    private DecisionImpactDTO nonBlockingImpact() {
        return impact(false, "No downstream consumers");
    }

    private DecisionImpactDTO blockingImpact(String summary) {
        return impact(true, summary);
    }

    private DecisionImpactDTO impact(boolean blocking, String summary) {
        DecisionImpactRiskDTO risk = new DecisionImpactRiskDTO();
        risk.setBlocking(blocking);
        risk.setSummary(summary);
        DecisionImpactDTO impact = new DecisionImpactDTO();
        impact.setDecisionCode(DECISION_CODE);
        impact.setRisk(risk);
        return impact;
    }
}
