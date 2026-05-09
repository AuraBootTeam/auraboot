package com.auraboot.framework.notification.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.notification.dto.NotificationRuleDTO;
import com.auraboot.framework.notification.dto.NotificationRuleRequest;
import com.auraboot.framework.notification.dto.NotificationRuleTestResult;
import com.auraboot.framework.notification.entity.NotificationRule;
import com.auraboot.framework.notification.mapper.NotificationRuleMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationRuleServiceTest {

    @Mock
    NotificationRuleMapper ruleMapper;
    @Mock
    DynamicDataService dynamicDataService;
    @Mock
    DataSource dataSource;

    private NotificationRuleService svc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        svc = new NotificationRuleService(ruleMapper, dynamicDataService, objectMapper, dataSource);
        MetaContext.setSystemTenantContext(10L);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    private NotificationRule rule(long id, String code) {
        NotificationRule r = new NotificationRule();
        r.setId(id);
        r.setTenantId(10L);
        r.setCode(code);
        r.setName("name-" + id);
        r.setEnabled(true);
        r.setTriggerType("scheduled");
        return r;
    }

    private NotificationRuleRequest request(String code) {
        NotificationRuleRequest req = new NotificationRuleRequest();
        req.setCode(code);
        req.setName("test rule");
        req.setEnabled(true);
        req.setTriggerType("scheduled");
        req.setActionChannel("in_app");
        req.setActionTemplateCode("tpl");
        return req;
    }

    @Test
    void listRules_returnsTenantScopedDtos() {
        when(ruleMapper.findAllByTenant(10L)).thenReturn(List.of(rule(1, "a"), rule(2, "b")));
        List<NotificationRuleDTO> all = svc.listRules();
        assertThat(all).hasSize(2);
        assertThat(all.get(0).getCode()).isEqualTo("a");
    }

    @Test
    void getRule_returnsDtoWhenFound() {
        when(ruleMapper.selectOne(any())).thenReturn(rule(7, "c"));
        NotificationRuleDTO dto = svc.getRule(7L);
        assertThat(dto.getCode()).isEqualTo("c");
    }

    @Test
    void getRule_throwsWhenMissing() {
        when(ruleMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> svc.getRule(7L)).isInstanceOf(BusinessException.class);
    }

    @Test
    void createRule_persists() {
        NotificationRuleRequest req = request("new");
        when(ruleMapper.insert(any(NotificationRule.class))).thenReturn(1);
        NotificationRuleDTO dto = svc.createRule(req);
        assertThat(dto.getCode()).isEqualTo("new");
        verify(ruleMapper).insert(any(NotificationRule.class));
    }

    @Test
    void createRule_translatesDuplicateKeyToBusinessException() {
        when(ruleMapper.insert(any(NotificationRule.class)))
                .thenThrow(new DuplicateKeyException("dup"));
        assertThatThrownBy(() -> svc.createRule(request("dup")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void updateRule_appliesRequestAndUpdates() {
        NotificationRule existing = rule(5L, "old");
        when(ruleMapper.selectOne(any())).thenReturn(existing);
        NotificationRuleRequest req = request("updated");
        NotificationRuleDTO dto = svc.updateRule(5L, req);
        assertThat(dto.getCode()).isEqualTo("updated");
        verify(ruleMapper).updateById(existing);
    }

    @Test
    void updateRule_throwsWhenMissing() {
        when(ruleMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> svc.updateRule(5L, request("x")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void deleteRule_succeeds() {
        when(ruleMapper.update(isNull(), any())).thenReturn(1);
        svc.deleteRule(5L);
        verify(ruleMapper).update(isNull(), any());
    }

    @Test
    void deleteRule_throwsWhenNoRowsAffected() {
        when(ruleMapper.update(isNull(), any())).thenReturn(0);
        assertThatThrownBy(() -> svc.deleteRule(5L)).isInstanceOf(BusinessException.class);
    }

    @Test
    void toggleEnabled_updatesAndReturns() {
        NotificationRule existing = rule(8L, "x");
        existing.setEnabled(true);
        when(ruleMapper.selectOne(any())).thenReturn(existing);
        NotificationRuleDTO dto = svc.toggleEnabled(8L, false);
        assertThat(dto.getEnabled()).isFalse();
        verify(ruleMapper).updateById(existing);
    }

    @Test
    void toggleEnabled_throwsWhenMissing() {
        when(ruleMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> svc.toggleEnabled(8L, false))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void evaluateCondition_failsWhenNoModelCode() {
        NotificationRuleDTO dto = new NotificationRuleDTO();
        dto.setId(1L);
        NotificationRuleTestResult result = svc.evaluateCondition(dto);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("no condition model");
    }

    @Test
    void evaluateCondition_succeedsWithMatchedRecords() {
        NotificationRuleDTO dto = new NotificationRuleDTO();
        dto.setId(1L);
        dto.setConditionModelCode("user");
        dto.setConditionFilter("[]");
        PaginationResult<Map<String, Object>> page = PaginationResult.of(
                List.of(Map.of("k", 1)), 3L, 1, 5);
        when(dynamicDataService.list(anyString(), any(DynamicQueryRequest.class))).thenReturn(page);
        NotificationRuleTestResult result = svc.evaluateCondition(dto);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMatchedCount()).isEqualTo(3);
        assertThat(result.getSampleRecords()).hasSize(1);
    }

    @Test
    void evaluateCondition_zeroMatchesGivesNoRecordsSummary() {
        NotificationRuleDTO dto = new NotificationRuleDTO();
        dto.setId(1L);
        dto.setConditionModelCode("user");
        PaginationResult<Map<String, Object>> page = PaginationResult.of(List.of(), 0L, 1, 5);
        when(dynamicDataService.list(anyString(), any(DynamicQueryRequest.class))).thenReturn(page);
        NotificationRuleTestResult result = svc.evaluateCondition(dto);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMatchedCount()).isZero();
        assertThat(result.getSummary()).contains("No records");
    }

    @Test
    void evaluateCondition_handlesDynamicServiceFailure() {
        NotificationRuleDTO dto = new NotificationRuleDTO();
        dto.setId(1L);
        dto.setConditionModelCode("user");
        when(dynamicDataService.list(anyString(), any(DynamicQueryRequest.class)))
                .thenThrow(new RuntimeException("boom"));
        NotificationRuleTestResult result = svc.evaluateCondition(dto);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("Evaluation error");
    }

    @Test
    void evaluateCondition_parsesValidFilterJson() {
        NotificationRuleDTO dto = new NotificationRuleDTO();
        dto.setId(1L);
        dto.setConditionModelCode("user");
        dto.setConditionFilter("[{\"fieldName\":\"x\",\"operator\":\"EQ\",\"value\":1}]");
        PaginationResult<Map<String, Object>> page = PaginationResult.of(List.of(), 0L, 1, 5);
        when(dynamicDataService.list(anyString(), any(DynamicQueryRequest.class))).thenReturn(page);
        NotificationRuleTestResult result = svc.evaluateCondition(dto);
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void evaluateCondition_unknownOperatorFallsBackToEq() {
        NotificationRuleDTO dto = new NotificationRuleDTO();
        dto.setId(1L);
        dto.setConditionModelCode("user");
        dto.setConditionFilter("[{\"fieldName\":\"x\",\"operator\":\"NOT_AN_OP\",\"value\":1}]");
        PaginationResult<Map<String, Object>> page = PaginationResult.of(List.of(), 0L, 1, 5);
        when(dynamicDataService.list(anyString(), any(DynamicQueryRequest.class))).thenReturn(page);
        NotificationRuleTestResult result = svc.evaluateCondition(dto);
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void evaluateCondition_invalidFilterJsonTreatedAsEmpty() {
        NotificationRuleDTO dto = new NotificationRuleDTO();
        dto.setId(1L);
        dto.setConditionModelCode("user");
        dto.setConditionFilter("###");
        PaginationResult<Map<String, Object>> page = PaginationResult.of(List.of(), 0L, 1, 5);
        when(dynamicDataService.list(anyString(), any(DynamicQueryRequest.class))).thenReturn(page);
        NotificationRuleTestResult result = svc.evaluateCondition(dto);
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void testEvaluateRule_loadsRuleAndEvaluates() {
        NotificationRule existing = rule(3L, "rule-x");
        existing.setConditionModelCode("user");
        when(ruleMapper.selectOne(any())).thenReturn(existing);
        PaginationResult<Map<String, Object>> page = PaginationResult.of(List.of(), 0L, 1, 5);
        when(dynamicDataService.list(anyString(), any(DynamicQueryRequest.class))).thenReturn(page);
        NotificationRuleTestResult result = svc.testEvaluateRule(3L);
        assertThat(result.isSuccess()).isTrue();
    }
}
