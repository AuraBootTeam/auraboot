package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.constant.DataType;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.dto.BindingConfigRequest;
import com.auraboot.framework.meta.dto.BindingConfiguration;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.FieldBindingContextService;
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
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-stack coverage IT for {@link FieldBindingContextServiceImpl} — model/field binding
 * configuration (configureBinding insert+update, getBindingConfiguration, updateBindingConfiguration,
 * validation rejects) against a real model + field. Was ~1% line-covered.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("FieldBindingContextServiceImpl Coverage IT — binding configure/get/update/validate")
class FieldBindingContextServiceImplCoverageIT {

    @Autowired
    private FieldBindingContextService bindingService;
    @Autowired
    private MetaModelMapper metaModelMapper;
    @Autowired
    private MetaFieldMapper metaFieldMapper;
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
    private Field field;
    private boolean inited = false;

    @BeforeEach
    void setUp() {
        setupTenantContext();
        if (!inited) {
            String code = "fbcov_" + Math.abs(System.nanoTime() % 1_000_000);
            purge();
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
            model.setExtension(extension("FBCov Model", "entity"));
            metaModelMapper.insert(model);

            field = new Field();
            field.setPid(UniqueIdGenerator.generate());
            field.setTenantId(testTenant.getId());
            field.setCode("fbcov_field");
            field.setDataType(DataType.STRING.getCode());
            field.setVersion(1);
            field.setIsCurrent(true);
            field.setStatus(Status.PUBLISHED.getCode());
            field.setCreatedAt(Instant.now());
            field.setUpdatedAt(Instant.now());
            field.setDeletedFlag(false);
            FieldFeatureBean feature = new FieldFeatureBean();
            feature.setRequired(false);
            field.setFeature(feature);
            field.setExtension(extension("FBCOV_FIELD", null));
            metaFieldMapper.insert(field);

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

    private ExtensionBean extension(String displayName, String modelType) {
        ExtensionBean e = new ExtensionBean();
        Map<String, Object> ext = new HashMap<>();
        ext.put("displayName", displayName);
        if (modelType != null) {
            ext.put("modelType", modelType);
        }
        e.setExtension(ext);
        return e;
    }

    private BindingConfigRequest request(String alias) {
        BindingConfigRequest r = new BindingConfigRequest();
        r.setAliasCode(alias);
        r.setRequired(false);
        r.setNullable(true);
        r.setVisible(true);
        r.setEditable(true);
        return r;
    }

    @Test
    @DisplayName("configureBinding inserts then updates; getBindingConfiguration round-trips")
    void configureAndGet() {
        BindingConfiguration created = bindingService.configureBinding(model.getPid(), field.getPid(), request("alias_one"));
        assertNotNull(created.getBindingId());
        assertEquals(field.getPid(), created.getFieldPid());

        Optional<BindingConfiguration> fetched = bindingService.getBindingConfiguration(model.getPid(), field.getPid());
        assertTrue(fetched.isPresent());

        // second configure -> update path
        BindingConfiguration updated = bindingService.configureBinding(model.getPid(), field.getPid(), request("alias_two"));
        assertEquals(created.getBindingId(), updated.getBindingId());

        // updateBindingConfiguration by id
        BindingConfiguration byId = bindingService.updateBindingConfiguration(created.getBindingId(), request("alias_three"));
        assertEquals(created.getBindingId(), byId.getBindingId());
    }

    @Test
    @DisplayName("configureBinding validates inputs (empty pids, null request, missing model/field, bad alias)")
    void configureValidation() {
        assertThrows(ValidationException.class,
                () -> bindingService.configureBinding("", field.getPid(), request("a")));
        assertThrows(ValidationException.class,
                () -> bindingService.configureBinding(model.getPid(), field.getPid(), null));
        // missing model / field: the service rejects (findByPid surfaces its own exception type)
        assertThrows(RuntimeException.class,
                () -> bindingService.configureBinding("no_such_model_pid", field.getPid(), request("a")));
        assertThrows(RuntimeException.class,
                () -> bindingService.configureBinding(model.getPid(), "no_such_field_pid", request("a")));
        assertThrows(ValidationException.class,
                () -> bindingService.configureBinding(model.getPid(), field.getPid(), request("Bad Alias!")));
    }

    @Test
    @DisplayName("getBindingConfiguration is empty for an unbound field")
    void getEmptyWhenUnbound() {
        assertTrue(bindingService.getBindingConfiguration(model.getPid(), "unbound_field_pid").isEmpty());
    }

    private void purge() {
        if (testTenant == null) {
            return;
        }
        Long tid = testTenant.getId();
        try {
            jdbcTemplate.update("DELETE FROM ab_meta_model_field_binding WHERE model_id IN "
                    + "(SELECT id FROM ab_meta_model WHERE code LIKE 'fbcov%' AND tenant_id = ?)", tid);
            jdbcTemplate.update("DELETE FROM ab_meta_field WHERE code = 'fbcov_field' AND tenant_id = ?", tid);
            jdbcTemplate.update("DELETE FROM ab_meta_model WHERE code LIKE 'fbcov%' AND tenant_id = ?", tid);
        } catch (Exception e) {
            log.warn("fbcov purge failed: {}", e.getMessage());
        }
    }

    private void setupTenantContext() {
        if (testUser == null) {
            testUser = userService.findByEmail("fbcov-test@auraboot.com");
            if (testUser == null) {
                testUser = userService.signUp("fbcov-test@auraboot.com", "test-password-123");
            }
        }
        if (testTenant == null) {
            testTenant = tenantService.findByName("fbcov-test-tenant");
            if (testTenant == null) {
                Tenant t = new Tenant();
                t.setPid(UniqueIdGenerator.generate());
                t.setName("fbcov-test-tenant");
                t.setDisplayName("FieldBinding Coverage Test Tenant");
                t.setStatus("active");
                t.setContactEmail("admin@fbcov-test.com");
                t.setDescription("Test tenant for FieldBindingContext coverage IT");
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
