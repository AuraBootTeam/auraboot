package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.entity.FieldMaskConfig;
import com.auraboot.framework.meta.mapper.FieldMaskConfigMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FieldMaskServiceImplTest {

    private final FieldMaskServiceImpl service = new FieldMaskServiceImpl(
            null, null, null, new FieldMaskConfigCacheService());

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    @Test
    void customMaskTreatsPatternAsLiteralText() {
        String masked = service.maskValue("a+b aab", "custom", "a+", "*");

        assertThat(masked).isEqualTo("**b aab");
    }

    @Test
    void hashMaskProducesDeterministicHexDigest() {
        String masked = service.maskValue("customer@example.com", "hash", null, "*");
        String maskedAgain = service.maskValue("customer@example.com", "hash", null, "*");

        assertThat(masked).hasSize(16);
        assertThat(masked).matches("[0-9a-f]{16}");
        assertThat(masked).isEqualTo(maskedAgain);
        assertThat(masked).isNotEqualTo("customer@example.com");
    }

    @Test
    void phoneMaskKeepsHeadAndTail() {
        assertThat(service.maskValue("13812345678", "phone", null, "*")).isEqualTo("138****5678");
        assertThat(service.maskValue("13812345678", "phone", null, "#")).isEqualTo("138####5678");
        assertThat(service.maskValue("123", "phone", null, "*")).isEqualTo("123"); // too short
    }

    @Test
    void emailMaskKeepsTwoCharsAndDomain() {
        assertThat(service.maskValue("customer@example.com", "email", null, "*")).isEqualTo("cu***@example.com");
        assertThat(service.maskValue("no-at-sign", "email", null, "*")).isEqualTo("no-at-sign");
    }

    @Test
    void idCardMaskKeepsHeadTail() {
        assertThat(service.maskValue("110101199003071234", "id_card", null, "*")).isEqualTo("1101**********1234");
        assertThat(service.maskValue("1234567", "id_card", null, "*")).isEqualTo("1234567"); // < 8
    }

    @Test
    void bankCardMaskKeepsLastFour() {
        assertThat(service.maskValue("6222021234567890", "bank_card", null, "*")).isEqualTo("************7890");
        assertThat(service.maskValue("abc", "bank_card", null, "*")).isEqualTo("abc"); // < 4
    }

    @Test
    void nameMaskKeepsFirstChar() {
        assertThat(service.maskValue("John", "name", null, "*")).isEqualTo("J***");
        assertThat(service.maskValue("X", "name", null, "*")).isEqualTo("X"); // < 2
    }

    @Test
    void fullMaskRepeatsReplacementCharCappedAtTen() {
        assertThat(service.maskValue("short", "full", null, "*")).isEqualTo("*****");
        assertThat(service.maskValue("a-very-long-secret-value", "full", null, "*")).isEqualTo("**********");
    }

    @Test
    void partialMaskUsesDefaultsAndExplicitPattern() {
        assertThat(service.maskValue("1234567890", "partial", null, "*")).isEqualTo("123***7890");
        assertThat(service.maskValue("1234567890", "partial", "2,2", "*")).isEqualTo("12******90");
    }

    @Test
    void unknownTypeAndBlankValuePassThrough() {
        assertThat(service.maskValue("keepme", "no_such_type", null, "*")).isEqualTo("keepme");
        assertThat(service.maskValue(null, "phone", null, "*")).isNull();
        assertThat(service.maskValue("", "phone", null, "*")).isEqualTo("");
    }

    // ==================== exempt-by-permission (capability-driven unmask) ====================

    private FieldMaskServiceImpl serviceWith(FieldMaskConfigMapper maskConfigMapper,
                                             UserPermissionService userPermissionService) {
        // tenant context required by getEnabledConfigs; memberId left null so the user has no
        // roles (isolates the permission-exemption path from the role-exemption path).
        MetaContext.setContext(1L, 1L, "test-user", "test-user");
        return new FieldMaskServiceImpl(
                maskConfigMapper, null, userPermissionService, new FieldMaskConfigCacheService());
    }

    private FieldMaskConfig phoneMaskExemptByPermission() {
        FieldMaskConfig config = new FieldMaskConfig();
        config.setModelCode("crm_account_common");
        config.setFieldCode("phone");
        config.setMaskType("phone");
        config.setApplyToList(true);
        config.setEnabled(true);
        config.setExemptPermissionCodes("crm.account.contact_unmask");
        return config;
    }

    @Test
    void userHoldingExemptPermissionSeesUnmaskedPhone() {
        FieldMaskConfigMapper maskConfigMapper = mock(FieldMaskConfigMapper.class);
        UserPermissionService userPermissionService = mock(UserPermissionService.class);
        when(maskConfigMapper.findByModelCode(any(), eq("crm_account_common")))
                .thenReturn(List.of(phoneMaskExemptByPermission()));
        when(userPermissionService.getUserPermissionCodes(42L))
                .thenReturn(Set.of("crm.account.contact_unmask"));
        FieldMaskServiceImpl svc = serviceWith(maskConfigMapper, userPermissionService);

        Map<String, Object> record = new HashMap<>();
        record.put("phone", "13812345678");
        List<Map<String, Object>> result =
                svc.applyMaskingForList("crm_account_common", List.of(record), 42L);

        assertThat(result.get(0).get("phone")).isEqualTo("13812345678");
    }

    @Test
    void userWithoutExemptPermissionSeesMaskedPhone() {
        FieldMaskConfigMapper maskConfigMapper = mock(FieldMaskConfigMapper.class);
        UserPermissionService userPermissionService = mock(UserPermissionService.class);
        when(maskConfigMapper.findByModelCode(any(), eq("crm_account_common")))
                .thenReturn(List.of(phoneMaskExemptByPermission()));
        when(userPermissionService.getUserPermissionCodes(7L))
                .thenReturn(Set.of());
        FieldMaskServiceImpl svc = serviceWith(maskConfigMapper, userPermissionService);

        Map<String, Object> record = new HashMap<>();
        record.put("phone", "13812345678");
        List<Map<String, Object>> result =
                svc.applyMaskingForList("crm_account_common", List.of(record), 7L);

        assertThat(result.get(0).get("phone")).isEqualTo("138****5678");
    }
}
