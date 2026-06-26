package com.auraboot.framework.organization;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("org employee optional user-link migration")
class OrgEmployeeOptionalUserLinkMigrationIT extends BaseIntegrationTest {

    private static final String MIGRATION_RESOURCE =
            "db/migration/core/V20260626010000__org_employee_optional_user_link.sql";

    @Autowired
    private JdbcTemplate jdbc;

    private Long tenantId;

    @BeforeEach
    void setup() {
        applyTestMetaContext();
        tenantId = testTenant.getId();
        ensureOrgEmployeeUserBindingExists();
        jdbc.update("""
                UPDATE ab_meta_model_field_binding binding
                SET required = true
                FROM ab_meta_model model,
                     ab_meta_field field
                WHERE binding.model_id = model.id
                  AND binding.field_id = field.id
                  AND binding.tenant_id = ?
                  AND model.code = 'org_employee'
                  AND field.code = 'org_emp_user_id'
                """, tenantId);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("""
                UPDATE ab_meta_model_field_binding binding
                SET required = false
                FROM ab_meta_model model,
                     ab_meta_field field
                WHERE binding.model_id = model.id
                  AND binding.field_id = field.id
                  AND binding.tenant_id = ?
                  AND model.code = 'org_employee'
                  AND field.code = 'org_emp_user_id'
                """, tenantId);
        MetaContext.clear();
    }

    @Test
    @DisplayName("drops runtime required flag and keeps physical employee user link nullable")
    void migrationDropsEmployeeUserLinkRequiredness() {
        assertThat(bindingRequired()).isTrue();

        Throwable error = catchThrowable(this::runMigration);

        assertThat(error).isNull();
        assertThat(bindingRequired()).isFalse();
        assertThat(employeeUserIdNullable()).isTrue();
    }

    private Boolean bindingRequired() {
        return jdbc.queryForObject("""
                SELECT binding.required
                FROM ab_meta_model_field_binding binding
                JOIN ab_meta_model model ON binding.model_id = model.id
                JOIN ab_meta_field field ON binding.field_id = field.id
                WHERE binding.tenant_id = ?
                  AND model.code = 'org_employee'
                  AND field.code = 'org_emp_user_id'
                LIMIT 1
                """, Boolean.class, tenantId);
    }

    private boolean employeeUserIdNullable() {
        String nullable = jdbc.queryForObject("""
                SELECT is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'mt_org_employee'
                  AND column_name = 'org_emp_user_id'
                """, String.class);
        return "YES".equals(nullable);
    }

    private void ensureOrgEmployeeUserBindingExists() {
        Long modelId = findOrCreateModel();
        Long fieldId = findOrCreateField();
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM ab_meta_model_field_binding
                WHERE tenant_id = ?
                  AND model_id = ?
                  AND field_id = ?
                  AND deleted_flag = false
                """, Long.class, tenantId, modelId, fieldId);
        if (count == null || count == 0L) {
            jdbc.update("""
                    INSERT INTO ab_meta_model_field_binding (
                        id, pid, tenant_id, model_id, field_id, field_order,
                        required, visible, editable, searchable, deleted_flag,
                        created_at, updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, 0, false, true, true, false, false, NOW(), NOW())
                    """,
                    nextId(), UniqueIdGenerator.generate(), tenantId, modelId, fieldId);
        }
    }

    private Long findOrCreateModel() {
        Long id = queryOptionalId(
                "SELECT id FROM ab_meta_model WHERE tenant_id = ? AND code = 'org_employee' LIMIT 1");
        if (id != null) {
            return id;
        }
        long newId = nextId();
        jdbc.update("""
                INSERT INTO ab_meta_model (
                    id, pid, tenant_id, code, table_name, extension, capabilities,
                    version, is_current, status, deleted_flag, created_at, updated_at
                )
                VALUES (?, ?, ?, 'org_employee', 'mt_org_employee', '{}'::jsonb, '{}'::jsonb,
                        1, true, 'published', false, NOW(), NOW())
                """, newId, UniqueIdGenerator.generate(), tenantId);
        return newId;
    }

    private Long findOrCreateField() {
        Long id = queryOptionalId(
                "SELECT id FROM ab_meta_field WHERE tenant_id = ? AND code = 'org_emp_user_id' LIMIT 1");
        if (id != null) {
            return id;
        }
        long newId = nextId();
        jdbc.update("""
                INSERT INTO ab_meta_field (
                    id, pid, tenant_id, code, data_type, extension, index_hint, ui_schema,
                    query_schema, version, is_current, status, deleted_flag, created_at, updated_at
                )
                VALUES (?, ?, ?, 'org_emp_user_id', 'reference', '{}'::jsonb, '{}'::jsonb, '{}'::jsonb,
                        '{}'::jsonb, 1, true, 'published', false, NOW(), NOW())
                """, newId, UniqueIdGenerator.generate(), tenantId);
        return newId;
    }

    private Long queryOptionalId(String sql) {
        return jdbc.query(sql, rs -> rs.next() ? rs.getLong(1) : null, tenantId);
    }

    private long nextId() {
        return (System.nanoTime() & 0x0000_7fff_ffff_ffffL) | 0x3000_0000_0000_0000L;
    }

    private void runMigration() throws IOException {
        ClassPathResource resource = new ClassPathResource(MIGRATION_RESOURCE);
        try (InputStream input = resource.getInputStream()) {
            jdbc.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
