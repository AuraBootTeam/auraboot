package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.agent.entity.AgentDefinition;
import com.auraboot.framework.agent.mapper.AgentDefinitionMapper;
import com.auraboot.framework.agent.mapper.AgentEvalCaseMapper;
import com.auraboot.framework.dashboard.dto.*;
import com.auraboot.framework.dashboard.service.DashboardService;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.NamedQuery;
import com.auraboot.framework.meta.mapper.*;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.exception.PluginException;
import com.auraboot.framework.plugin.mapper.PluginResourceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Verifies import state, conflict handling and service payloads at persistence ports. */
@ExtendWith(MockitoExtension.class)
class PluginResourceImporterLifecycleTest {
    @Mock CommandService commandService;
    @Mock BindingRuleMapper bindingRuleMapper;
    @Mock MetaModelService metaModelService;
    @Mock MetaFieldService metaFieldService;
    @Mock MetaModelMapper metaModelMapper;
    @Mock MetaFieldMapper metaFieldMapper;
    @Mock CommandDefinitionMapper commandDefinitionMapper;
    @Mock com.auraboot.framework.permission.service.PermissionService permissionService;
    @Mock com.auraboot.framework.permission.mapper.PermissionMapper permissionMapper;
    @Mock com.auraboot.framework.menu.service.MenuService menuService;
    @Mock com.auraboot.framework.menu.mapper.MenuMapper menuMapper;
    @Mock PageSchemaService pageSchemaService;
    @Mock PageSchemaMapper pageSchemaMapper;
    @Mock com.auraboot.framework.rbac.mapper.RoleMapper roleMapper;
    @Mock DashboardService dashboardService;
    @Mock NamedQueryService namedQueryService;
    @Mock NamedQueryMapper namedQueryMapper;
    @Mock AgentDefinitionMapper agentDefinitionMapper;
    @Mock AgentEvalCaseMapper agentEvalCaseMapper;
    @Mock PluginResourceMapper pluginResourceMapper;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock DictService dictService;
    @Mock DictMapper dictMapper;
    @Spy ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks PluginResourceImporterImpl importer;

    DashboardDefinitionDTO dashboard() {
        return DashboardDefinitionDTO.builder().code("invoice_board").title("Invoices")
                .widgets(List.of(Map.of("id", "revenue", "type", "metric")))
                .layoutConfig(Map.of("columns", 6, "gap", 12)).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"draft", "published"})
    void dashboardCreateAndUpdateCarryWidgetLayoutAndPublication(String status) {
        var dto = dashboard(); dto.setStatus(status);
        var stored = new DashboardDTO(); stored.setPid("board-42");
        when(dashboardService.create(any())).thenReturn(stored);
        var created = importer.importDashboard(dto, "plugin-42", "import-42", 42L, ImportRequest.ConflictStrategy.OVERWRITE);
        assertThat(created.getActionEnum()).isEqualTo(ResourceAction.CREATE);
        var capture = ArgumentCaptor.forClass(DashboardCreateRequest.class); verify(dashboardService).create(capture.capture());
        assertThat(capture.getValue().getWidgets().get(0).get("id").asText()).isEqualTo("revenue");
        assertThat(capture.getValue().getLayoutConfig().get("columns").asInt()).isEqualTo(6);
        assertThat(capture.getValue().getLayoutConfig().get("rowHeight").asInt()).isEqualTo(100);
        when(dashboardService.findByCode("invoice_board")).thenReturn(stored);
        var updated = importer.importDashboard(dto, "plugin-42", "import-43", 42L, ImportRequest.ConflictStrategy.OVERWRITE);
        assertThat(updated.getActionEnum()).isEqualTo(ResourceAction.UPDATE);
        verify(dashboardService).update(eq("board-42"), argThat(request -> "Invoices".equals(request.getTitle())));
        verify(dashboardService, times("draft".equals(status) ? 0 : 2)).publish("board-42");
    }

    @Test
    void dashboardConflictPoliciesAndInvalidDefinitionPreventWrites() {
        var stored = new DashboardDTO(); stored.setPid("board-42");
        when(dashboardService.findByCode("invoice_board")).thenReturn(stored);
        assertThatThrownBy(() -> importer.importDashboard(dashboard(), "p", "i", 42L, ImportRequest.ConflictStrategy.ERROR)).isInstanceOf(PluginException.class);
        assertThat(importer.importDashboard(dashboard(), "p", "i", 42L, ImportRequest.ConflictStrategy.SKIP).getActionEnum()).isEqualTo(ResourceAction.SKIP);
        assertThatThrownBy(() -> importer.importDashboard(new DashboardDefinitionDTO(), "p", "i", 42L, ImportRequest.ConflictStrategy.OVERWRITE)).isInstanceOf(PluginException.class);
        verify(dashboardService, never()).create(any()); verify(dashboardService, never()).update(anyString(), any());
    }

    NamedQueryDefinitionDTO query() {
        var field = new NamedQueryFieldRequest(); field.setFieldCode("amount"); field.setColumnExpr("amount"); field.setDataType("number");
        return NamedQueryDefinitionDTO.builder().code("invoice_totals").title("Invoice totals").fromSql("ab_invoice")
                .baseWhere(objectMapper.valueToTree(Map.of("field", "amount", "op", "GTE", "value", 0))).defaultOrder(objectMapper.valueToTree(List.of(Map.of("field", "amount", "direction", "DESC")))).fields(List.of(field)).build();
    }

    @Test
    void namedQueryCreationCopiesFieldsAndPreservesPermissionChecksAndSnapshot() {
        var dto = query(); dto.setStatus("enabled"); dto.setTags(List.of("finance")); dto.setMetadata(objectMapper.valueToTree(Map.of("owner", "billing")));
        var stored = new NamedQueryDTO(); stored.setPid("query-42"); stored.setId(42L);
        when(namedQueryService.create(any())).thenReturn(stored);
        var result = importer.importNamedQuery(dto, "plugin-42", "import-42", 42L, ImportRequest.ConflictStrategy.OVERWRITE);
        assertThat(result.getActionEnum()).isEqualTo(ResourceAction.CREATE);
        var capture = ArgumentCaptor.forClass(NamedQueryCreateRequest.class); verify(namedQueryService).create(capture.capture());
        assertThat(capture.getValue().getStatus()).isEqualTo("published");
        assertThat(capture.getValue().getCheckPermissions()).isTrue();
        assertThat(capture.getValue().getValidateSql()).isTrue();
        assertThat(capture.getValue().getFields().get(0)).isNotSameAs(dto.getFields().get(0));
        assertThat(capture.getValue().getFields().get(0).getColumnExpr()).isEqualTo("amount");
        assertThat(result.getCurrentState()).containsEntry("fromSql", "ab_invoice");
        verify(namedQueryService).markFieldsAsPluginSource("invoice_totals");
    }

    @ParameterizedTest
    @ValueSource(strings = {"fieldCode", "columnExpr", "dataType"})
    void malformedNamedQueryFieldStopsBeforePersistence(String missing) {
        var dto = query(); var field = dto.getFields().get(0);
        switch (missing) { case "fieldCode" -> field.setFieldCode(null); case "columnExpr" -> field.setColumnExpr(null); default -> field.setDataType(null); }
        assertThatThrownBy(() -> importer.importNamedQuery(dto, "p", "i", 42L, ImportRequest.ConflictStrategy.OVERWRITE)).isInstanceOf(PluginException.class);
        verify(namedQueryService, never()).create(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"draft", "testing", "published", "deprecated", "archived"})
    void namedQueryReimportReturnsToRequestedLifecycleAndKeepsImportedSql(String status) {
        var dto = query(); dto.setStatus(status);
        var stored = new NamedQuery(); stored.setPid("query-42"); stored.setId(42L); stored.setStatus("draft"); stored.setFromSql("stale_sql");
        var view = new NamedQueryDTO(); view.setPid("query-42"); view.setStatus("published");
        when(namedQueryService.findByCode("invoice_totals")).thenReturn(view);
        when(namedQueryMapper.findByCode("invoice_totals")).thenReturn(stored);
        when(namedQueryService.findByPid("query-42")).thenReturn(view);
        doAnswer(call -> { view.setStatus(call.getArgument(1)); return null; }).when(namedQueryService).updateStatus(anyString(), anyString());
        var result = importer.importNamedQuery(dto, "plugin-42", "import-42", 42L, ImportRequest.ConflictStrategy.OVERWRITE);
        assertThat(result.getActionEnum()).isEqualTo(ResourceAction.UPDATE);
        assertThat(view.getStatus()).isEqualTo(status);
        verify(namedQueryService).update(eq("query-42"), argThat(request -> "ab_invoice".equals(request.getFromSql())));
        verify(namedQueryService).batchSaveFields(eq("invoice_totals"), argThat(request -> request.getClearExisting() && "plugin".equals(request.getSource())));
        verify(namedQueryMapper).updateById(argThat((NamedQuery value) -> "ab_invoice".equals(value.getFromSql())));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void agentImportPreservesIdentityAndCarriesConfiguration(boolean existing) {
        var dto = AgentDefinitionDTO.builder().agentCode("invoice_assistant").name("Invoice assistant")
                .model("test-model").tools(List.of("invoice.read")).skills(List.of("summarize"))
                .guardrails(Map.of("maxWrites", 0)).systemPrompt("Read invoices").maxTools(4).build();
        var stored = new AgentDefinition(); stored.setPid("agent-42"); stored.setId(42L);
        if (existing) when(agentDefinitionMapper.selectOne(any())).thenReturn(stored);
        var result = importer.importAgentDefinition(dto, "plugin-42", "import-42", 42L, ImportRequest.ConflictStrategy.OVERWRITE);
        assertThat(result.getActionEnum()).isEqualTo(existing ? ResourceAction.UPDATE : ResourceAction.CREATE);
        var capture = ArgumentCaptor.forClass(AgentDefinition.class);
        if (existing) verify(agentDefinitionMapper).updateById(capture.capture()); else verify(agentDefinitionMapper).insert(capture.capture());
        assertThat(capture.getValue().getAgentCode()).isEqualTo("invoice_assistant");
        assertThat(capture.getValue().getTools()).isEqualTo("[\"invoice.read\"]");
        assertThat(capture.getValue().getMaxTools()).isEqualTo(4);
        assertThat(capture.getValue().getAllowedOperations()).containsExactly("query", "create", "update", "delete", "transition");
        verify(jdbcTemplate).update("DELETE FROM ab_agent_eval_case WHERE tenant_id = ? AND agent_code = ?", 42L, "invoice_assistant");
        if (existing) assertThat(result.getResourcePid()).isEqualTo("agent-42");
    }

    @ParameterizedTest @ValueSource(strings={"CREATE","OVERWRITE","SKIP","ERROR"})
    void bindingConflictIdentityKeepsUnrelatedRulesAndCarriesConfiguration(String policy) {
        var command=new com.auraboot.framework.meta.dto.CommandDefinitionDTO(); command.setPid("c-42");
        var input=com.auraboot.framework.plugin.dto.imports.BindingRuleDTO.builder().commandCode("invoice_create").ruleType("VALIDATION")
                .expression("amount >= 0").targetModel("invoice").targetField("amount").sourceField("value").sequence(3).enabled(false).config(Map.of("strict",true)).build();
        var matching=new com.auraboot.framework.meta.dto.BindingRuleDTO(); matching.setPid("matching"); matching.setRuleType("VALIDATION"); matching.setSequence(3);
        matching.setTargetModel(" invoice "); matching.setTargetField("amount"); matching.setSourceField("value"); matching.setHandlerClass(" ");
        var unrelated=new com.auraboot.framework.meta.dto.BindingRuleDTO(); unrelated.setPid("unrelated"); unrelated.setRuleType("VALIDATION"); unrelated.setSequence(4);
        when(commandService.findByCode("invoice_create")).thenReturn(command);
        when(commandService.getBindingRules("c-42")).thenReturn("CREATE".equals(policy) ? List.of(unrelated) : List.of(matching,unrelated));
        var strategy="CREATE".equals(policy) ? ImportRequest.ConflictStrategy.OVERWRITE : ImportRequest.ConflictStrategy.valueOf(policy);
        if ("ERROR".equals(policy)) {
            assertThatThrownBy(() -> importer.importBindingRule(input,"p","i",42L,strategy)).isInstanceOf(PluginException.class);
            verify(commandService,never()).addBindingRule(anyString(),any()); return;
        }
        if ("SKIP".equals(policy)) {
            assertThat(importer.importBindingRule(input,"p","i",42L,strategy).getActionEnum()).isEqualTo(ResourceAction.SKIP);
            verify(commandService,never()).addBindingRule(anyString(),any()); return;
        }
        var created=new com.auraboot.framework.meta.dto.BindingRuleDTO(); created.setPid("new-rule");
        when(commandService.addBindingRule(eq("c-42"),any())).thenReturn(created);
        var result=importer.importBindingRule(input,"p","i",42L,strategy);
        assertThat(result.getActionEnum()).isEqualTo("CREATE".equals(policy) ? ResourceAction.CREATE : ResourceAction.UPDATE);
        assertThat(result.getResourceCode()).isEqualTo("invoice_create:VALIDATION:3:invoice:amount:value:-:-");
        verify(commandService).addBindingRule(eq("c-42"),argThat(q -> q.getExpression().equals("amount >= 0") && q.getConfig().equals("{\"strict\":true}") && !q.getEnabled()));
        verify(bindingRuleMapper).updatePluginPid("p","new-rule"); verify(commandService,never()).removeBindingRule("unrelated");
        verify(commandService,times("CREATE".equals(policy) ? 0 : 1)).removeBindingRule("matching");
    }

    @Test void unknownBindingCommandPreservesFailureCauseAndPreventsPersistence() {
        when(commandService.findByCode("missing")).thenThrow(new IllegalArgumentException("missing command"));
        var input=com.auraboot.framework.plugin.dto.imports.BindingRuleDTO.builder().commandCode("missing").ruleType("VALIDATION").build();
        assertThatThrownBy(() -> importer.importBindingRule(input,"p","i",42L,ImportRequest.ConflictStrategy.OVERWRITE)).isInstanceOf(PluginException.class).hasCauseInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(bindingRuleMapper);
    }

    @ParameterizedTest @ValueSource(strings={"MODEL","FIELD","COMMAND","PERMISSION","MENU","PAGE","DICT","NAMED_QUERY"})
    void rollbackArchivesResourceWhenServiceDeletionFails(String type) {
        var resource=PluginResource.builder().resourceType(type).resourcePid("r-42").resourceId(42L).tenantId(43L).build();
        switch(type) {
            case "MODEL" -> doThrow(new IllegalStateException("offline")).when(metaModelService).delete("r-42");
            case "FIELD" -> doThrow(new IllegalStateException("offline")).when(metaFieldService).delete("r-42");
            case "COMMAND" -> doThrow(new IllegalStateException("offline")).when(commandService).delete("r-42");
            case "PERMISSION" -> doThrow(new IllegalStateException("offline")).when(permissionService).delete(42L);
            case "MENU" -> doThrow(new IllegalStateException("offline")).when(menuService).deleteMenu(42L);
            case "PAGE" -> doThrow(new IllegalStateException("offline")).when(pageSchemaService).delete("r-42");
            case "DICT" -> doThrow(new IllegalStateException("offline")).when(dictService).delete("r-42");
            case "NAMED_QUERY" -> doThrow(new IllegalStateException("offline")).when(namedQueryService).delete("r-42");
        }
        importer.rollbackResource(resource);
        switch(type) {
            case "MODEL" -> verify(metaModelMapper).archiveByPid("r-42");
            case "FIELD" -> verify(metaFieldMapper).archiveByPid("r-42");
            case "COMMAND" -> verify(commandDefinitionMapper).archiveByPid("r-42");
            case "PERMISSION" -> verify(permissionMapper).softDelete(42L);
            case "MENU" -> verify(menuMapper).softDeleteById(42L);
            case "PAGE" -> verify(pageSchemaMapper).archiveByPid("r-42");
            case "DICT" -> verify(dictMapper).softDeleteByPid("r-42");
            case "NAMED_QUERY" -> verify(namedQueryMapper).updateStatusByPid("r-42","archived");
        }
    }
    @Test void agentRestoreScopesEvalCasesToTenantAndAgentIdentity() {
        var resource=PluginResource.builder().resourceType("AGENT_DEFINITION").resourcePid("agent-42").resourceCode("invoice_agent").tenantId(42L).build();
        importer.restoreResource(resource);
        verify(jdbcTemplate).update(contains("UPDATE ab_agent_definition"),eq("agent-42"));
        verify(jdbcTemplate).update(contains("WHERE tenant_id = ? AND agent_code = ?"),eq(42L),eq("invoice_agent"));
    }
}
