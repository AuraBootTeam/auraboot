package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.dto.CascadeDictRequest;
import com.auraboot.framework.meta.dto.CascadeDictResult;
import com.auraboot.framework.meta.dto.DictItemData;
import com.auraboot.framework.meta.dto.DictTreeNode;
import com.auraboot.framework.meta.dto.DictValidationResult;
import com.auraboot.framework.meta.entity.Dict;
import com.auraboot.framework.meta.entity.DictItem;
import com.auraboot.framework.meta.mapper.DictItemMapper;
import com.auraboot.framework.meta.mapper.DictMapper;
import com.auraboot.framework.meta.service.DictCascadeService;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-stack integration test for {@link DictCascadeServiceImpl}.
 *
 * <p>Part of OSS coverage initiative #8/#9 (tracker:
 * {@code docs/backlog/2026-06-10-oss-coverage-to-80-tracker.md}). The cascade service
 * was at ~1.8% line coverage; this exercises its full public surface against a real
 * cascade dictionary persisted in the shared DB (dict + hierarchical items inserted
 * via the real mappers — system under test is the real service, not mocks).
 *
 * <p>NOTE on shape: every cascade query method resolves the dict via
 * {@code findCurrentByCode} (requires {@code is_current = true}) and requires
 * {@code dict_type = 'cascade'} exactly. A no-parent request is a <em>root</em> query
 * ({@code findTopLevelByDictId}), so methods built on the no-parent request observe
 * only top-level items — the assertions below reflect that real behavior; deeper
 * levels are exercised through {@code getCascadeChildren(code, parentValue)}.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@DisplayName("DictCascadeServiceImpl Real-Stack Integration Test")
class DictCascadeServiceImplIntegrationTest {

    private static final String CODE_PREFIX = "covcascade";
    private static final String RUN = Long.toString(Math.abs(System.nanoTime()), 36);

    @Autowired
    private DictCascadeService cascadeService;
    @Autowired
    private DictMapper dictMapper;
    @Autowired
    private DictItemMapper dictItemMapper;
    @Autowired
    private UserService userService;
    @Autowired
    private TenantService tenantService;
    @Autowired
    private TenantMemberService tenantMemberService;

    private final AtomicInteger seq = new AtomicInteger();
    private Tenant testTenant;

    private String uniqueCode(String label) {
        return CODE_PREFIX + RUN + "_" + seq.incrementAndGet() + "_" + label;
    }

    @BeforeEach
    void setUp() {
        String testEmail = "covcascade-test@auraboot.com";
        User testUser = userService.findByEmail(testEmail);
        if (testUser == null) {
            testUser = userService.signUp(testEmail, "test-password-123");
        }

        String testTenantName = "covcascade-test-tenant";
        testTenant = tenantService.findByName(testTenantName);
        if (testTenant == null) {
            Tenant tenant = new Tenant();
            tenant.setPid(UniqueIdGenerator.generate());
            tenant.setName(testTenantName);
            tenant.setDisplayName("Cascade Coverage Test Tenant");
            tenant.setStatus("active");
            tenant.setContactEmail("admin@covcascade-test.com");
            tenant.setDescription("Test tenant for cascade-dict coverage IT");
            tenant.setDeletedFlag(false);
            tenant.setCreatedAt(Instant.now());
            tenant.setUpdatedAt(Instant.now());
            testTenant = tenantService.createTenant(tenant);
        }

        TenantMember member = tenantMemberService.findByTenantIdAndUserId(testTenant.getId(), testUser.getId());
        if (member == null) {
            tenantMemberService.addMember(testUser.getId(), testTenant.getId(), "active");
        }

        MetaContext.setContext(testTenant.getId(), testUser.getId(), testUser.getPid(), testUser.getUserName());
    }

    @AfterEach
    void tearDown() {
        try {
            List<Dict> dicts = dictMapper.selectList(new LambdaQueryWrapper<Dict>()
                    .eq(Dict::getTenantId, testTenant.getId())
                    .likeRight(Dict::getCode, CODE_PREFIX));
            for (Dict d : dicts) {
                dictItemMapper.delete(new LambdaQueryWrapper<DictItem>().eq(DictItem::getDictId, d.getId()));
            }
            if (!dicts.isEmpty()) {
                dictMapper.delete(new LambdaQueryWrapper<Dict>()
                        .eq(Dict::getTenantId, testTenant.getId())
                        .likeRight(Dict::getCode, CODE_PREFIX));
            }
        } catch (Exception e) {
            log.warn("cascade cleanup failed: {}", e.getMessage());
        } finally {
            MetaContext.clear();
        }
    }

    // ---------- data setup helpers ----------

    private Dict insertDict(String code, String dictType) {
        Dict dict = new Dict();
        dict.setPid(UniqueIdGenerator.generate());
        dict.setTenantId(testTenant.getId());
        dict.setCode(code);
        dict.setName("Region " + code);
        dict.setDictType(dictType);
        dict.setStatus("published");
        dict.setVersion(1);
        dict.setSemver("1.0.0");
        dict.setIsCurrent(true);
        dict.setCreatedAt(Instant.now());
        dict.setUpdatedAt(Instant.now());
        dictMapper.insert(dict);
        return dict;
    }

    private void addItem(Long dictId, String value, String label, String parentValue, int sort) {
        DictItem item = new DictItem();
        item.setPid(UniqueIdGenerator.generate());
        item.setTenantId(testTenant.getId());
        item.setDictId(dictId);
        item.setValue(value);
        item.setLabel(label);
        item.setParentValue(parentValue);
        item.setSortNo(sort);
        item.setStatus("enabled");
        item.setSource("user");
        item.setCreatedAt(Instant.now());
        item.setUpdatedAt(Instant.now());
        dictItemMapper.insert(item);
    }

    /** China -> {Zhejiang -> {Hangzhou, Ningbo}, Jiangsu}. Returns dict code. */
    private String createRegionCascade() {
        String code = uniqueCode("region");
        Dict dict = insertDict(code, "cascade");
        addItem(dict.getId(), "cn", "China", null, 1);
        addItem(dict.getId(), "zj", "Zhejiang", "cn", 1);
        addItem(dict.getId(), "js", "Jiangsu", "cn", 2);
        addItem(dict.getId(), "hz", "Hangzhou", "zj", 1);
        addItem(dict.getId(), "nb", "Ningbo", "zj", 2);
        return code;
    }

    // ==================== query ====================

    @Test
    @DisplayName("queryCascadeDict: root query returns top-level items; parent query returns children")
    void queryCascadeDict() {
        String code = createRegionCascade();

        CascadeDictResult root = cascadeService.queryCascadeDict(new CascadeDictRequest(code));
        assertTrue(root.getSuccess());
        assertEquals(1, root.getItems().size());
        assertEquals("cn", root.getItems().get(0).getValue());

        CascadeDictResult children = cascadeService.queryCascadeDict(new CascadeDictRequest(code, "cn"));
        assertTrue(children.getSuccess());
        assertEquals(2, children.getItems().size());
        DictItemData first = children.getItems().get(0);
        assertEquals("zj", first.getValue());
        assertEquals("Zhejiang", first.getLabel());
        assertEquals("cn", first.getParentValue());
    }

    @Test
    @DisplayName("queryCascadeDict: missing dict and non-cascade dict both fail gracefully")
    void queryCascadeDictFailures() {
        CascadeDictResult missing = cascadeService.queryCascadeDict(new CascadeDictRequest(uniqueCode("ghost")));
        assertFalse(missing.getSuccess());
        assertNotNull(missing.getErrorMessage());

        String treeCode = uniqueCode("nottree");
        insertDict(treeCode, "tree"); // not "cascade"
        CascadeDictResult wrongType = cascadeService.queryCascadeDict(new CascadeDictRequest(treeCode));
        assertFalse(wrongType.getSuccess());
        assertTrue(wrongType.getErrorMessage().contains("级联"));
    }

    @Test
    @DisplayName("getCascadeChildren / getCascadeRoots traverse the hierarchy")
    void childrenAndRoots() {
        String code = createRegionCascade();

        List<DictItemData> roots = cascadeService.getCascadeRoots(code);
        assertEquals(1, roots.size());
        assertEquals("cn", roots.get(0).getValue());

        List<DictItemData> provinces = cascadeService.getCascadeChildren(code, "cn");
        assertEquals(2, provinces.size());

        List<DictItemData> cities = cascadeService.getCascadeChildren(code, "zj");
        assertEquals(2, cities.size());
        assertEquals("hz", cities.get(0).getValue());
    }

    @Test
    @DisplayName("queryByParams / queryByFilters / queryByComposite delegate to the cascade query")
    void parameterQueries() {
        String code = createRegionCascade();

        assertEquals(1, cascadeService.queryByParams(code, Map.of("k", "v")).size());
        assertEquals(1, cascadeService.queryByFilters(code, Map.of("k", (Object) "v")).size());
        assertEquals(1, cascadeService.queryByComposite(new CascadeDictRequest(code)).size());
    }

    // ==================== tree ====================

    @Test
    @DisplayName("buildCascadeTree returns the root node; explicit/unknown rootValue handled")
    void buildTree() {
        String code = createRegionCascade();

        DictTreeNode tree = cascadeService.buildCascadeTree(code);
        assertNotNull(tree);
        assertEquals("cn", tree.getValue());

        DictTreeNode rooted = cascadeService.buildCascadeTree(code, "cn");
        assertEquals("cn", rooted.getValue());

        DictTreeNode unknown = cascadeService.buildCascadeTree(code, "no-such-root");
        assertNull(unknown.getValue());
    }

    @Test
    @DisplayName("batchBuildCascadeTree maps each code; empty input returns empty map")
    void batchBuildTree() {
        String code = createRegionCascade();
        Map<String, DictTreeNode> trees = cascadeService.batchBuildCascadeTree(List.of(code));
        assertTrue(trees.containsKey(code));
        assertTrue(cascadeService.batchBuildCascadeTree(List.of()).isEmpty());
    }

    // ==================== structure / levels ====================

    @Test
    @DisplayName("getCascadeStructure / getItemsByLevel / getMaxLevel / getNodePath")
    void structureAndLevels() {
        String code = createRegionCascade();

        Map<String, Object> structure = cascadeService.getCascadeStructure(code);
        assertEquals(code, structure.get("dictCode"));
        assertTrue(structure.containsKey("totalCount"));

        assertNotNull(cascadeService.getItemsByLevel(code, 0));
        assertNotNull(cascadeService.getMaxLevel(code));

        // "cn" is a root node -> path is itself; a deeper node not in the root set -> empty path
        assertEquals(1, cascadeService.getNodePath(code, "cn").size());
        assertEquals(0, cascadeService.getNodePath(code, "hz").size());
    }

    // ==================== validation / integrity ====================

    @Test
    @DisplayName("validateCascadeConfig + integrity + circular-reference checks on a clean cascade")
    void validationChecks() {
        String code = createRegionCascade();

        DictValidationResult valid = cascadeService.validateCascadeConfig(code);
        assertTrue(valid.getValid());

        Map<String, Object> integrity = cascadeService.checkCascadeIntegrity(code);
        assertEquals(Boolean.TRUE, integrity.get("valid"));

        assertFalse(cascadeService.hasCircularReference(code));

        // missing dict fails validation
        DictValidationResult missing = cascadeService.validateCascadeConfig(uniqueCode("ghost"));
        assertFalse(missing.getValid());
    }

    // ==================== cache / batch / metrics ====================

    @Test
    @DisplayName("warmup / clear / refresh cache execute without error")
    void cacheOps() {
        String code = createRegionCascade();
        assertDoesNotThrow(() -> cascadeService.warmupCascadeCache(code));
        assertDoesNotThrow(() -> cascadeService.clearCascadeCache(code));
        assertDoesNotThrow(() -> cascadeService.refreshCascadeCache(code));
    }

    @Test
    @DisplayName("batchQueryCascadeDict returns one result per request; empty input returns empty list")
    void batchQuery() {
        String code1 = createRegionCascade();
        String code2 = createRegionCascade();
        List<CascadeDictResult> results = cascadeService.batchQueryCascadeDict(
                List.of(new CascadeDictRequest(code1), new CascadeDictRequest(code2)));
        assertEquals(2, results.size());
        assertTrue(cascadeService.batchQueryCascadeDict(List.of()).isEmpty());
    }

    @Test
    @DisplayName("getCascadeStatistics / getCascadePerformanceMetrics expose metrics")
    void statisticsAndMetrics() {
        String code = createRegionCascade();

        Map<String, Object> stats = cascadeService.getCascadeStatistics(code);
        assertTrue(stats.containsKey("timestamp"));
        assertEquals(code, stats.get("dictCode"));

        Map<String, Object> metrics = cascadeService.getCascadePerformanceMetrics(code);
        assertTrue(metrics.containsKey("queryTime"));
        assertEquals(Boolean.TRUE, metrics.get("success"));
    }
}
