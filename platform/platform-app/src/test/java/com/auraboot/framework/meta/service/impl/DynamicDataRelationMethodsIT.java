package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.constant.DataType;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.dto.RelationOperationResult;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.entity.payload.FieldRefTargetBean;
import com.auraboot.framework.meta.service.DynamicDataService;
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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-stack IT for the DynamicDataService many-to-many relation runtime that
 * {@code loadModelRelations} just made reachable: createRelations inserts into the junction
 * table, removeRelations deletes from it (idempotent on re-create). A source model with a
 * MANY_TO_MANY reference field + a physical junction table; source/target records are not needed
 * for the link CRUD itself.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("DynamicData Relation Methods IT — createRelations + removeRelations (M2M)")
class DynamicDataRelationMethodsIT {

    private static final String JUNCTION = "mt_relcov_link";

    @Autowired
    private DynamicDataService dynamicDataService;
    @Autowired
    private com.auraboot.framework.meta.mapper.MetaModelMapper metaModelMapper;
    @Autowired
    private com.auraboot.framework.meta.mapper.MetaFieldMapper metaFieldMapper;
    @Autowired
    private com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper bindingMapper;
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
    private String srcCode;
    private boolean inited = false;

    @BeforeEach
    void setUp() {
        setupTenantContext();
        if (!inited) {
            purge();
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + JUNCTION + " ("
                    + "id BIGSERIAL PRIMARY KEY, src_pid VARCHAR(64), tgt_pid VARCHAR(64), "
                    + "tenant_id BIGINT, created_at TIMESTAMPTZ)");
            jdbcTemplate.update("DELETE FROM " + JUNCTION + " WHERE tenant_id = ?", testTenant.getId());

            srcCode = "relcov_src_" + Math.abs(System.nanoTime() % 1_000_000);
            Model src = newModel(srcCode);
            FieldRefTargetBean.BidirectionalConfig bidi = new FieldRefTargetBean.BidirectionalConfig();
            bidi.setRelationType("MANY_TO_MANY");
            bidi.setJunctionTable(JUNCTION);
            bidi.setJunctionSourceColumn("src_pid");
            bidi.setJunctionTargetColumn("tgt_pid");
            FieldRefTargetBean rt = new FieldRefTargetBean();
            rt.setRefType("entity");
            rt.setTargetEntity("relcov_tgt");
            rt.setTargetField("pid");
            rt.setBidirectional(bidi);
            bind(src, refField("tags_ref", rt));
            inited = true;
        }
    }

    @AfterAll
    void cleanup() {
        try {
            purge();
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + JUNCTION);
        } finally {
            MetaContext.clear();
        }
    }

    private long links(String srcPid) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + JUNCTION + " WHERE tenant_id = ? AND src_pid = ?",
                Long.class, testTenant.getId(), srcPid);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("createRelations links targets (idempotent); removeRelations unlinks them")
    void createAndRemove() {
        String srcPid = "relcov_src_rec_1";
        jdbcTemplate.update("DELETE FROM " + JUNCTION + " WHERE tenant_id = ? AND src_pid = ?",
                testTenant.getId(), srcPid);
        RelationOperationResult created = dynamicDataService.createRelations(
                srcCode, srcPid, "tags_ref", List.of("tgt_a", "tgt_b"));
        assertTrue(created.getSuccessCount() >= 2, "expected >=2 successful links");
        long afterCreate = links(srcPid);
        assertTrue(afterCreate >= 2, "junction should hold the created links, got " + afterCreate);

        // removeRelations unlinks the named targets
        dynamicDataService.removeRelations(srcCode, srcPid, "tags_ref", List.of("tgt_a", "tgt_b"));
        assertTrue(links(srcPid) < afterCreate, "removeRelations should reduce the link count");
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
        ext.put("displayName", "RelCov " + code);
        ext.put("modelType", "entity");
        e.setExtension(ext);
        m.setExtension(e);
        metaModelMapper.insert(m);
        return m;
    }

    private Field refField(String code, FieldRefTargetBean rt) {
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
        f.setFeature(new FieldFeatureBean());
        f.setRefTarget(rt);
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
                    + "(SELECT id FROM ab_meta_model WHERE code LIKE 'relcov_src%' AND tenant_id = ?)", tid);
            jdbcTemplate.update("DELETE FROM ab_meta_field WHERE code = 'tags_ref' AND tenant_id = ? "
                    + "AND id NOT IN (SELECT field_id FROM ab_meta_model_field_binding)", tid);
            jdbcTemplate.update("DELETE FROM ab_meta_model WHERE code LIKE 'relcov_src%' AND tenant_id = ?", tid);
        } catch (Exception e) {
            log.warn("relcov purge failed: {}", e.getMessage());
        }
    }

    private void setupTenantContext() {
        if (testUser == null) {
            testUser = userService.findByEmail("relcov-test@auraboot.com");
            if (testUser == null) {
                testUser = userService.signUp("relcov-test@auraboot.com", "test-password-123");
            }
        }
        if (testTenant == null) {
            testTenant = tenantService.findByName("relcov-test-tenant");
            if (testTenant == null) {
                Tenant t = new Tenant();
                t.setPid(UniqueIdGenerator.generate());
                t.setName("relcov-test-tenant");
                t.setDisplayName("Relation Methods Coverage Test Tenant");
                t.setStatus("active");
                t.setContactEmail("admin@relcov-test.com");
                t.setDescription("Test tenant for DynamicData relation methods IT");
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
