package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.module.meta.event.CommandCompletedEvent;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Real-stack coverage IT for {@link ActivityEventListener} additional branches: a non-trackable
 * model category short-circuits (no activity), and a trackable model records a state_transition
 * activity carrying payload metadata. Complements the happy/guard IT. @Async unwrapped via AopTestUtils.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("ActivityEventListener Branches IT — non-trackable + state_transition")
class ActivityEventListenerBranchesIT {

    @Autowired
    private ActivityEventListener listener;
    @Autowired
    private MetaModelMapper metaModelMapper;
    @Autowired
    private UserService userService;
    @Autowired
    private TenantService tenantService;
    @Autowired
    private TenantMemberService tenantMemberService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User testUser;
    private Tenant testTenant;
    private ActivityEventListener target;
    private Model trackable;
    private Model nonTrackable;
    private boolean inited = false;

    @BeforeEach
    void setUp() {
        setupTenantContext();
        target = AopTestUtils.getTargetObject(listener);
        if (!inited) {
            purge();
            trackable = newModel("aelb_doc_" + Math.abs(System.nanoTime() % 1_000_000), "document");
            nonTrackable = newModel("aelb_ent_" + Math.abs(System.nanoTime() % 1_000_000), "entity");
            inited = true;
        }
    }

    @AfterAll
    void cleanup() {
        try {
            purge();
        } finally {
            MetaContext.clear();
        }
    }

    private Model newModel(String code, String category) {
        Model m = new Model();
        m.setPid(UniqueIdGenerator.generate());
        m.setTenantId(testTenant.getId());
        m.setCode(code);
        m.setVersion(1);
        m.setIsCurrent(true);
        m.setStatus(Status.PUBLISHED.getCode());
        m.setModelCategory(category);
        m.setCreatedAt(Instant.now());
        m.setUpdatedAt(Instant.now());
        m.setDeletedFlag(false);
        ExtensionBean e = new ExtensionBean();
        Map<String, Object> ext = new HashMap<>();
        ext.put("displayName", "AELB " + category);
        ext.put("modelType", "entity");
        ext.put("modelCategory", category);
        e.setExtension(ext);
        m.setExtension(e);
        metaModelMapper.insert(m);
        return m;
    }

    private CommandCompletedEvent event(String modelCode, String recordId, String op) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", "demo");
        payload.put("targetState", "APPROVED");
        return new CommandCompletedEvent(testTenant.getId(), recordId, modelCode, payload,
                modelCode + ":" + op, op);
    }

    private long activityCount(String modelCode) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ab_activity WHERE tenant_id = ? AND object_model = ?",
                Long.class, testTenant.getId(), modelCode);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("a non-trackable (entity-category) model records no activity")
    void nonTrackableModelSkipped() {
        long before = activityCount(nonTrackable.getCode());
        target.onCommandCompleted(event(nonTrackable.getCode(), "rec_nt", "create"));
        assertEquals(before, activityCount(nonTrackable.getCode()),
                "entity-category model is not trackable -> no activity recorded");
    }

    @Test
    @DisplayName("a state_transition on a trackable model records an activity")
    void stateTransitionRecorded() {
        long before = activityCount(trackable.getCode());
        assertDoesNotThrow(() ->
                target.onCommandCompleted(event(trackable.getCode(), "rec_st", "state_transition")));
        assertEquals(before + 1, activityCount(trackable.getCode()),
                "state_transition on a trackable model records one activity");
    }

    private void purge() {
        if (testTenant == null) {
            return;
        }
        Long tid = testTenant.getId();
        try {
            jdbcTemplate.update("DELETE FROM ab_activity WHERE tenant_id = ? AND object_model LIKE 'aelb%'", tid);
            jdbcTemplate.update("DELETE FROM ab_meta_model WHERE code LIKE 'aelb%' AND tenant_id = ?", tid);
        } catch (Exception e) {
            log.warn("aelb purge failed: {}", e.getMessage());
        }
    }

    private void setupTenantContext() {
        if (testUser == null) {
            testUser = userService.findByEmail("aelb-test@auraboot.com");
            if (testUser == null) {
                testUser = userService.signUp("aelb-test@auraboot.com", "test-password-123");
            }
        }
        if (testTenant == null) {
            testTenant = tenantService.findByName("aelb-test-tenant");
            if (testTenant == null) {
                Tenant t = new Tenant();
                t.setPid(UniqueIdGenerator.generate());
                t.setName("aelb-test-tenant");
                t.setDisplayName("ActivityEventListener Branches Test Tenant");
                t.setStatus("active");
                t.setContactEmail("admin@aelb-test.com");
                t.setDescription("Test tenant for ActivityEventListener branches IT");
                t.setDeletedFlag(false);
                t.setCreatedAt(Instant.now());
                t.setUpdatedAt(Instant.now());
                testTenant = tenantService.createTenant(t);
            }
        }
        TenantMember member = tenantMemberService.findByTenantIdAndUserId(testTenant.getId(), testUser.getId());
        if (member == null) {
            tenantMemberService.addMember(testUser.getId(), testTenant.getId(), "active");
        }
        MetaContext.setContext(testTenant.getId(), testUser.getId(), testUser.getPid(), testUser.getUserName());
    }
}
