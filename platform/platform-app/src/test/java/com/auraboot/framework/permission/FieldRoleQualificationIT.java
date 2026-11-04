package com.auraboot.framework.permission;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.integration.TestIdGenerator;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.permission.service.impl.FieldPermissionServiceImpl;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real PostgreSQL actor/role joins; field metadata is fixed in memory to isolate qualification. */
@SpringBootTest(classes = com.auraboot.framework.application.TestApplication.class,
        properties = "spring.data.redis.host=")
class FieldRoleQualificationIT extends BaseIntegrationTest {
    @Autowired private RoleMapper roles;
    @Autowired private JdbcTemplate jdbc;
    private Long user, member, role, assignment;
    private FieldPermissionServiceImpl fields;
    private final AtomicReference<List<Long>> contributed = new AtomicReference<>(List.of());

    @BeforeEach
    void actor() {
        user = TestIdGenerator.uniqueUserId();
        member = TestIdGenerator.uniqueUserId();
        role = TestIdGenerator.uniqueUserId();
        assignment = TestIdGenerator.uniqueUserId();
        String pid = UniqueIdGenerator.generate();
        String code = "field_" + pid;
        jdbc.update("INSERT INTO ab_user (id,pid,is_enabled,deleted_flag) VALUES (?,?,TRUE,FALSE)", user, pid);
        jdbc.update("INSERT INTO ab_tenant_member (id,pid,tenant_id,user_id,status,deleted_flag) VALUES (?,?,?,?,'active',FALSE)",
                member, UniqueIdGenerator.generate(), testTenant.getId(), user);
        jdbc.update("INSERT INTO ab_role (id,pid,tenant_id,code,name,status,deleted_flag) VALUES (?,?,?,?,'Field qualification','active',FALSE)",
                role, UniqueIdGenerator.generate(), testTenant.getId(), code);
        jdbc.update("INSERT INTO ab_user_role (id,pid,tenant_id,member_id,role_id,status,deleted_flag) VALUES (?,?,?,?,?,'active',FALSE)",
                assignment, UniqueIdGenerator.generate(), testTenant.getId(), member, role);
        MetaContext.setContext(testTenant.getId(), user, pid, "field-qualification");
        MetaContext.setMemberId(member);
        MetaModelService metadata = mock(MetaModelService.class);
        FieldDefinition restricted = new FieldDefinition();
        restricted.setCode("salary");
        restricted.setExtraProps(Map.of("fieldPermission", Map.of("view", List.of(code), "edit", List.of(code))));
        when(metadata.getModelFields("field_fixture")).thenReturn(List.of(restricted));
        fields = new FieldPermissionServiceImpl(metadata, roles,
                List.of((memberId, tenantId) -> contributed.get()));
    }

    private void allowed() {
        var result = fields.getFieldPermissions(member, "field_fixture");
        assertThat(result.viewableFields()).containsExactly("salary");
        assertThat(result.editableFields()).containsExactly("salary");
        assertThat(result.hiddenFields()).isEmpty();
    }

    private void hidden() {
        var result = fields.getFieldPermissions(member, "field_fixture");
        assertThat(result.hiddenFields()).containsExactly("salary");
        assertThat(result.viewableFields()).isEmpty();
        assertThat(result.editableFields()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"role-disabled", "role-deleted", "role-tenant", "assignment-disabled",
            "assignment-deleted", "assignment-tenant", "future", "expired", "member-disabled",
            "member-deleted", "member-tenant", "member-user", "user-disabled", "user-deleted"})
    void invalidatedActorOrAssignmentCannotRetainFields(String state) {
        allowed();
        switch (state) {
            case "role-disabled" -> jdbc.update("UPDATE ab_role SET status='disabled' WHERE id=?", role);
            case "role-deleted" -> jdbc.update("UPDATE ab_role SET deleted_flag=TRUE WHERE id=?", role);
            case "role-tenant" -> jdbc.update("UPDATE ab_role SET tenant_id=1 WHERE id=?", role);
            case "assignment-disabled" -> jdbc.update("UPDATE ab_user_role SET status='disabled' WHERE id=?", assignment);
            case "assignment-deleted" -> jdbc.update("UPDATE ab_user_role SET deleted_flag=TRUE WHERE id=?", assignment);
            case "assignment-tenant" -> jdbc.update("UPDATE ab_user_role SET tenant_id=1 WHERE id=?", assignment);
            case "future" -> jdbc.update("UPDATE ab_user_role SET effective_date=CURRENT_DATE+1 WHERE id=?", assignment);
            case "expired" -> jdbc.update("UPDATE ab_user_role SET expiry_date=CURRENT_DATE-1 WHERE id=?", assignment);
            case "member-disabled" -> jdbc.update("UPDATE ab_tenant_member SET status='disabled' WHERE id=?", member);
            case "member-deleted" -> jdbc.update("UPDATE ab_tenant_member SET deleted_flag=TRUE WHERE id=?", member);
            case "member-tenant" -> jdbc.update("UPDATE ab_tenant_member SET tenant_id=1 WHERE id=?", member);
            case "member-user" -> {
                Long other = TestIdGenerator.uniqueUserId();
                jdbc.update("INSERT INTO ab_user (id,pid,is_enabled,deleted_flag) VALUES (?,?,TRUE,FALSE)",
                        other, UniqueIdGenerator.generate());
                jdbc.update("UPDATE ab_tenant_member SET user_id=? WHERE id=?", other, member);
            }
            case "user-disabled" -> jdbc.update("UPDATE ab_user SET is_enabled=FALSE WHERE id=?", user);
            case "user-deleted" -> jdbc.update("UPDATE ab_user SET deleted_flag=TRUE WHERE id=?", user);
            default -> throw new IllegalArgumentException(state);
        }
        hidden();
    }

    @Test
    void currentDateEndpointsRemainInclusiveAndRevocationRestorationIsLive() {
        jdbc.update("UPDATE ab_user_role SET effective_date=CURRENT_DATE,expiry_date=CURRENT_DATE WHERE id=?", assignment);
        allowed();
        jdbc.update("UPDATE ab_user_role SET status='disabled' WHERE id=?", assignment);
        hidden();
        jdbc.update("UPDATE ab_user_role SET status='active' WHERE id=?", assignment);
        allowed();
    }

    @Test
    void governedContributionWorksWithoutDirectAssignmentButStillRequiresValidActorAndRole() {
        jdbc.update("DELETE FROM ab_user_role WHERE id=?", assignment);
        hidden();
        contributed.set(List.of(role));
        allowed();
        jdbc.update("UPDATE ab_role SET tenant_id=1 WHERE id=?", role);
        hidden();
        jdbc.update("UPDATE ab_role SET tenant_id=? WHERE id=?", testTenant.getId(), role);
        allowed();
        jdbc.update("UPDATE ab_user SET is_enabled=FALSE WHERE id=?", user);
        hidden();
    }

    @Test
    void withdrawingAContributionDoesNotReuseItsOldRole() {
        jdbc.update("DELETE FROM ab_user_role WHERE id=?", assignment);
        contributed.set(List.of(role));
        allowed();
        contributed.set(List.of());
        hidden();
    }

    @Test
    void assignedGlobalTemplateStillUsesCurrentTenantMembership() {
        jdbc.update("UPDATE ab_role SET tenant_id=NULL WHERE id=?", role);
        allowed();
        jdbc.update("UPDATE ab_user_role SET tenant_id=1 WHERE id=?", assignment);
        hidden();
    }
}
