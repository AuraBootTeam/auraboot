package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.decision.dto.ConditionFragmentDTO;
import com.auraboot.framework.decision.dto.ConditionFragmentImpactDTO;
import com.auraboot.framework.decision.dto.ConditionFragmentVersionUpdateRequest;
import com.auraboot.framework.decision.dto.DecisionImpactRefDTO;
import com.auraboot.framework.decision.entity.ConditionFragmentEntity;
import com.auraboot.framework.decision.entity.DecisionUsageRefEntity;
import com.auraboot.framework.decision.mapper.ConditionFragmentMapper;
import com.auraboot.framework.decision.mapper.DecisionUsageRefMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConditionFragmentServiceImplTest {

    @Mock
    private ConditionFragmentMapper fragmentMapper;

    @Mock
    private DecisionUsageRefMapper usageRefMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ConditionFragmentServiceImpl service;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(7L, 9L, "user-pid", "tester");
        service = new ConditionFragmentServiceImpl(fragmentMapper, usageRefMapper, objectMapper);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void impactDerivesConsumersFromDecisionRefsAndFragmentScope() {
        ConditionFragmentEntity fragment = new ConditionFragmentEntity();
        fragment.setFragmentCode("leave_bpm_approval_route");
        fragment.setScopeType("BPM");
        fragment.setScopeRef("wd_leave_approval");
        fragment.setDecisionRefsJson(objectMapper.valueToTree(List.of("approval_routing")));

        DecisionUsageRefEntity bpmRef = usageRef(
                "BPM_PROCESS",
                "wd_leave_approval",
                "DECISION",
                "approval_routing",
                Map.of("sourceName", "请假审批流程", "processKey", "wd_leave_approval"));
        DecisionUsageRefEntity automationRef = usageRef(
                "AUTOMATION",
                "leave_notify",
                "DECISION",
                "approval_routing",
                Map.of("sourceName", "长假提醒", "modelCode", "wd_leave_request"));

        when(fragmentMapper.findLatestByTenantAndCode(7L, "leave_bpm_approval_route"))
                .thenReturn(fragment);
        when(usageRefMapper.findTargetRefs(7L, "CONDITION_FRAGMENT", "leave_bpm_approval_route"))
                .thenReturn(List.of());
        when(usageRefMapper.findTargetRefs(7L, "DECISION", "approval_routing"))
                .thenReturn(List.of(bpmRef, automationRef));

        ConditionFragmentImpactDTO impact = service.impact("leave_bpm_approval_route");

        assertThat(impact.getIncomingCount()).isEqualTo(1);
        assertThat(impact.getIncoming()).singleElement()
                .satisfies(ref -> {
                    assertThat(ref.getSourceType()).isEqualTo("BPM_PROCESS");
                    assertThat(ref.getSourceCode()).isEqualTo("wd_leave_approval");
                    assertThat(ref.getSourceName()).isEqualTo("请假审批流程");
                    assertThat(ref.getTargetType()).isEqualTo("DECISION");
                    assertThat(ref.getTargetCode()).isEqualTo("approval_routing");
                });
    }

    @Test
    void impactReadsDirectConditionFragmentRefsAcrossConsumers() {
        ConditionFragmentEntity fragment = new ConditionFragmentEntity();
        fragment.setFragmentCode("shared_leave_approval_guard");
        fragment.setDecisionRefsJson(objectMapper.valueToTree(List.of()));

        List<DecisionUsageRefEntity> directRefs = List.of(
                usageRef("SLA_RULE", "wd_manager_approve_sla", "CONDITION_FRAGMENT",
                        "shared_leave_approval_guard", Map.of("sourceName", "主管审批 SLA")),
                usageRef("BPM_PROCESS", "wd_leave_approval", "CONDITION_FRAGMENT",
                        "shared_leave_approval_guard", Map.of("sourceName", "请假审批流程")),
                usageRef("AUTOMATION", "leave_request_automation", "CONDITION_FRAGMENT",
                        "shared_leave_approval_guard", Map.of("sourceName", "长假自动提醒")),
                usageRef("EVENT_POLICY", "leave_event_policy", "CONDITION_FRAGMENT",
                        "shared_leave_approval_guard", Map.of("sourceName", "请假事件策略")),
                usageRef("PERMISSION_POLICY", "model.leave_request.view", "CONDITION_FRAGMENT",
                        "shared_leave_approval_guard", Map.of("sourceName", "请假可见性策略")));

        when(fragmentMapper.findLatestByTenantAndCode(7L, "shared_leave_approval_guard"))
                .thenReturn(fragment);
        when(usageRefMapper.findTargetRefs(7L, "CONDITION_FRAGMENT", "shared_leave_approval_guard"))
                .thenReturn(directRefs);

        ConditionFragmentImpactDTO impact = service.impact("shared_leave_approval_guard");

        assertThat(impact.getIncomingCount()).isEqualTo(5);
        assertThat(impact.getIncoming())
                .extracting(ref -> ref.getSourceType(), ref -> ref.getTargetType(), ref -> ref.getTargetCode())
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                "SLA_RULE", "CONDITION_FRAGMENT", "shared_leave_approval_guard"),
                        org.assertj.core.groups.Tuple.tuple(
                                "BPM_PROCESS", "CONDITION_FRAGMENT", "shared_leave_approval_guard"),
                        org.assertj.core.groups.Tuple.tuple(
                                "AUTOMATION", "CONDITION_FRAGMENT", "shared_leave_approval_guard"),
                        org.assertj.core.groups.Tuple.tuple(
                                "EVENT_POLICY", "CONDITION_FRAGMENT", "shared_leave_approval_guard"),
                        org.assertj.core.groups.Tuple.tuple(
                                "PERMISSION_POLICY", "CONDITION_FRAGMENT", "shared_leave_approval_guard"));
        assertThat(impact.getIncoming())
                .extracting(DecisionImpactRefDTO::getSourceName)
                .contains("主管审批 SLA", "请假审批流程", "长假自动提醒", "请假事件策略", "请假可见性策略");
    }

    @Test
    void updateDraftOverwritesEditableVersionAndRefreshesReferenceIndexes() {
        ConditionFragmentEntity fragment = new ConditionFragmentEntity();
        fragment.setPid("frag-pid");
        fragment.setTenantId(7L);
        fragment.setFragmentCode("leave_bpm_approval_route");
        fragment.setFragmentName("Old name");
        fragment.setScopeType("BPM");
        fragment.setScopeRef("old_process");
        fragment.setVersion(2);
        fragment.setStatus("VALIDATED");
        fragment.setOwnerModule("BPM");
        fragment.setEnabled(true);

        ConditionFragmentVersionUpdateRequest request = new ConditionFragmentVersionUpdateRequest();
        request.setFragmentName("申请人审批条件");
        request.setScopeType("BPM");
        request.setScopeRef("wd_leave_approval");
        request.setOwnerModule("BPM");
        request.setEnabled(true);
        request.setConditionSpec(objectMapper.valueToTree(Map.of(
                "root", Map.of(
                        "type", "compare",
                        "left", Map.of("type", "path", "scope", "record", "path", "data.wd_req_applicant"),
                        "operator", "EQ",
                        "right", Map.of("type", "literal", "value", "user-owner")),
                "decisionBindings", List.of(Map.of(
                        "decisionCode", "approval_routing",
                        "versionPolicy", "LATEST_PUBLISHED",
                        "inputMappings", List.of(),
                        "outputMappings", List.of(),
                        "fallbackPolicy", Map.of("mode", "FAIL_CLOSED"),
                        "traceMode", "SAMPLED",
                        "enabled", true)))));

        when(fragmentMapper.findByTenantAndPid(7L, "frag-pid")).thenReturn(fragment);

        ConditionFragmentDTO dto = service.updateDraft("frag-pid", request);

        ArgumentCaptor<ConditionFragmentEntity> captor = ArgumentCaptor.forClass(ConditionFragmentEntity.class);
        verify(fragmentMapper).updateById(captor.capture());
        ConditionFragmentEntity saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo("DRAFT");
        assertThat(saved.getFragmentName()).isEqualTo("申请人审批条件");
        assertThat(saved.getScopeRef()).isEqualTo("wd_leave_approval");
        assertThat(objectMapper.convertValue(saved.getFieldRefsJson(), List.class))
                .contains("record.data.wd_req_applicant");
        assertThat(objectMapper.convertValue(saved.getDecisionRefsJson(), List.class))
                .contains("approval_routing");
        assertThat(dto.getStatus()).isEqualTo("DRAFT");
        assertThat(dto.getFieldRefs()).contains("record.data.wd_req_applicant");
        assertThat(dto.getDecisionRefs()).contains("approval_routing");
    }

    @Test
    void updateDraftRejectsImmutablePublishedVersion() {
        ConditionFragmentEntity fragment = new ConditionFragmentEntity();
        fragment.setPid("published-frag");
        fragment.setTenantId(7L);
        fragment.setFragmentCode("leave_bpm_approval_route");
        fragment.setStatus("PUBLISHED");

        ConditionFragmentVersionUpdateRequest request = new ConditionFragmentVersionUpdateRequest();
        request.setConditionSpec(objectMapper.valueToTree(Map.of(
                "root", Map.of(
                        "type", "group",
                        "op", "AND",
                        "children", List.of()))));

        when(fragmentMapper.findByTenantAndPid(7L, "published-frag")).thenReturn(fragment);

        Assertions.assertThrows(
                com.auraboot.framework.exception.ValidationException.class,
                () -> service.updateDraft("published-frag", request));
    }

    private DecisionUsageRefEntity usageRef(
            String sourceType,
            String sourceCode,
            String targetType,
            String targetCode,
            Map<String, Object> metadata) {
        DecisionUsageRefEntity ref = new DecisionUsageRefEntity();
        ref.setSourceType(sourceType);
        ref.setSourceCode(sourceCode);
        ref.setSourcePid(sourceCode + "-pid");
        ref.setTargetType(targetType);
        ref.setTargetCode(targetCode);
        ref.setBinding("RULE_BINDING");
        ref.setMetadataJson(objectMapper.valueToTree(metadata));
        return ref;
    }
}
