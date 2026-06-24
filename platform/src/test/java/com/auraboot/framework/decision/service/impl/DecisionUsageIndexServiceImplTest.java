package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.entity.Automation;
import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.entity.TriggerConfig;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.automation.mapper.AutomationMapper;
import com.auraboot.framework.bpm.mapper.SlaConfigMapper;
import com.auraboot.framework.decision.dto.DecisionImpactRefDTO;
import com.auraboot.framework.decision.dto.DecisionUsageIndexRebuildDTO;
import com.auraboot.framework.decision.entity.DecisionUsageRefEntity;
import com.auraboot.framework.decision.mapper.DecisionUsageRefMapper;
import com.auraboot.framework.decision.mapper.DrtVersionMapper;
import com.auraboot.framework.decision.rule.DecisionBinding;
import com.auraboot.framework.decision.rule.DecisionVersionPolicy;
import com.auraboot.framework.decision.rule.RuleBindingKind;
import com.auraboot.framework.decision.rule.RuleConsumerBinding;
import com.auraboot.framework.decision.rule.RuleValueSource;
import com.auraboot.framework.eventpolicy.entity.DrtPolicyVersionEntity;
import com.auraboot.framework.eventpolicy.mapper.DrtPolicyDefinitionMapper;
import com.auraboot.framework.eventpolicy.mapper.DrtPolicyVersionMapper;
import com.auraboot.framework.meta.entity.NamedQuery;
import com.auraboot.framework.meta.mapper.NamedQueryMapper;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import com.auraboot.framework.plugin.mapper.BpmProcessDefinitionMapper;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DecisionUsageIndexServiceImplTest {

    private final DecisionUsageRefMapper usageRefMapper = mock(DecisionUsageRefMapper.class);
    private final DrtVersionMapper versionMapper = mock(DrtVersionMapper.class);
    private final AutomationMapper automationMapper = mock(AutomationMapper.class);
    private final SlaConfigMapper slaConfigMapper = mock(SlaConfigMapper.class);
    private final DrtPolicyVersionMapper policyVersionMapper = mock(DrtPolicyVersionMapper.class);
    private final DrtPolicyDefinitionMapper policyDefinitionMapper = mock(DrtPolicyDefinitionMapper.class);
    private final NamedQueryMapper namedQueryMapper = mock(NamedQueryMapper.class);
    private final BpmProcessDefinitionMapper bpmProcessDefinitionMapper = mock(BpmProcessDefinitionMapper.class);
    private final RolePermissionMapper rolePermissionMapper = mock(RolePermissionMapper.class);
    private final PermissionMapper permissionMapper = mock(PermissionMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final DecisionUsageIndexServiceImpl service = new DecisionUsageIndexServiceImpl(
            usageRefMapper,
            versionMapper,
            automationMapper,
            slaConfigMapper,
            policyVersionMapper,
            policyDefinitionMapper,
            namedQueryMapper,
            bpmProcessDefinitionMapper,
            rolePermissionMapper,
            permissionMapper,
            objectMapper);

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    @Test
    void rebuildIndexesAutomationConnectorAndWebhookActionReferences() {
        MetaContext.setContext(10L, 20L, "tester", "Tester");
        when(versionMapper.selectList(any())).thenReturn(List.of());
        when(slaConfigMapper.selectList(any())).thenReturn(List.of());
        when(policyVersionMapper.selectList(any())).thenReturn(List.of());

        Automation automation = new Automation();
        automation.setPid("auto-1");
        automation.setTenantId(10L);
        automation.setName("Escalation Flow");
        automation.setModelCode("case");
        automation.setTriggerType("on_record_create");
        automation.setEnabled(true);
        automation.setActions(List.of(
                AutomationAction.builder()
                        .type("call_api")
                        .label("Enrich customer")
                        .sequence(0)
                        .config(Map.of(
                                "connectorPid", "api-1",
                                "endpointCode", "enrich",
                                "url", "https://api.example.test/enrich"))
                        .build(),
                AutomationAction.builder()
                        .type("send_webhook")
                        .label("Notify downstream")
                        .sequence(1)
                        .config(Map.of(
                                "webhookPid", "wh-1",
                                "eventType", "case.closed",
                                "url", "https://hooks.example.test/case"))
                        .build()));
        when(automationMapper.selectList(any())).thenReturn(List.of(automation));

        DecisionUsageIndexRebuildDTO summary = service.rebuild();

        ArgumentCaptor<DecisionUsageRefEntity> captor = ArgumentCaptor.forClass(DecisionUsageRefEntity.class);
        verify(usageRefMapper).deleteByTenant(10L);
        verify(usageRefMapper, times(2)).insert(captor.capture());
        assertThat(summary.getIntegrationRefs()).isEqualTo(2);
        assertThat(captor.getAllValues())
                .extracting(DecisionUsageRefEntity::getTargetType, DecisionUsageRefEntity::getTargetCode,
                        DecisionUsageRefEntity::getTargetPath)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("CONNECTOR", "api-1", "enrich"),
                        org.assertj.core.groups.Tuple.tuple("WEBHOOK", "wh-1", "case.closed"));
        assertThat(captor.getAllValues())
                .anySatisfy(ref -> {
                    assertThat(ref.getSourceType()).isEqualTo("AUTOMATION");
                    assertThat(ref.getSourceCode()).isEqualTo("auto-1");
                    assertThat(ref.getMetadataJson().get("sourceName").asText()).isEqualTo("Escalation Flow");
                    assertThat(ref.getMetadataJson().get("actionType").asText()).isEqualTo("call_api");
                });
    }

    @Test
    void rebuildIndexesAutomationRuleBindingDecisionAndFieldRefs() {
        MetaContext.setContext(10L, 20L, "tester", "Tester");
        when(versionMapper.selectList(any())).thenReturn(List.of());
        when(slaConfigMapper.selectList(any())).thenReturn(List.of());
        when(policyVersionMapper.selectList(any())).thenReturn(List.of());
        when(namedQueryMapper.selectList(any())).thenReturn(List.of());

        TriggerConfig config = new TriggerConfig();
        config.setRuleBinding(new RuleConsumerBinding(
                "AUTOMATION",
                "auto-rule-1",
                "trigger",
                RuleBindingKind.DECISION_REF,
                null,
                new DecisionBinding(
                        "approval_routing",
                        DecisionVersionPolicy.ROLLOUT,
                        null,
                        null,
                        null,
                        List.of(new DecisionBinding.InputMapping(
                                "amount",
                                RuleValueSource.field(Scope.RECORD, "data.amount"))),
                        List.of(),
                        DecisionBinding.FallbackPolicy.failClosed(),
                        200,
                        DecisionBinding.TraceMode.SAMPLED,
                        true,
                        RuleValueSource.field(Scope.RECORD, "data.recordPid"),
                        null),
                true));
        Automation automation = new Automation();
        automation.setPid("auto-rule-1");
        automation.setTenantId(10L);
        automation.setName("Rule Center Automation");
        automation.setModelCode("case");
        automation.setTriggerType("on_record_create");
        automation.setEnabled(true);
        automation.setTriggerConfig(config);
        when(automationMapper.selectList(any())).thenReturn(List.of(automation));

        DecisionUsageIndexRebuildDTO summary = service.rebuild();

        ArgumentCaptor<DecisionUsageRefEntity> captor = ArgumentCaptor.forClass(DecisionUsageRefEntity.class);
        verify(usageRefMapper).deleteByTenant(10L);
        verify(usageRefMapper, times(3)).insert(captor.capture());
        assertThat(summary.getConsumerRefs()).isEqualTo(1);
        assertThat(summary.getFieldRefs()).isEqualTo(2);
        assertThat(captor.getAllValues())
                .extracting(DecisionUsageRefEntity::getTargetType, DecisionUsageRefEntity::getTargetCode,
                        DecisionUsageRefEntity::getTargetPath, DecisionUsageRefEntity::getBinding)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                "DECISION", "approval_routing", null, "RULE_BINDING"),
                        org.assertj.core.groups.Tuple.tuple(
                                "FIELD", null, "record.data.amount", "RULE_BINDING"),
                        org.assertj.core.groups.Tuple.tuple(
                                "FIELD", null, "record.data.recordPid", "RULE_BINDING"));
    }

    @Test
    void rebuildIndexesSlaRuleBindingDecisionAndFieldRefs() {
        MetaContext.setContext(10L, 20L, "tester", "Tester");
        when(versionMapper.selectList(any())).thenReturn(List.of());
        when(automationMapper.selectList(any())).thenReturn(List.of());
        when(policyVersionMapper.selectList(any())).thenReturn(List.of());
        when(namedQueryMapper.selectList(any())).thenReturn(List.of());
        when(bpmProcessDefinitionMapper.selectList(any())).thenReturn(List.of());

        com.auraboot.framework.bpm.entity.SlaConfigEntity sla = com.auraboot.framework.bpm.entity.SlaConfigEntity.builder()
                .pid("sla-rule-1")
                .tenantId(10L)
                .name("Complaint SLA")
                .targetType("NODE")
                .targetKey("approve_task")
                .deadlineMode("FIXED")
                .deadlineValue("PT24H")
                .enabled(true)
                .deletedFlag(false)
                .ruleBinding(new RuleConsumerBinding(
                        "SLA",
                        "sla-rule-1",
                        "deadline",
                        RuleBindingKind.DECISION_REF,
                        null,
                        new DecisionBinding(
                                "complaint_sla_deadline",
                                DecisionVersionPolicy.LATEST_PUBLISHED,
                                null,
                                null,
                                null,
                                List.of(new DecisionBinding.InputMapping(
                                        "targetKey",
                                        RuleValueSource.field(Scope.RECORD, "data.targetKey"))),
                                List.of(),
                                DecisionBinding.FallbackPolicy.failClosed(),
                                200,
                                DecisionBinding.TraceMode.SAMPLED,
                                true,
                                RuleValueSource.field(Scope.RECORD, "data.priority"),
                                null),
                        true))
                .build();
        when(slaConfigMapper.selectList(any())).thenReturn(List.of(sla));

        DecisionUsageIndexRebuildDTO summary = service.rebuild();

        ArgumentCaptor<DecisionUsageRefEntity> captor = ArgumentCaptor.forClass(DecisionUsageRefEntity.class);
        verify(usageRefMapper).deleteByTenant(10L);
        verify(usageRefMapper, times(3)).insert(captor.capture());
        assertThat(summary.getConsumerRefs()).isEqualTo(1);
        assertThat(summary.getFieldRefs()).isEqualTo(2);
        assertThat(captor.getAllValues())
                .extracting(DecisionUsageRefEntity::getSourceType, DecisionUsageRefEntity::getSourceCode,
                        DecisionUsageRefEntity::getTargetType, DecisionUsageRefEntity::getTargetCode,
                        DecisionUsageRefEntity::getTargetPath, DecisionUsageRefEntity::getBinding)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                "SLA_RULE", "sla-rule-1", "DECISION",
                                "complaint_sla_deadline", null, "RULE_BINDING"),
                        org.assertj.core.groups.Tuple.tuple(
                                "SLA_RULE", "sla-rule-1", "FIELD",
                                null, "record.data.targetKey", "RULE_BINDING"),
                        org.assertj.core.groups.Tuple.tuple(
                                "SLA_RULE", "sla-rule-1", "FIELD",
                                null, "record.data.priority", "RULE_BINDING"));
    }

    @Test
    void findTargetRefsReturnsIntegrationConsumers() {
        MetaContext.setContext(10L, 20L, "tester", "Tester");
        DecisionUsageRefEntity ref = new DecisionUsageRefEntity();
        ref.setSourceType("AUTOMATION");
        ref.setSourceCode("auto-1");
        ref.setSourcePid("auto-1");
        ref.setTargetType("CONNECTOR");
        ref.setTargetCode("api-1");
        ref.setTargetPath("enrich");
        ref.setBinding("ACTION");
        ref.setMetadataJson(objectMapper.valueToTree(Map.of(
                "sourceName", "Escalation Flow",
                "actionType", "call_api")));
        when(usageRefMapper.findTargetRefs(10L, "CONNECTOR", "api-1")).thenReturn(List.of(ref));

        List<DecisionImpactRefDTO> refs = service.findTargetRefs("CONNECTOR", "api-1");

        assertThat(refs).singleElement().satisfies(dto -> {
            assertThat(dto.getSourceType()).isEqualTo("AUTOMATION");
            assertThat(dto.getSourceName()).isEqualTo("Escalation Flow");
            assertThat(dto.getTargetType()).isEqualTo("CONNECTOR");
            assertThat(dto.getTargetPath()).isEqualTo("enrich");
            assertThat(dto.getMetadata()).containsEntry("actionType", "call_api");
        });
    }

    @Test
    void rebuildIndexesNamedQueryConnectorsAndEventPolicyWebhookEvents() throws Exception {
        MetaContext.setContext(10L, 20L, "tester", "Tester");
        when(versionMapper.selectList(any())).thenReturn(List.of());
        when(automationMapper.selectList(any())).thenReturn(List.of());
        when(slaConfigMapper.selectList(any())).thenReturn(List.of());

        NamedQuery query = new NamedQuery();
        query.setPid("query-1");
        query.setTenantId(10L);
        query.setCode("customer_enrichment");
        query.setTitle("Customer Enrichment");
        query.setStatus("published");
        query.setConnectorPid("api-nq");
        query.setConnectorEndpointCode("lookup");
        when(namedQueryMapper.selectList(any())).thenReturn(List.of(query));

        DrtPolicyVersionEntity policyVersion = new DrtPolicyVersionEntity();
        policyVersion.setPid("policy-version-1");
        policyVersion.setTenantId(10L);
        policyVersion.setPolicyCode("case_closed_policy");
        policyVersion.setVersion(2);
        policyVersion.setStatus("PUBLISHED");
        policyVersion.setPhase("AFTER_COMMIT");
        policyVersion.setMatchMode("COLLECT_ALL");
        policyVersion.setRulesJson(objectMapper.readTree("""
                [{
                  "ruleCode": "notify-close",
                  "actions": [{
                    "type": "WEBHOOK",
                    "payload": { "eventType": "case.closed" }
                  }]
                }]
                """));
        when(policyVersionMapper.selectList(any())).thenReturn(List.of(policyVersion));

        DecisionUsageIndexRebuildDTO summary = service.rebuild();

        ArgumentCaptor<DecisionUsageRefEntity> captor = ArgumentCaptor.forClass(DecisionUsageRefEntity.class);
        verify(usageRefMapper).deleteByTenant(10L);
        verify(usageRefMapper, times(2)).insert(captor.capture());
        assertThat(summary.getIntegrationRefs()).isEqualTo(2);
        assertThat(captor.getAllValues())
                .extracting(DecisionUsageRefEntity::getSourceType, DecisionUsageRefEntity::getTargetType,
                        DecisionUsageRefEntity::getTargetCode, DecisionUsageRefEntity::getTargetPath)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("NAMED_QUERY", "CONNECTOR", "api-nq", "lookup"),
                        org.assertj.core.groups.Tuple.tuple("EVENT_POLICY", "WEBHOOK", "case.closed", "case.closed"));
    }

    @Test
    void rebuildIndexesEventPolicyDecisionBindingAndConditionFieldRefs() throws Exception {
        MetaContext.setContext(10L, 20L, "tester", "Tester");
        when(versionMapper.selectList(any())).thenReturn(List.of());
        when(automationMapper.selectList(any())).thenReturn(List.of());
        when(slaConfigMapper.selectList(any())).thenReturn(List.of());
        when(namedQueryMapper.selectList(any())).thenReturn(List.of());

        DrtPolicyVersionEntity policyVersion = new DrtPolicyVersionEntity();
        policyVersion.setPid("policy-version-rule-center");
        policyVersion.setTenantId(10L);
        policyVersion.setPolicyCode("case_routing_policy");
        policyVersion.setVersion(3);
        policyVersion.setStatus("PUBLISHED");
        policyVersion.setPhase("AFTER_COMMIT");
        policyVersion.setMatchMode("COLLECT_ALL");
        policyVersion.setRulesJson(objectMapper.readTree("""
                [{
                  "ruleCode": "route-high-value",
                  "conditionSpec": {
                    "root": {
                      "type": "compare",
                      "left": { "type": "path", "scope": "record", "path": "data.amount", "dataType": "decimal" },
                      "operator": "GTE",
                      "right": { "type": "literal", "value": 1000, "dataType": "decimal" }
                    }
                  },
                  "actions": [{
                    "type": "ROUTE",
                    "decisionBinding": {
                      "decisionCode": "approval_routing",
                      "versionPolicy": "ROLLOUT",
                      "inputMappings": [
                        { "input": "amount", "source": { "kind": "field", "scope": "record", "path": "data.amount" } }
                      ]
                    }
                  }]
                }]
                """));
        when(policyVersionMapper.selectList(any())).thenReturn(List.of(policyVersion));

        DecisionUsageIndexRebuildDTO summary = service.rebuild();

        ArgumentCaptor<DecisionUsageRefEntity> captor = ArgumentCaptor.forClass(DecisionUsageRefEntity.class);
        verify(usageRefMapper).deleteByTenant(10L);
        verify(usageRefMapper, times(2)).insert(captor.capture());
        assertThat(summary.getConsumerRefs()).isEqualTo(1);
        assertThat(summary.getFieldRefs()).isEqualTo(1);
        assertThat(captor.getAllValues())
                .extracting(DecisionUsageRefEntity::getSourceType, DecisionUsageRefEntity::getTargetType,
                        DecisionUsageRefEntity::getTargetCode, DecisionUsageRefEntity::getTargetPath,
                        DecisionUsageRefEntity::getBinding)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                "EVENT_POLICY", "DECISION", "approval_routing", null, "VERSION_RULES"),
                        org.assertj.core.groups.Tuple.tuple(
                                "EVENT_POLICY", "FIELD", null, "record.data.amount", "VERSION_RULES"));
    }

    @Test
    void rebuildIndexesBpmDesignerNodeAndEdgeRuleRefs() throws Exception {
        MetaContext.setContext(10L, 20L, "tester", "Tester");
        when(versionMapper.selectList(any())).thenReturn(List.of());
        when(automationMapper.selectList(any())).thenReturn(List.of());
        when(slaConfigMapper.selectList(any())).thenReturn(List.of());
        when(policyVersionMapper.selectList(any())).thenReturn(List.of());
        when(namedQueryMapper.selectList(any())).thenReturn(List.of());

        BpmProcessDefinition process = new BpmProcessDefinition();
        process.setPid("bpm-proc-1");
        process.setTenantId(10L);
        process.setProcessKey("approval_process");
        process.setProcessName("Approval Process");
        process.setStatus("draft");
        process.setVersion(4);
        process.setIsCurrent(true);
        process.setDeletedFlag(false);
        process.setExtension(Map.of("designerJson", """
                {
                  "key": "approval_process",
                  "nodes": [
                    {
                      "id": "gateway_route",
                      "type": "exclusiveGateway",
                      "data": {
                        "type": "exclusiveGateway",
                        "label": "Route",
                        "config": {
                          "ruleBinding": {
                            "consumerType": "BPM",
                            "consumerCode": "approval_process",
                            "consumerNodeId": "gateway_route",
                            "bindingKind": "DECISION_REF",
                            "decisionBinding": {
                              "decisionCode": "approval_routing",
                              "versionPolicy": "ROLLOUT",
                              "inputMappings": [
                                {
                                  "input": "amount",
                                  "source": { "kind": "field", "scope": "record", "path": "amount" }
                                }
                              ],
                              "correlationKey": { "kind": "field", "scope": "record", "path": "requestId" }
                            },
                            "enabled": true
                          }
                        }
                      }
                    },
                    {
                      "id": "task_assign",
                      "type": "userTask",
                      "data": {
                        "type": "userTask",
                        "label": "Approve",
                        "config": {
                          "assignee": {
                            "type": "decision",
                            "decisionBinding": {
                              "decisionCode": "task_assignee",
                              "versionPolicy": "LATEST_PUBLISHED",
                              "inputMappings": [
                                {
                                  "input": "department",
                                  "source": { "kind": "field", "scope": "record", "path": "requester.departmentId" }
                                }
                              ]
                            }
                          }
                        }
                      }
                    }
                  ],
                  "edges": [
                    {
                      "id": "edge_high_amount",
                      "source": "gateway_route",
                      "target": "task_assign",
                      "data": {
                        "label": "High amount",
                        "conditionSpec": {
                          "root": {
                            "type": "compare",
                            "left": { "type": "path", "scope": "record", "path": "amount", "dataType": "decimal" },
                            "operator": "GTE",
                            "right": { "type": "literal", "value": 1000, "dataType": "decimal" }
                          }
                        }
                      }
                    }
                  ]
                }
                """));
        when(bpmProcessDefinitionMapper.selectList(any())).thenReturn(List.of(process));

        DecisionUsageIndexRebuildDTO summary = service.rebuild();

        ArgumentCaptor<DecisionUsageRefEntity> captor = ArgumentCaptor.forClass(DecisionUsageRefEntity.class);
        verify(usageRefMapper).deleteByTenant(10L);
        verify(usageRefMapper, times(6)).insert(captor.capture());
        assertThat(summary.getConsumerRefs()).isEqualTo(2);
        assertThat(summary.getFieldRefs()).isEqualTo(4);
        assertThat(captor.getAllValues())
                .extracting(DecisionUsageRefEntity::getSourceType, DecisionUsageRefEntity::getSourceCode,
                        DecisionUsageRefEntity::getSourceVersion, DecisionUsageRefEntity::getTargetType,
                        DecisionUsageRefEntity::getTargetCode, DecisionUsageRefEntity::getTargetPath,
                        DecisionUsageRefEntity::getBinding)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                "BPM_PROCESS", "approval_process", "4",
                                "DECISION", "approval_routing", null, "DESIGNER_NODE"),
                        org.assertj.core.groups.Tuple.tuple(
                                "BPM_PROCESS", "approval_process", "4",
                                "DECISION", "task_assignee", null, "DESIGNER_NODE"),
                        org.assertj.core.groups.Tuple.tuple(
                                "BPM_PROCESS", "approval_process", "4",
                                "FIELD", null, "record.amount", "DESIGNER_NODE"),
                        org.assertj.core.groups.Tuple.tuple(
                                "BPM_PROCESS", "approval_process", "4",
                                "FIELD", null, "record.requestId", "DESIGNER_NODE"),
                        org.assertj.core.groups.Tuple.tuple(
                                "BPM_PROCESS", "approval_process", "4",
                                "FIELD", null, "record.requester.departmentId", "DESIGNER_NODE"),
                        org.assertj.core.groups.Tuple.tuple(
                                "BPM_PROCESS", "approval_process", "4",
                                "FIELD", null, "record.amount", "DESIGNER_EDGE"));
        assertThat(captor.getAllValues())
                .anySatisfy(ref -> {
                    assertThat(ref.getTargetCode()).isEqualTo("approval_routing");
                    assertThat(ref.getMetadataJson().get("nodeId").asText()).isEqualTo("gateway_route");
                    assertThat(ref.getMetadataJson().get("nodeType").asText()).isEqualTo("exclusiveGateway");
                })
                .anySatisfy(ref -> {
                    assertThat(ref.getBinding()).isEqualTo("DESIGNER_EDGE");
                    assertThat(ref.getMetadataJson().get("edgeId").asText()).isEqualTo("edge_high_amount");
                    assertThat(ref.getMetadataJson().get("sourceNodeId").asText()).isEqualTo("gateway_route");
                    assertThat(ref.getMetadataJson().get("targetNodeId").asText()).isEqualTo("task_assign");
                });
    }

    @Test
    void rebuildIndexesPermissionPolicyDecisionAndFieldRefs() {
        MetaContext.setContext(10L, 20L, "tester", "Tester");
        when(versionMapper.selectList(any())).thenReturn(List.of());
        when(automationMapper.selectList(any())).thenReturn(List.of());
        when(slaConfigMapper.selectList(any())).thenReturn(List.of());
        when(policyVersionMapper.selectList(any())).thenReturn(List.of());
        when(namedQueryMapper.selectList(any())).thenReturn(List.of());
        when(bpmProcessDefinitionMapper.selectList(any())).thenReturn(List.of());

        RolePermission rolePermission = new RolePermission();
        rolePermission.setPid("rp-abac-1");
        rolePermission.setTenantId(10L);
        rolePermission.setRoleId(700L);
        rolePermission.setPermissionId(500L);
        rolePermission.setGrantType("grant");
        rolePermission.setStatus("active");
        rolePermission.setDeletedFlag(false);
        rolePermission.setConditions(Map.of(
                "dynamicAbac", Map.of(
                        "decisionBinding", Map.of(
                                "decisionCode", "permission_amount_guard",
                                "versionPolicy", "LATEST_PUBLISHED",
                                "inputMappings", List.of(Map.of(
                                        "input", "amount",
                                        "source", Map.of(
                                                "kind", "field",
                                                "scope", "record",
                                                "path", "amount")))))));
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of(rolePermission));

        Permission permission = new Permission();
        permission.setId(500L);
        permission.setCode("model.invoice.approve");
        permission.setName("Approve Invoice");
        permission.setResourceType("model");
        permission.setResourceCode("invoice");
        permission.setAction("approve");
        when(permissionMapper.selectById(500L)).thenReturn(permission);

        DecisionUsageIndexRebuildDTO summary = service.rebuild();

        ArgumentCaptor<DecisionUsageRefEntity> captor = ArgumentCaptor.forClass(DecisionUsageRefEntity.class);
        verify(usageRefMapper).deleteByTenant(10L);
        verify(usageRefMapper, times(2)).insert(captor.capture());
        assertThat(summary.getConsumerRefs()).isEqualTo(1);
        assertThat(summary.getFieldRefs()).isEqualTo(1);
        assertThat(captor.getAllValues())
                .extracting(DecisionUsageRefEntity::getSourceType, DecisionUsageRefEntity::getSourceCode,
                        DecisionUsageRefEntity::getSourcePid, DecisionUsageRefEntity::getTargetType,
                        DecisionUsageRefEntity::getTargetCode, DecisionUsageRefEntity::getTargetPath,
                        DecisionUsageRefEntity::getBinding)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                "PERMISSION_POLICY", "model.invoice.approve", "rp-abac-1",
                                "DECISION", "permission_amount_guard", null, "ROLE_PERMISSION_CONDITION"),
                        org.assertj.core.groups.Tuple.tuple(
                                "PERMISSION_POLICY", "model.invoice.approve", "rp-abac-1",
                                "FIELD", null, "record.amount", "ROLE_PERMISSION_CONDITION"));
        assertThat(captor.getAllValues())
                .allSatisfy(ref -> {
                    assertThat(ref.getMetadataJson().get("permissionCode").asText())
                            .isEqualTo("model.invoice.approve");
                    assertThat(ref.getMetadataJson().get("roleId").asLong()).isEqualTo(700L);
                    assertThat(ref.getMetadataJson().get("sourceName").asText()).isEqualTo("Approve Invoice");
                });
    }
}
