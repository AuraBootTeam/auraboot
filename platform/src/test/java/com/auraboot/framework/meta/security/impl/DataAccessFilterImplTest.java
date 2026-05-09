package com.auraboot.framework.meta.security.impl;

import com.auraboot.framework.meta.dto.DataAccessLogRequest;
import com.auraboot.framework.meta.dto.DataFilterRequest;
import com.auraboot.framework.meta.dto.DataFilterResult;
import com.auraboot.framework.meta.dto.DataMaskingRequest;
import com.auraboot.framework.meta.dto.DataMaskingResult;
import com.auraboot.framework.meta.dto.SimpleResult;
import com.auraboot.framework.permission.service.UserPermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Security-focused unit tests for {@link DataAccessFilterImpl}.
 *
 * <p>Each test asserts a security boundary: deny without permission, allow with permission,
 * field-level masking behaviour, fail-closed semantics on invalid inputs, and PII redaction.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DataAccessFilterImplTest {

    @Mock
    private UserPermissionService userPermissionService;

    @InjectMocks
    private DataAccessFilterImpl filter;

    private static final Long USER_ID = 100L;
    private static final Long TENANT_ID = 1L;
    private static final String MODEL = "customer";

    @BeforeEach
    void setUp() {
        // Default: no permissions; tests opt-in to grants.
        lenient().when(userPermissionService.hasPermission(anyLong(), anyString())).thenReturn(false);
    }

    // ===================== filterQueryResult =====================

    /** Boundary: user lacks model.read => returns success=false and zero data leaked. */
    @Test
    void filterQueryResult_denies_when_user_lacks_model_read() {
        when(userPermissionService.hasPermission(USER_ID, "model.customer.read")).thenReturn(false);

        DataFilterRequest request = DataFilterRequest.builder()
                .userId(USER_ID)
                .tenantId(TENANT_ID)
                .modelCode(MODEL)
                .data(List.of(Map.of("name", "Alice")))
                .build();

        DataFilterResult result = filter.filterQueryResult(request);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("无权访问");
        assertThat(result.getFilteredData()).isNull();
    }

    /** Boundary: user with model.read but no model.manage gets phone field masked. */
    @Test
    void filterQueryResult_allows_and_masks_pii_for_non_admin() {
        when(userPermissionService.hasPermission(USER_ID, "model.customer.read")).thenReturn(true);
        when(userPermissionService.hasPermission(USER_ID, "model.customer.manage")).thenReturn(false);

        Map<String, Object> record = new HashMap<>();
        record.put("phone", "13812345678");
        record.put("email", "alice@example.com");

        DataFilterRequest request = DataFilterRequest.builder()
                .userId(USER_ID)
                .tenantId(TENANT_ID)
                .modelCode(MODEL)
                .data(List.of(record))
                .build();

        DataFilterResult result = filter.filterQueryResult(request);

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getFilteredData()).hasSize(1);
        Map<String, Object> filteredRow = result.getFilteredData().get(0);
        // PII must be masked, not raw.
        assertThat(filteredRow.get("phone")).isEqualTo("138****5678");
        assertThat(filteredRow.get("email").toString()).startsWith("al****@");
        assertThat(result.getStatistics().getOriginalRecordCount()).isEqualTo(1);
    }

    /** Boundary: manage permission => no masking applied (admin sees raw). */
    @Test
    void filterQueryResult_admin_with_manage_sees_raw_values() {
        when(userPermissionService.hasPermission(USER_ID, "model.customer.read")).thenReturn(true);
        when(userPermissionService.hasPermission(USER_ID, "model.customer.manage")).thenReturn(true);

        Map<String, Object> record = new HashMap<>();
        record.put("phone", "13812345678");

        DataFilterRequest request = DataFilterRequest.builder()
                .userId(USER_ID)
                .tenantId(TENANT_ID)
                .modelCode(MODEL)
                .data(List.of(record))
                .build();

        DataFilterResult result = filter.filterQueryResult(request);

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getFilteredData().get(0).get("phone")).isEqualTo("13812345678");
    }

    /** Fail-closed: null request bombs => success=false, no data leaked. */
    @Test
    void filterQueryResult_failsClosed_on_null_request() {
        DataFilterResult result = filter.filterQueryResult(null);
        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getFilteredData()).isNull();
    }

    /** Empty data list still returns success and empty filtered list (no NPE). */
    @Test
    void filterQueryResult_empty_data_returns_empty_filtered() {
        when(userPermissionService.hasPermission(USER_ID, "model.customer.read")).thenReturn(true);

        DataFilterRequest request = DataFilterRequest.builder()
                .userId(USER_ID)
                .tenantId(TENANT_ID)
                .modelCode(MODEL)
                .data(new ArrayList<>())
                .build();

        DataFilterResult result = filter.filterQueryResult(request);
        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getFilteredData()).isEmpty();
    }

    // ===================== batchFilterData =====================

    /** Boundary: missing required fields => fail-closed with errorMessage. */
    @Test
    void batchFilterData_failsClosed_on_missing_required() {
        Map<String, Object> req = new HashMap<>();
        req.put("modelCode", MODEL);
        // missing userId, tenantId
        SimpleResult result = filter.batchFilterData(req);
        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("Missing required fields");
    }

    /** Boundary: no records => success but empty items, no permission check. */
    @Test
    void batchFilterData_empty_records_returns_zero_count() {
        Map<String, Object> req = new HashMap<>();
        req.put("modelCode", MODEL);
        req.put("userId", USER_ID);
        req.put("tenantId", TENANT_ID);
        req.put("records", new ArrayList<>());

        SimpleResult result = filter.batchFilterData(req);
        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getCount()).isZero();
        verify(userPermissionService, never()).hasPermission(anyLong(), anyString());
    }

    /** Boundary: user without model.read => all records dropped (zero items returned). */
    @Test
    void batchFilterData_drops_all_when_no_model_read() {
        when(userPermissionService.hasPermission(USER_ID, "model.customer.read")).thenReturn(false);

        Map<String, Object> req = new HashMap<>();
        req.put("modelCode", MODEL);
        req.put("userId", USER_ID);
        req.put("tenantId", TENANT_ID);
        req.put("records", List.of(Map.of("name", "Alice"), Map.of("name", "Bob")));

        SimpleResult result = filter.batchFilterData(req);

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getCount()).isZero();
        assertThat(result.getProperties().get("removedCount")).isEqualTo(2);
    }

    /** Boundary: with permission, records flow through; userId can be String. */
    @Test
    void batchFilterData_handles_string_user_id_and_filters() {
        when(userPermissionService.hasPermission(USER_ID, "model.customer.read")).thenReturn(true);

        Map<String, Object> req = new HashMap<>();
        req.put("modelCode", MODEL);
        req.put("userId", "100");
        req.put("tenantId", "1");
        req.put("records", List.of(Map.of("name", "Alice")));

        SimpleResult result = filter.batchFilterData(req);
        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getCount()).isEqualTo(1);
    }

    /** Fail-closed: unparseable userId => extractLong returns null => missing field error. */
    @Test
    void batchFilterData_failsClosed_on_garbage_userId() {
        Map<String, Object> req = new HashMap<>();
        req.put("modelCode", MODEL);
        req.put("userId", "not-a-number");
        req.put("tenantId", TENANT_ID);
        req.put("records", List.of(Map.of("name", "x")));

        SimpleResult result = filter.batchFilterData(req);
        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("Missing required fields");
    }

    // ===================== filterRecord =====================

    /** Boundary: null record => fail-closed. */
    @Test
    void filterRecord_failsClosed_on_missing_record() {
        Map<String, Object> req = new HashMap<>();
        req.put("modelCode", MODEL);
        req.put("userId", USER_ID);
        // missing record
        SimpleResult result = filter.filterRecord(req);
        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("Missing required fields");
    }

    /** Boundary: no model permission => returns accessible=false, data=null. */
    @Test
    void filterRecord_denies_access_when_no_model_read() {
        when(userPermissionService.hasPermission(USER_ID, "model.customer.read")).thenReturn(false);
        Map<String, Object> req = new HashMap<>();
        req.put("modelCode", MODEL);
        req.put("userId", USER_ID);
        req.put("record", Map.of("name", "Alice"));

        SimpleResult result = filter.filterRecord(req);
        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isNull();
        assertThat(result.getProperties().get("accessible")).isEqualTo(false);
    }

    /** Boundary: model permission granted => record flows through with masking applied. */
    @Test
    void filterRecord_allows_with_model_read_and_masks_pii() {
        when(userPermissionService.hasPermission(USER_ID, "model.customer.read")).thenReturn(true);
        Map<String, Object> req = new HashMap<>();
        req.put("modelCode", MODEL);
        req.put("userId", USER_ID);
        req.put("record", Map.of("phone", "13800001234"));

        SimpleResult result = filter.filterRecord(req);
        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getProperties().get("accessible")).isEqualTo(true);
        Map<?, ?> data = (Map<?, ?>) result.getData();
        assertThat(data.get("phone")).isEqualTo("138****1234");
    }

    // ===================== applyDataMasking =====================

    /** Boundary: null value => masking not applied, success=true. */
    @Test
    void applyDataMasking_null_value_returns_no_op_success() {
        DataMaskingResult result = filter.applyDataMasking(
                DataMaskingRequest.builder().code("phone").value(null).maskingRule("phone_masking").build());
        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getMaskingApplied()).isFalse();
        assertThat(result.getMaskedValue()).isNull();
    }

    @Test
    void applyDataMasking_phone_rule_masks_middle_four_digits() {
        DataMaskingResult result = filter.applyDataMasking(
                DataMaskingRequest.builder().code("phone").value("13812345678").maskingRule("phone_masking").build());
        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getMaskedValue()).isEqualTo("138****5678");
        assertThat(result.getMaskingApplied()).isTrue();
    }

    @Test
    void applyDataMasking_email_rule_masks_local_part() {
        DataMaskingResult result = filter.applyDataMasking(
                DataMaskingRequest.builder().code("email").value("alice@example.com").maskingRule("email_masking").build());
        assertThat(result.getMaskedValue().toString()).startsWith("al****@");
    }

    @Test
    void applyDataMasking_idCard_rule_masks_middle_eight_digits() {
        DataMaskingResult result = filter.applyDataMasking(
                DataMaskingRequest.builder().code("idCard").value("110101199001011234").maskingRule("id_card_masking").build());
        assertThat(result.getMaskedValue()).isEqualTo("110101********1234");
    }

    @Test
    void applyDataMasking_bankCard_rule_keeps_only_last_four() {
        DataMaskingResult result = filter.applyDataMasking(
                DataMaskingRequest.builder().code("bankCard").value("6222020200001234567").maskingRule("bank_card_masking").build());
        assertThat(result.getMaskedValue().toString()).endsWith("4567");
        assertThat(result.getMaskedValue().toString()).contains("************");
    }

    @Test
    void applyDataMasking_name_rule_keeps_first_char_only() {
        DataMaskingResult result = filter.applyDataMasking(
                DataMaskingRequest.builder().code("name").value("张三丰").maskingRule("name_masking").build());
        assertThat(result.getMaskedValue()).isEqualTo("张**");
    }

    @Test
    void applyDataMasking_unknown_rule_returns_value_unchanged() {
        DataMaskingResult result = filter.applyDataMasking(
                DataMaskingRequest.builder().code("foo").value("hello").maskingRule("unknown_rule").build());
        assertThat(result.getMaskedValue()).isEqualTo("hello");
        assertThat(result.getMaskingApplied()).isFalse();
    }

    @Test
    void applyDataMasking_short_phone_left_unchanged() {
        DataMaskingResult result = filter.applyDataMasking(
                DataMaskingRequest.builder().code("phone").value("123").maskingRule("phone_masking").build());
        assertThat(result.getMaskedValue()).isEqualTo("123");
    }

    @Test
    void applyDataMasking_email_without_at_unchanged() {
        DataMaskingResult result = filter.applyDataMasking(
                DataMaskingRequest.builder().code("email").value("not-an-email").maskingRule("email_masking").build());
        assertThat(result.getMaskedValue()).isEqualTo("not-an-email");
    }

    @Test
    void applyDataMasking_short_idCard_unchanged() {
        DataMaskingResult result = filter.applyDataMasking(
                DataMaskingRequest.builder().code("idCard").value("123").maskingRule("id_card_masking").build());
        assertThat(result.getMaskedValue()).isEqualTo("123");
    }

    @Test
    void applyDataMasking_short_bankCard_unchanged() {
        DataMaskingResult result = filter.applyDataMasking(
                DataMaskingRequest.builder().code("bankCard").value("123").maskingRule("bank_card_masking").build());
        assertThat(result.getMaskedValue()).isEqualTo("123");
    }

    @Test
    void applyDataMasking_short_name_unchanged() {
        DataMaskingResult result = filter.applyDataMasking(
                DataMaskingRequest.builder().code("name").value("A").maskingRule("name_masking").build());
        assertThat(result.getMaskedValue()).isEqualTo("A");
    }

    // ===================== getFieldMaskingRule =====================

    @Test
    void getFieldMaskingRule_phone_field_returns_phone_masking() {
        Map<String, Object> req = new HashMap<>();
        req.put("code", "userPhone");
        req.put("userId", USER_ID);
        req.put("tenantId", TENANT_ID);
        req.put("fieldType", "string");

        SimpleResult result = filter.getFieldMaskingRule(req);
        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo("phone_masking");
        assertThat(result.getProperties().get("maskingRequired")).isEqualTo(true);
    }

    @Test
    void getFieldMaskingRule_email_field_returns_email_masking() {
        SimpleResult result = filter.getFieldMaskingRule(Map.of("code", "userEmail", "userId", USER_ID, "tenantId", TENANT_ID, "fieldType", "string"));
        assertThat(result.getData()).isEqualTo("email_masking");
    }

    @Test
    void getFieldMaskingRule_idcard_field_returns_id_card_masking() {
        SimpleResult result = filter.getFieldMaskingRule(Map.of("code", "myIdCard", "userId", USER_ID, "tenantId", TENANT_ID, "fieldType", "string"));
        assertThat(result.getData()).isEqualTo("id_card_masking");
    }

    @Test
    void getFieldMaskingRule_identity_keyword_returns_id_card_masking() {
        SimpleResult result = filter.getFieldMaskingRule(Map.of("code", "identityNo", "userId", USER_ID, "tenantId", TENANT_ID, "fieldType", "string"));
        assertThat(result.getData()).isEqualTo("id_card_masking");
    }

    @Test
    void getFieldMaskingRule_account_returns_bank_card_masking() {
        SimpleResult result = filter.getFieldMaskingRule(Map.of("code", "bankAccount", "userId", USER_ID, "tenantId", TENANT_ID, "fieldType", "string"));
        assertThat(result.getData()).isEqualTo("bank_card_masking");
    }

    @Test
    void getFieldMaskingRule_name_returns_name_masking() {
        SimpleResult result = filter.getFieldMaskingRule(Map.of("code", "fullName", "userId", USER_ID, "tenantId", TENANT_ID, "fieldType", "string"));
        assertThat(result.getData()).isEqualTo("name_masking");
    }

    /** Boundary: lowercase 'username' is exempted from name masking. */
    @Test
    void getFieldMaskingRule_username_is_exempt() {
        SimpleResult result = filter.getFieldMaskingRule(Map.of("code", "username", "userId", USER_ID, "tenantId", TENANT_ID, "fieldType", "string"));
        assertThat(result.getData()).isNull();
        assertThat(result.getProperties().get("maskingRequired")).isEqualTo(false);
    }

    @Test
    void getFieldMaskingRule_non_pii_field_returns_null_rule() {
        SimpleResult result = filter.getFieldMaskingRule(Map.of("code", "age", "userId", USER_ID, "tenantId", TENANT_ID, "fieldType", "int"));
        assertThat(result.getData()).isNull();
        assertThat(result.getProperties().get("ruleDescription")).isEqualTo("无需脱敏");
    }

    /** Fail-closed: missing 'code' triggers NPE inside try => caught and returned as failure. */
    @Test
    void getFieldMaskingRule_failsClosed_on_missing_code() {
        Map<String, Object> req = new HashMap<>();
        req.put("userId", USER_ID);
        SimpleResult result = filter.getFieldMaskingRule(req);
        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("获取脱敏规则失败");
    }

    // ===================== calculateDynamicMaskingStrategy =====================

    @Test
    void calculateDynamicMaskingStrategy_returns_role_and_time_rules() {
        Map<String, Object> req = new HashMap<>();
        req.put("userId", USER_ID);
        req.put("tenantId", TENANT_ID);
        req.put("context", Map.of("k", "v"));
        SimpleResult result = filter.calculateDynamicMaskingStrategy(req);
        assertThat(result.getSuccess()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, String> rules = (Map<String, String>) result.getData();
        assertThat(rules).containsKey("timeBasedMasking");
        assertThat(rules).containsKey("roleBasedMasking");
        // default user role => full
        assertThat(rules.get("roleBasedMasking")).isEqualTo("full");
    }

    @Test
    void calculateDynamicMaskingStrategy_handles_null_context() {
        Map<String, Object> req = new HashMap<>();
        req.put("userId", USER_ID);
        req.put("tenantId", TENANT_ID);
        SimpleResult result = filter.calculateDynamicMaskingStrategy(req);
        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getProperties().get("contextHash")).isEqualTo("0");
    }

    // ===================== logDataAccess =====================

    @Test
    void logDataAccess_executes_without_throwing() {
        // Boundary: log path must never propagate exceptions to caller.
        filter.logDataAccess(DataAccessLogRequest.builder()
                .userId(USER_ID)
                .tenantId(TENANT_ID)
                .modelCode(MODEL)
                .action("query")
                .recordCount(5)
                .accessTime(LocalDateTime.now())
                .clientIp("127.0.0.1")
                .userAgent("agent")
                .build());
        // No assertion target; the contract is "no throw".
    }

    @Test
    void logDataAccess_handles_null_request_silently() {
        // Catches NPE and logs — must not propagate.
        filter.logDataAccess(null);
    }

    // ===================== executeDataFilterRules =====================

    @Test
    void executeDataFilterRules_failsClosed_on_missing_required() {
        SimpleResult result = filter.executeDataFilterRules(new HashMap<>());
        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("Missing required fields");
    }

    @Test
    void executeDataFilterRules_empty_records_returns_empty() {
        Map<String, Object> req = new HashMap<>();
        req.put("modelCode", MODEL);
        req.put("userId", USER_ID);
        req.put("tenantId", TENANT_ID);
        req.put("records", new ArrayList<>());
        SimpleResult result = filter.executeDataFilterRules(req);
        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getCount()).isZero();
    }

    /** Boundary: without model.read all records dropped, reason exposed in properties. */
    @Test
    void executeDataFilterRules_denies_when_no_model_permission() {
        when(userPermissionService.hasPermission(USER_ID, "model.customer.read")).thenReturn(false);
        Map<String, Object> req = new HashMap<>();
        req.put("modelCode", MODEL);
        req.put("userId", USER_ID);
        req.put("tenantId", TENANT_ID);
        req.put("records", List.of(Map.of("name", "Alice")));

        SimpleResult result = filter.executeDataFilterRules(req);
        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getCount()).isZero();
        assertThat(result.getProperties().get("reason")).isEqualTo("No model read permission");
    }

    @Test
    void executeDataFilterRules_allows_and_masks_with_model_read() {
        when(userPermissionService.hasPermission(USER_ID, "model.customer.read")).thenReturn(true);
        Map<String, Object> req = new HashMap<>();
        req.put("modelCode", MODEL);
        req.put("userId", USER_ID);
        req.put("tenantId", TENANT_ID);
        Map<String, Object> rec = new HashMap<>();
        rec.put("phone", "13900001111");
        rec.put("age", 30);
        req.put("records", List.of(rec));

        SimpleResult result = filter.executeDataFilterRules(req);
        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getCount()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> filteredRow = (Map<String, Object>) result.getItems().get(0);
        assertThat(filteredRow.get("phone")).isEqualTo("139****1111");
        assertThat(filteredRow.get("age")).isEqualTo(30);
    }

    // ===================== Stub / passthrough APIs (coverage but with assertions) =====================

    @Test
    void warmupDataPermissionCache_returns_success() {
        SimpleResult result = filter.warmupDataPermissionCache(Collections.emptyMap());
        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getCount()).isZero();
    }

    @Test
    void refreshDataPermissionCache_returns_success() {
        SimpleResult result = filter.refreshDataPermissionCache(Collections.emptyMap());
        assertThat(result.getSuccess()).isTrue();
    }

    @Test
    void clearDataPermissionCache_returns_success() {
        SimpleResult result = filter.clearDataPermissionCache(Collections.emptyMap());
        assertThat(result.getSuccess()).isTrue();
    }

    @Test
    void validateDataAccessPermission_returns_valid() {
        SimpleResult result = filter.validateDataAccessPermission(Collections.emptyMap());
        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getProperties().get("valid")).isEqualTo(true);
    }

    @Test
    void validateDataModificationPermission_returns_valid() {
        SimpleResult result = filter.validateDataModificationPermission(Collections.emptyMap());
        assertThat(result.getProperties().get("valid")).isEqualTo(true);
    }

    @Test
    void validateDataExportPermission_returns_valid() {
        SimpleResult result = filter.validateDataExportPermission(Collections.emptyMap());
        assertThat(result.getProperties().get("valid")).isEqualTo(true);
    }

    @Test
    void analyzeDataAccessPattern_returns_empty_data() {
        SimpleResult result = filter.analyzeDataAccessPattern(Collections.emptyMap());
        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isInstanceOf(Map.class);
    }

    @Test
    void detectDataAccessAnomalies_returns_empty_items() {
        SimpleResult result = filter.detectDataAccessAnomalies(Collections.emptyMap());
        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getItems()).isEmpty();
    }

    @Test
    void validateDataFilterRules_returns_valid() {
        SimpleResult result = filter.validateDataFilterRules(Collections.emptyMap());
        assertThat(result.getProperties().get("valid")).isEqualTo(true);
    }

    @Test
    void optimizeDataFilterRules_returns_empty_items() {
        SimpleResult result = filter.optimizeDataFilterRules(Collections.emptyMap());
        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getItems()).isEmpty();
    }

    // ===================== Field-level boundary =====================

    /** Boundary: invisible field is dropped entirely (not just masked). */
    @Test
    void filterQueryResult_drops_invisible_field_entirely() {
        // user has model.read but no field-level read AND no model.read fallback won't fire for non-PII.
        // Setup: model.read=false for the fallback path; field.*.read=false. But filterQueryResult itself
        // requires model.read first. So we test via batchFilterData where model.read=true gates entry,
        // then field visibility is decided by field.* + fallback.
        // Here field-level returns false but model.read fallback returns true => still visible.
        // To exercise the "invisible" branch we make ALL permission lookups false except the entry one.
        when(userPermissionService.hasPermission(eq(USER_ID), eq("model.customer.read")))
                .thenReturn(true)   // entry check
                .thenReturn(false); // fallback in buildFieldPermissions
        when(userPermissionService.hasPermission(USER_ID, "model.customer.manage")).thenReturn(false);
        when(userPermissionService.hasPermission(eq(USER_ID), eq("field.customer_secretField.read"))).thenReturn(false);

        DataFilterRequest request = DataFilterRequest.builder()
                .userId(USER_ID)
                .tenantId(TENANT_ID)
                .modelCode(MODEL)
                .data(List.of(Map.of("secretField", "topsecret")))
                .build();

        DataFilterResult result = filter.filterQueryResult(request);
        assertThat(result.getSuccess()).isTrue();
        // invisible field dropped => empty record skipped => filteredData empty
        assertThat(result.getFilteredData()).isEmpty();
    }
}
