package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.constant.DataType;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.dto.RelationDefinition;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.entity.payload.FieldRefTargetBean;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.MetaModelService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-stack IT for the (newly implemented) {@code MetaModelServiceImpl.loadModelRelations}:
 * a model's reference fields with a bidirectional config are materialized into navigable
 * {@link RelationDefinition}s (MANY_TO_ONE FK relation + MANY_TO_MANY junction relation), and a
 * plain field contributes none. Also verifies getRelationDefinition lookup + not-found.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("MetaModelServiceImpl Relations IT — loadModelRelations from reference fields")
class MetaModelServiceImplRelationsIT {

    @Autowired
    private MetaModelService metaModelService;
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
    private final AtomicInteger order = new AtomicInteger();
    private String sourceCode;
    private boolean inited = false;

    @BeforeEach
    void setUp() {
        setupTenantContext();
        if (!inited) {
            purge();
            sourceCode = "lmr_src_" + Math.abs(System.nanoTime() % 1_000_000);
            Model source = newModel(sourceCode);
            // a plain field -> no relation
            bind(source, plainField("title"));
            // MANY_TO_ONE reference -> FK relation
            bind(source, refField("customer_ref", "lmr_customer",
                    manyToOne("pid")));
            // MANY_TO_MANY reference -> junction relation
            bind(source, refField("tags_ref", "lmr_tag",
                    manyToMany("mt_lmr_src_tag", "src_id", "tag_id")));
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
    @DisplayName("getModelRelations materializes a MANY_TO_ONE FK relation and a MANY_TO_MANY junction relation")
    void materializesRelations() {
        List<RelationDefinition> relations = metaModelService.getModelRelations(sourceCode);
        assertEquals(2, relations.size(), "two reference fields -> two relations (the plain field contributes none)");

        RelationDefinition m2o = relations.stream().filter(r -> "customer_ref".equals(r.getName())).findFirst().orElseThrow();
        assertEquals(RelationDefinition.RelationType.MANY_TO_ONE, m2o.getRelationType());
        assertEquals("lmr_customer", m2o.getTargetModel());
        assertEquals("pid", m2o.getTargetField());
        assertEquals(sourceCode, m2o.getSourceModel());

        RelationDefinition m2m = relations.stream().filter(r -> "tags_ref".equals(r.getName())).findFirst().orElseThrow();
        assertEquals(RelationDefinition.RelationType.MANY_TO_MANY, m2m.getRelationType());
        assertEquals("lmr_tag", m2m.getTargetModel());
        assertEquals("mt_lmr_src_tag", m2m.getJoinTable());
        assertEquals("src_id", m2m.getSourceField());
        assertEquals("tag_id", m2m.getTargetField());
    }

    @Test
    @DisplayName("getRelationDefinition resolves a named relation and throws for an unknown one")
    void getRelationDefinitionLookup() {
        assertEquals(RelationDefinition.RelationType.MANY_TO_ONE,
                metaModelService.getRelationDefinition(sourceCode, "customer_ref").getRelationType());
        assertThrows(MetaServiceException.class,
                () -> metaModelService.getRelationDefinition(sourceCode, "no_such_relation"));
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
        ext.put("displayName", "LMR " + code);
        ext.put("modelType", "entity");
        e.setExtension(ext);
        m.setExtension(e);
        metaModelMapper.insert(m);
        return m;
    }

    private FieldRefTargetBean.BidirectionalConfig manyToOne(String targetField) {
        FieldRefTargetBean.BidirectionalConfig b = new FieldRefTargetBean.BidirectionalConfig();
        b.setRelationType("MANY_TO_ONE");
        b.setInverseFieldCode("inverse_of_customer");
        b.setIsOwningSide(true);
        return b;
    }

    private FieldRefTargetBean.BidirectionalConfig manyToMany(String junction, String srcCol, String tgtCol) {
        FieldRefTargetBean.BidirectionalConfig b = new FieldRefTargetBean.BidirectionalConfig();
        b.setRelationType("MANY_TO_MANY");
        b.setJunctionTable(junction);
        b.setJunctionSourceColumn(srcCol);
        b.setJunctionTargetColumn(tgtCol);
        return b;
    }

    private Field plainField(String code) {
        return field(code, DataType.STRING.getCode(), null, null);
    }

    private Field refField(String code, String targetEntity, FieldRefTargetBean.BidirectionalConfig bidi) {
        FieldRefTargetBean rt = new FieldRefTargetBean();
        rt.setRefType("entity");
        rt.setTargetEntity(targetEntity);
        rt.setTargetField("pid");
        rt.setBidirectional(bidi);
        return field(code, DataType.STRING.getCode(), rt, null);
    }

    private Field field(String code, String dataType, FieldRefTargetBean refTarget, Object unused) {
        Field f = new Field();
        f.setPid(UniqueIdGenerator.generate());
        f.setTenantId(testTenant.getId());
        f.setCode(code);
        f.setDataType(dataType);
        f.setVersion(1);
        f.setIsCurrent(true);
        f.setStatus(Status.PUBLISHED.getCode());
        f.setCreatedAt(Instant.now());
        f.setUpdatedAt(Instant.now());
        f.setDeletedFlag(false);
        f.setFeature(new FieldFeatureBean());
        if (refTarget != null) {
            f.setRefTarget(refTarget);
        }
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
        b.setFieldOrder(order.incrementAndGet());
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
                    + "(SELECT id FROM ab_meta_model WHERE code LIKE 'lmr_src%' AND tenant_id = ?)", tid);
            jdbcTemplate.update("DELETE FROM ab_meta_field WHERE code IN "
                    + "('title','customer_ref','tags_ref') AND tenant_id = ? "
                    + "AND id NOT IN (SELECT field_id FROM ab_meta_model_field_binding)", tid);
            jdbcTemplate.update("DELETE FROM ab_meta_model WHERE code LIKE 'lmr_src%' AND tenant_id = ?", tid);
        } catch (Exception e) {
            log.warn("lmr purge failed: {}", e.getMessage());
        }
    }

    private void setupTenantContext() {
        if (testUser == null) {
            testUser = userService.findByEmail("lmr-test@auraboot.com");
            if (testUser == null) {
                testUser = userService.signUp("lmr-test@auraboot.com", "test-password-123");
            }
        }
        if (testTenant == null) {
            testTenant = tenantService.findByName("lmr-test-tenant");
            if (testTenant == null) {
                Tenant t = new Tenant();
                t.setPid(UniqueIdGenerator.generate());
                t.setName("lmr-test-tenant");
                t.setDisplayName("LoadModelRelations Test Tenant");
                t.setStatus("active");
                t.setContactEmail("admin@lmr-test.com");
                t.setDescription("Test tenant for loadModelRelations IT");
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
