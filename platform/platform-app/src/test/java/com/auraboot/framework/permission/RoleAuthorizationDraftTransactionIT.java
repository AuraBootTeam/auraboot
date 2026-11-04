package com.auraboot.framework.permission;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.permission.capability.CapabilityRegistryService;
import com.auraboot.framework.permission.dto.DataScopeUpdateRequest;
import com.auraboot.framework.permission.dto.RoleAuthorizationDraftRequest;
import com.auraboot.framework.permission.dto.RoleAuthorizationDraftSnapshot;
import com.auraboot.framework.permission.service.DataScopeService;
import com.auraboot.framework.permission.service.RoleAuthorizationDraftService;
import com.auraboot.framework.plugin.dto.imports.CapabilityDefinitionDTO;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/** Real PostgreSQL transactions; faults occur after actual writes, never in a mocked database. */
@SpringBootTest(classes = TestApplication.class, properties = "spring.data.redis.host=")
@Transactional(propagation = Propagation.NEVER)
class RoleAuthorizationDraftTransactionIT extends BaseIntegrationTest {
    @Autowired private RoleAuthorizationDraftService drafts;
    @Autowired private CapabilityRegistryService catalog;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private DataScopeService scopes;
    private long roleId;
    private String rolePid;
    private String resource;
    private String readCode;
    private String updateCode;
    private String readCapability;
    private String editCapability;

    @BeforeEach void fixture() {
        roleId = IdWorker.getId();
        rolePid = UniqueIdGenerator.generate();
        resource = "draft_tx_" + rolePid.toLowerCase();
        readCode = "model." + resource + ".read";
        updateCode = "model." + resource + ".update";
        readCapability = resource + ".cap.read";
        editCapability = resource + ".cap.edit";
        jdbc.update("""
                INSERT INTO ab_role (id,pid,tenant_id,code,name,type,status,deleted_flag,created_at,updated_at)
                VALUES (?,?,?,?,?,'custom','active',false,now(),now())
                """, roleId, rolePid, getTestTenant().getId(), resource, resource);
        for (String action : List.of("read", "update")) {
            jdbc.update("""
                    INSERT INTO ab_permission (pid,tenant_id,code,name,resource_type,resource_code,action,
                                               source,status,deleted_flag,created_at,updated_at)
                    VALUES (?,?,?,?,'model',?,?,'test','active',false,now(),now())
                    """, UniqueIdGenerator.generate(), getTestTenant().getId(), "model." + resource + "." + action,
                    action, resource, action);
        }
        catalog.saveDefinition(CapabilityDefinitionDTO.builder().code(readCapability).group("Draft transaction")
                .includes(List.of(readCode)).build());
        catalog.saveDefinition(CapabilityDefinitionDTO.builder().code(editCapability).group("Draft transaction")
                .includes(List.of(readCode, updateCode)).build());
    }

    @AfterEach void cleanup() {
        reset(scopes);
        if (rolePid != null) {
            jdbc.update("DELETE FROM ab_data_permission_role_binding WHERE tenant_id=? AND role_pid=?", getTestTenant().getId(), rolePid);
            jdbc.update("DELETE FROM ab_role_data_scope WHERE tenant_id=? AND role_id=?", getTestTenant().getId(), roleId);
            jdbc.update("DELETE FROM ab_role_permission WHERE tenant_id=? AND role_id=?", getTestTenant().getId(), roleId);
            jdbc.update("DELETE FROM ab_role WHERE tenant_id=? AND id=?", getTestTenant().getId(), roleId);
            jdbc.update("DELETE FROM ab_permission_capability WHERE tenant_id=? AND code LIKE ?", getTestTenant().getId(), resource + ".%");
            jdbc.update("DELETE FROM ab_permission WHERE tenant_id=? AND resource_code=?", getTestTenant().getId(), resource);
            jdbc.update("DELETE FROM ab_data_permission_policy WHERE tenant_id=? AND model_code=?", getTestTenant().getId(), resource);
        }
    }

    @Test void savesDutiesDefaultAndPerActionOverrideAsOneState() {
        var baseline = drafts.snapshot(rolePid);
        var saved = drafts.apply(rolePid, editRequest(baseline));
        assertThat(persistedCodes()).containsExactly(readCode, updateCode);
        assertThat(defaultScope()).isEqualTo("self");
        assertThat(persistedScopes()).containsExactly(
                Map.of("action_code", "read", "scope_type", "self"),
                Map.of("action_code", "update", "scope_type", "none"));
        assertThat(saved.revision()).isNotEqualTo(baseline.revision());
        assertThat(drafts.snapshot(rolePid).revision()).isEqualTo(saved.revision());
        assertThatThrownBy(() -> drafts.apply(rolePid, editRequest(baseline)))
                .isInstanceOfSatisfying(RootUnCheckedException.class,
                        error -> assertThat(error.getResponseCode()).isEqualTo(ResponseCode.CasVersionConflict));
    }

    @Test void failureAfterRealGrantDefaultAndScopeWritesRollsBackEverything() {
        var baseline = drafts.snapshot(rolePid);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            assertThat(persistedCodes()).containsExactly(readCode, updateCode);
            assertThat(defaultScope()).isEqualTo("self");
            assertThat(persistedScopes()).anyMatch(row -> "none".equals(row.get("scope_type")));
            throw new IllegalStateException("Fault after actual authorization scope write");
        }).when(scopes).setScope(eq(getTestTenant().getId()), eq(roleId), eq(resource), eq("update"), eq("none"), eq("MAX"));
        assertThatThrownBy(() -> drafts.apply(rolePid, editRequest(baseline)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("after actual");
        assertThat(persistedCodes()).isEmpty();
        assertThat(defaultScope()).isNull();
        assertThat(persistedScopes()).isEmpty();
        assertThat(drafts.snapshot(rolePid).revision()).isEqualTo(baseline.revision());
    }

    @Test void concurrentDraftsHaveOneWinnerAndOneStaleRevision() throws Exception {
        var baseline = drafts.snapshot(rolePid);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = List.of(readCapability, editCapability).stream().map(capability -> pool.submit(() -> {
                MetaContext.setContext(getTestTenant().getId(), getTestUser().getId(), getTestUser().getPid(), "draft-test");
                MetaContext.setMemberId(getTestTenantMember().getId());
                ready.countDown();
                try {
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent draft did not start");
                    drafts.apply(rolePid, new RoleAuthorizationDraftRequest(baseline.revision(), Set.of(capability),
                            Set.of(), false, null, false, List.of()));
                    return "saved";
                } catch (RootUnCheckedException conflict) {
                    // CATCH: expected stale-version outcome; other failures still fail the future.
                    if (conflict.getResponseCode() != ResponseCode.CasVersionConflict) throw conflict;
                    return "conflict";
                } finally { MetaContext.clear(); }
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(results.get(0).get(30, TimeUnit.SECONDS), results.get(1).get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("saved", "conflict");
        }
        assertThat(persistedCodes()).contains(readCode).hasSizeBetween(1, 2);
    }

    @Test void changingBoundFieldPolicyInvalidatesAnEarlierDraft() {
        String policyPid = UniqueIdGenerator.generate();
        jdbc.update("""
                INSERT INTO ab_data_permission_policy (tenant_id,pid,name,model_code,policy_type,field_code,
                                                      mask_type,priority,enabled,created_at,updated_at)
                VALUES (?,?,?,?,'column','secret','hide',10,true,now(),now())
                """, getTestTenant().getId(), policyPid, resource, resource);
        jdbc.update("INSERT INTO ab_data_permission_role_binding (tenant_id,policy_pid,role_pid) VALUES (?,?,?)",
                getTestTenant().getId(), policyPid, rolePid);
        var baseline = drafts.snapshot(rolePid);
        jdbc.update("UPDATE ab_data_permission_policy SET mask_type='hash',updated_at=now() WHERE tenant_id=? AND pid=?",
                getTestTenant().getId(), policyPid);
        assertThatThrownBy(() -> drafts.apply(rolePid, editRequest(baseline)))
                .isInstanceOfSatisfying(RootUnCheckedException.class,
                        error -> assertThat(error.getResponseCode()).isEqualTo(ResponseCode.CasVersionConflict));
        assertThat(persistedCodes()).isEmpty();
    }

    private RoleAuthorizationDraftRequest editRequest(RoleAuthorizationDraftSnapshot baseline) {
        return new RoleAuthorizationDraftRequest(baseline.revision(), Set.of(editCapability), Set.of(), true,
                "self", true, List.of(new DataScopeUpdateRequest(resource, "update", "none", "MAX")));
    }

    private List<String> persistedCodes() {
        return jdbc.queryForList("""
                SELECT p.code FROM ab_role_permission rp JOIN ab_permission p ON p.id=rp.permission_id
                WHERE rp.tenant_id=? AND rp.role_id=? AND rp.deleted_flag=false ORDER BY p.code
                """, String.class, getTestTenant().getId(), roleId);
    }

    private String defaultScope() {
        return jdbc.queryForObject("SELECT default_data_scope_type FROM ab_role WHERE tenant_id=? AND id=?",
                String.class, getTestTenant().getId(), roleId);
    }

    private List<Map<String, Object>> persistedScopes() {
        return jdbc.queryForList("SELECT action_code,scope_type FROM ab_role_data_scope WHERE tenant_id=? AND role_id=? ORDER BY action_code",
                getTestTenant().getId(), roleId);
    }
}
