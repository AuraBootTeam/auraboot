package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.entity.FieldMaskConfig;
import com.auraboot.framework.meta.service.FieldMaskService;
import com.auraboot.framework.permission.service.UserPermissionService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * Real-stack IT for capability-driven field unmasking ({@code exempt_permission_codes}). A user
 * holding the exempt permission sees the unmasked value across list/detail/export; everyone else
 * sees the masked value. Proves the migration column round-trips through {@code saveConfig} against
 * the real DB and that the read-path exempt-by-permission logic resolves through the live
 * {@link FieldMaskService}. {@link UserPermissionService} is mocked to control the caller's
 * permission set (permission resolution itself is covered by its own tests).
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Field mask exempt-by-permission IT — capability-driven unmask")
class FieldMaskExemptByPermissionIT {

    private static final long TENANT_ID = 990_300_021L;
    private static final String UNMASK_PERM = "crm.account.contact_unmask";
    private final AtomicLong seq = new AtomicLong();

    @Autowired
    private FieldMaskService fieldMaskService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockBean
    private UserPermissionService userPermissionService;

    private String model;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, 990_000_021L, "fm-perm-pid", "fm-perm-user");
        model = "fmperm_" + seq.incrementAndGet() + "_" + Math.abs(System.nanoTime() % 100000);

        FieldMaskConfig cfg = new FieldMaskConfig();
        cfg.setModelCode(model);
        cfg.setFieldCode("phone");
        cfg.setMaskType("phone");
        cfg.setApplyToList(true);
        cfg.setApplyToDetail(true);
        cfg.setApplyToExport(true);
        cfg.setEnabled(true);
        cfg.setExemptPermissionCodes(UNMASK_PERM);
        fieldMaskService.saveConfig(cfg); // real DB write — exercises the new exempt_permission_codes column
    }

    @AfterAll
    void cleanup() {
        try {
            jdbcTemplate.update("DELETE FROM ab_field_mask_config WHERE tenant_id = ?", TENANT_ID);
        } finally {
            MetaContext.clear();
        }
    }

    private Map<String, Object> phoneRecord() {
        Map<String, Object> r = new HashMap<>();
        r.put("phone", "13812345678");
        return r;
    }

    @Test
    @DisplayName("Holder of crm.account.contact_unmask sees the unmasked phone (list + detail)")
    void holderSeesUnmasked() {
        when(userPermissionService.getUserPermissionCodes(42L)).thenReturn(Set.of(UNMASK_PERM));

        assertEquals("13812345678",
                fieldMaskService.applyMaskingForList(model, List.of(phoneRecord()), 42L).get(0).get("phone"));
        assertEquals("13812345678",
                fieldMaskService.applyMaskingForDetail(model, phoneRecord(), 42L).get("phone"));
    }

    @Test
    @DisplayName("Non-holder sees the masked phone across list + detail + export")
    void nonHolderSeesMasked() {
        when(userPermissionService.getUserPermissionCodes(7L)).thenReturn(Set.of());

        assertEquals("138****5678",
                fieldMaskService.applyMaskingForList(model, List.of(phoneRecord()), 7L).get(0).get("phone"));
        assertEquals("138****5678",
                fieldMaskService.applyMaskingForDetail(model, phoneRecord(), 7L).get("phone"));
        assertEquals("138****5678",
                fieldMaskService.applyMaskingForExport(model, List.of(phoneRecord()), 7L).get(0).get("phone"));
    }
}
