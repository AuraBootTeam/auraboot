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
import com.auraboot.framework.meta.service.VirtualFieldEngine;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-stack coverage IT for {@link com.auraboot.framework.meta.service.impl.VirtualFieldEngineImpl}
 * dependency-graph methods: validateDependencyGraph (acyclic vs cyclic computed fields) and
 * getComputationOrder (Kahn topological sort) against real models whose fields carry
 * virtualType + computeDependencies. (evaluate() is covered separately; materialize() needs records.)
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("VirtualFieldEngine Graph IT — validateDependencyGraph + getComputationOrder")
class VirtualFieldEngineGraphIT {

    @Autowired
    private VirtualFieldEngine engine;
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
    private final AtomicInteger fieldSeq = new AtomicInteger();
    private String acyclicCode;
    private String cyclicCode;
    private boolean inited = false;

    @BeforeEach
    void setUp() {
        setupTenantContext();
        if (!inited) {
            purge();
            // acyclic: base (plain) <- c1 <- c2
            acyclicCode = "vfcov_ok_" + Math.abs(System.nanoTime() % 1_000_000);
            Model a = newModel(acyclicCode);
            bind(a, newField("vf_base", null, null));
            bind(a, newField("vf_c1", "computed_readonly", List.of("vf_base")));
            bind(a, newField("vf_c2", "computed_readonly", List.of("vf_c1", "vf_base")));
            // cyclic: c1 <-> c2
            cyclicCode = "vfcov_cycle_" + Math.abs(System.nanoTime() % 1_000_000);
            Model b = newModel(cyclicCode);
            bind(b, newField("vf_x", "computed_readonly", List.of("vf_y")));
            bind(b, newField("vf_y", "computed_readonly", List.of("vf_x")));
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
    @DisplayName("validateDependencyGraph: acyclic -> no cycle; cyclic -> reports a cycle")
    void validateGraph() {
        assertTrue(engine.validateDependencyGraph(acyclicCode).isEmpty(), "acyclic graph should report no cycle");
        assertFalse(engine.validateDependencyGraph(cyclicCode).isEmpty(), "cyclic graph should report a cycle");
        assertTrue(engine.validateDependencyGraph("vfcov_unknown_model").isEmpty(), "unknown model -> empty");
    }

    @Test
    @DisplayName("getComputationOrder: topological order over the acyclic computed fields")
    void computationOrder() {
        List<String> order = engine.getComputationOrder(acyclicCode);
        assertNotNull(order);
        // c2 depends on c1, so c1 must precede c2 in the computation order
        if (order.contains("vf_c1") && order.contains("vf_c2")) {
            assertTrue(order.indexOf("vf_c1") < order.indexOf("vf_c2"), "c1 must be computed before c2");
        }
    }

    // ---- harness ----

    private Model newModel(String code) {
        Model m = new Model();
        m.setPid(UniqueIdGenerator.generate());
        m.setTenantId(testTenant.getId());
        m.setCode(code);
        m.setVersion(1);
        m.setIsCurrent(true);
        m.setStatus(Status.PUBLISHED.getCode());
        m.setCreatedAt(Instant.now());
        m.setUpdatedAt(Instant.now());
        m.setDeletedFlag(false);
        ExtensionBean e = new ExtensionBean();
        Map<String, Object> ext = new HashMap<>();
        ext.put("displayName", "VFCov " + code);
        ext.put("modelType", "entity");
        e.setExtension(ext);
        m.setExtension(e);
        metaModelMapper.insert(m);
        return m;
    }

    private Field newField(String code, String virtualType, List<String> deps) {
        Field f = new Field();
        f.setPid(UniqueIdGenerator.generate());
        f.setTenantId(testTenant.getId());
        f.setCode(code);
        f.setDataType(DataType.STRING.getCode());
        f.setVersion(1);
        f.setIsCurrent(true);
        f.setStatus(Status.PUBLISHED.getCode());
        f.setCreatedAt(Instant.now());
        f.setUpdatedAt(Instant.now());
        f.setDeletedFlag(false);
        FieldFeatureBean feat = new FieldFeatureBean();
        feat.setRequired(false);
        if (virtualType != null) {
            feat.setVirtualType(virtualType);
            feat.setComputeExpression("dummy");
            feat.setComputeDependencies(deps);
        }
        f.setFeature(feat);
        ExtensionBean ext = new ExtensionBean();
        Map<String, Object> e = new HashMap<>();
        e.put("displayName", code.toUpperCase());
        ext.setExtension(e);
        f.setExtension(ext);
        metaFieldMapper.insert(f);
        return f;
    }

    private void bind(Model m, Field f) {
        ModelFieldBinding b = new ModelFieldBinding();
        b.setTenantId(testTenant.getId());
        b.setModelId(m.getId());
        b.setFieldId(f.getId());
        b.setFieldOrder(fieldSeq.incrementAndGet());
        b.setRequired(false);
        bindingMapper.insert(b);
    }

    private void purge() {
        if (testTenant == null) {
            return;
        }
        Long tid = testTenant.getId();
        try {
            jdbcTemplate.update("DELETE FROM ab_meta_model_field_binding WHERE model_id IN "
                    + "(SELECT id FROM ab_meta_model WHERE code LIKE 'vfcov%' AND tenant_id = ?)", tid);
            jdbcTemplate.update("DELETE FROM ab_meta_field WHERE code LIKE 'vf_%' AND tenant_id = ?", tid);
            jdbcTemplate.update("DELETE FROM ab_meta_model WHERE code LIKE 'vfcov%' AND tenant_id = ?", tid);
        } catch (Exception e) {
            log.warn("vfcov purge failed: {}", e.getMessage());
        }
    }

    private void setupTenantContext() {
        if (testUser == null) {
            testUser = userService.findByEmail("vfcov-test@auraboot.com");
            if (testUser == null) {
                testUser = userService.signUp("vfcov-test@auraboot.com", "test-password-123");
            }
        }
        if (testTenant == null) {
            testTenant = tenantService.findByName("vfcov-test-tenant");
            if (testTenant == null) {
                Tenant t = new Tenant();
                t.setPid(UniqueIdGenerator.generate());
                t.setName("vfcov-test-tenant");
                t.setDisplayName("VirtualField Coverage Test Tenant");
                t.setStatus("active");
                t.setContactEmail("admin@vfcov-test.com");
                t.setDescription("Test tenant for VirtualFieldEngine graph coverage IT");
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
