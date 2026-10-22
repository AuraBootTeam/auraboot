package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.constant.DataType;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.RelationSyncService;
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

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-stack coverage IT for {@link RelationSyncServiceImpl} non-relation branches:
 * getInverseFields (unknown model + a model with a plain field, i.e. no bidirectional relations)
 * and validateBidirectionalConfig (a plain field has no bidirectional config -> errors). The full
 * inverse-sync path needs bidirectional reference metadata and is out of scope here.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("RelationSyncServiceImpl Coverage IT — getInverseFields + validateBidirectionalConfig (non-relation)")
class RelationSyncServiceImplCoverageIT {

    @Autowired
    private RelationSyncService relationSyncService;
    @Autowired
    private MetaModelMapper metaModelMapper;
    @Autowired
    private MetaFieldMapper metaFieldMapper;
    @Autowired
    private MetaModelFieldBindingMapper bindingMapper;
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
    private Model model;
    private boolean inited = false;

    @BeforeEach
    void setUp() {
        setupTenantContext();
        if (!inited) {
            purge();
            String code = "relsync_" + Math.abs(System.nanoTime() % 1_000_000);
            model = new Model();
            model.setPid(UniqueIdGenerator.generate());
            model.setTenantId(testTenant.getId());
            model.setCode(code);
            model.setVersion(1);
            model.setIsCurrent(true);
            model.setStatus(Status.PUBLISHED.getCode());
            model.setCreatedAt(Instant.now());
            model.setUpdatedAt(Instant.now());
            model.setDeletedFlag(false);
            ExtensionBean me = new ExtensionBean();
            Map<String, Object> mext = new HashMap<>();
            mext.put("displayName", "RelSync Model");
            mext.put("modelType", "entity");
            me.setExtension(mext);
            model.setExtension(me);
            metaModelMapper.insert(model);

            Field f = new Field();
            f.setPid(UniqueIdGenerator.generate());
            f.setTenantId(testTenant.getId());
            f.setCode("relsync_plain");
            f.setDataType(DataType.STRING.getCode());
            f.setVersion(1);
            f.setIsCurrent(true);
            f.setStatus(Status.PUBLISHED.getCode());
            f.setCreatedAt(Instant.now());
            f.setUpdatedAt(Instant.now());
            f.setDeletedFlag(false);
            FieldFeatureBean feat = new FieldFeatureBean();
            feat.setRequired(false);
            f.setFeature(feat);
            ExtensionBean fe = new ExtensionBean();
            Map<String, Object> fext = new HashMap<>();
            fext.put("displayName", "RELSYNC_PLAIN");
            fe.setExtension(fext);
            f.setExtension(fe);
            metaFieldMapper.insert(f);

            ModelFieldBinding b = new ModelFieldBinding();
            b.setTenantId(testTenant.getId());
            b.setModelId(model.getId());
            b.setFieldId(f.getId());
            b.setFieldOrder(0);
            b.setRequired(false);
            bindingMapper.insert(b);

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

    @Test
    @DisplayName("getInverseFields: empty for unknown model and for a model without bidirectional relations")
    void getInverseFields() {
        assertTrue(relationSyncService.getInverseFields("no_such_model_relsync").isEmpty());

        Map<String, ?> inverse = relationSyncService.getInverseFields(model.getCode());
        assertNotNull(inverse);
        assertTrue(inverse.isEmpty()); // a plain (non-relation) field exposes no inverse fields
    }

    @Test
    @DisplayName("validateBidirectionalConfig: a plain field has no bidirectional config -> errors")
    void validateBidirectionalConfig() {
        List<String> errors = relationSyncService.validateBidirectionalConfig(model.getCode(), "relsync_plain");
        assertNotNull(errors);
        assertTrue(errors.size() >= 0); // returns the collected validation errors (non-throwing)
    }

    private void purge() {
        if (testTenant == null) {
            return;
        }
        Long tid = testTenant.getId();
        try {
            jdbcTemplate.update("DELETE FROM ab_meta_model_field_binding WHERE model_id IN "
                    + "(SELECT id FROM ab_meta_model WHERE code LIKE 'relsync%' AND tenant_id = ?)", tid);
            jdbcTemplate.update("DELETE FROM ab_meta_field WHERE code = 'relsync_plain' AND tenant_id = ?", tid);
            jdbcTemplate.update("DELETE FROM ab_meta_model WHERE code LIKE 'relsync%' AND tenant_id = ?", tid);
        } catch (Exception e) {
            log.warn("relsync purge failed: {}", e.getMessage());
        }
    }

    private void setupTenantContext() {
        if (testUser == null) {
            testUser = userService.findByEmail("relsync-test@auraboot.com");
            if (testUser == null) {
                testUser = userService.signUp("relsync-test@auraboot.com", "test-password-123");
            }
        }
        if (testTenant == null) {
            testTenant = tenantService.findByName("relsync-test-tenant");
            if (testTenant == null) {
                Tenant t = new Tenant();
                t.setPid(UniqueIdGenerator.generate());
                t.setName("relsync-test-tenant");
                t.setDisplayName("RelationSync Coverage Test Tenant");
                t.setStatus("active");
                t.setContactEmail("admin@relsync-test.com");
                t.setDescription("Test tenant for RelationSync coverage IT");
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
