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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-stack coverage IT for {@link ActivityEventListener} — onCommandCompleted writes an
 * activity row for trackable (document/master category) models across operation types, and
 * short-circuits on blank model / blank record / non-trackable model. Verified via ab_activity.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("ActivityEventListener Coverage IT — onCommandCompleted")
class ActivityEventListenerCoverageIT {

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
    private Model trackable;
    private ActivityEventListener target;
    private boolean inited = false;

    @BeforeEach
    void setUp() {
        setupTenantContext();
        // unwrap the @Async proxy so onCommandCompleted runs synchronously in this thread
        // (keeping the MetaContext tenant) instead of being dispatched to eventTaskExecutor.
        target = AopTestUtils.getTargetObject(listener);
        if (!inited) {
            purge();
            trackable = newModel("aelcov_doc_" + Math.abs(System.nanoTime() % 1_000_000), "document");
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
        ext.put("displayName", "AEL Cov " + category);
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
        return new CommandCompletedEvent(testTenant.getId(), recordId, modelCode, payload,
                modelCode + ":" + op, op);
    }

    private long activityCount() {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ab_activity WHERE tenant_id = ? AND object_model = ?",
                Long.class, testTenant.getId(), trackable.getCode());
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("records an activity for a trackable model across operation types")
    void recordsTrackable() {
        long before = activityCount();
        target.onCommandCompleted(event(trackable.getCode(), "rec_1", "create"));
        target.onCommandCompleted(event(trackable.getCode(), "rec_1", "update"));
        target.onCommandCompleted(event(trackable.getCode(), "rec_1", "delete"));
        target.onCommandCompleted(event(trackable.getCode(), "rec_1", "state_transition"));
        target.onCommandCompleted(event(trackable.getCode(), "rec_2", "something_else"));
        long after = activityCount();
        assertTrue(after >= before + 5, "expected >=5 new activities, before=" + before + " after=" + after);
    }

    @Test
    @DisplayName("short-circuits on blank model / blank record / non-trackable model")
    void shortCircuits() {
        long before = activityCount();
        target.onCommandCompleted(event(null, "rec_x", "create"));        // blank model
        target.onCommandCompleted(event(trackable.getCode(), null, "create")); // blank record
        target.onCommandCompleted(event("no_such_model_aelcov", "rec_x", "create")); // unknown -> not trackable
        assertEquals(before, activityCount(), "no activity should be recorded for short-circuited events");
    }

    private void purge() {
        if (testTenant == null) {
            return;
        }
        Long tid = testTenant.getId();
        try {
            jdbcTemplate.update("DELETE FROM ab_activity WHERE tenant_id = ? AND object_model LIKE 'aelcov%'", tid);
            jdbcTemplate.update("DELETE FROM ab_meta_model WHERE code LIKE 'aelcov%' AND tenant_id = ?", tid);
        } catch (Exception e) {
            log.warn("aelcov purge failed: {}", e.getMessage());
        }
    }

    private void setupTenantContext() {
        if (testUser == null) {
            testUser = userService.findByEmail("aelcov-test@auraboot.com");
            if (testUser == null) {
                testUser = userService.signUp("aelcov-test@auraboot.com", "test-password-123");
            }
        }
        if (testTenant == null) {
            testTenant = tenantService.findByName("aelcov-test-tenant");
            if (testTenant == null) {
                Tenant t = new Tenant();
                t.setPid(UniqueIdGenerator.generate());
                t.setName("aelcov-test-tenant");
                t.setDisplayName("ActivityEventListener Coverage Test Tenant");
                t.setStatus("active");
                t.setContactEmail("admin@aelcov-test.com");
                t.setDescription("Test tenant for ActivityEventListener coverage IT");
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
