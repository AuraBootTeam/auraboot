package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.dto.DictDataResult;
import com.auraboot.framework.meta.dto.DictLoadRequest;
import com.auraboot.framework.meta.dto.DictVersionInfo;
import com.auraboot.framework.meta.entity.Dict;
import com.auraboot.framework.meta.entity.DictItem;
import com.auraboot.framework.meta.mapper.DictItemMapper;
import com.auraboot.framework.meta.mapper.DictMapper;
import com.auraboot.framework.meta.service.DictVersionService;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-stack integration test for {@link DictVersionServiceImpl}.
 *
 * <p>Part of OSS coverage initiative #8/#9 (tracker:
 * {@code docs/backlog/2026-06-10-oss-coverage-to-80-tracker.md}). Existing unit tests
 * (Mockito) cover the static/dynamic load paths; this exercises the DB-backed version
 * surface — strategy resolution (latest/pinned), version info/history, current-version
 * switching, compatibility, cache, stats — against real multi-version dict rows.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@DisplayName("DictVersionServiceImpl Real-Stack Integration Test")
class DictVersionServiceImplIntegrationTest {

    private static final String CODE_PREFIX = "covdictver";
    private static final String RUN = Long.toString(Math.abs(System.nanoTime()), 36);

    @Autowired
    private DictVersionService versionService;
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
        String testEmail = "covdictver-test@auraboot.com";
        User testUser = userService.findByEmail(testEmail);
        if (testUser == null) {
            testUser = userService.signUp(testEmail, "test-password-123");
        }

        String testTenantName = "covdictver-test-tenant";
        testTenant = tenantService.findByName(testTenantName);
        if (testTenant == null) {
            Tenant tenant = new Tenant();
            tenant.setPid(UniqueIdGenerator.generate());
            tenant.setName(testTenantName);
            tenant.setDisplayName("Dict Version Coverage Test Tenant");
            tenant.setStatus("active");
            tenant.setContactEmail("admin@covdictver-test.com");
            tenant.setDescription("Test tenant for dict-version coverage IT");
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
            log.warn("dict-version cleanup failed: {}", e.getMessage());
        } finally {
            MetaContext.clear();
        }
    }

    // ---------- data setup ----------

    private Dict insertDict(String code, int version, boolean current, String type, String status) {
        Dict d = new Dict();
        d.setPid(UniqueIdGenerator.generate());
        d.setTenantId(testTenant.getId());
        d.setCode(code);
        d.setName("Ver " + code);
        d.setDictType(type);
        d.setStatus(status);
        d.setVersion(version);
        d.setSemver("1.0." + version);
        d.setIsCurrent(current);
        d.setCreatedAt(Instant.now());
        d.setUpdatedAt(Instant.now());
        dictMapper.insert(d);
        return d;
    }

    private void addItems(Long dictId, int count) {
        for (int i = 1; i <= count; i++) {
            DictItem item = new DictItem();
            item.setPid(UniqueIdGenerator.generate());
            item.setTenantId(testTenant.getId());
            item.setDictId(dictId);
            item.setValue("v" + i);
            item.setLabel("Label " + i);
            item.setSortNo(i);
            item.setStatus("enabled");
            item.setSource("user");
            item.setCreatedAt(Instant.now());
            item.setUpdatedAt(Instant.now());
            dictItemMapper.insert(item);
        }
    }

    /** Two versions of the same code: v1 (current) with 2 items, v2 (not current) with 3 items. */
    private String createVersionedDict() {
        String code = uniqueCode("multi");
        Dict v1 = insertDict(code, 1, true, "dynamic", "published");
        addItems(v1.getId(), 2);
        Dict v2 = insertDict(code, 2, false, "dynamic", "published");
        addItems(v2.getId(), 3);
        return code;
    }

    // ==================== strategy loading ====================

    @Test
    @DisplayName("loadDictByStrategy resolves latest (current) and pinned (specific) versions")
    void loadByStrategy() {
        String code = createVersionedDict();

        DictDataResult latest = versionService.loadDictByStrategy(code, "latest", null);
        assertTrue(latest.getSuccess());
        assertEquals(2, latest.getItems().size(), "latest -> v1 has 2 items");

        DictDataResult pinned = versionService.loadDictByStrategy(code, "pinned", "2");
        assertTrue(pinned.getSuccess());
        assertEquals(3, pinned.getItems().size(), "pinned v2 has 3 items");
    }

    @Test
    @DisplayName("loadDictByStrategy error branches: bad strategy, missing pinned, unknown code/version")
    void loadByStrategyErrors() {
        String code = createVersionedDict();

        assertFalse(versionService.loadDictByStrategy(code, "bogus", null).getSuccess());
        assertFalse(versionService.loadDictByStrategy(code, "pinned", null).getSuccess());
        assertFalse(versionService.loadDictByStrategy(code, "pinned", "abc").getSuccess());
        assertFalse(versionService.loadDictByStrategy(code, "pinned", "999").getSuccess());
        assertFalse(versionService.loadDictByStrategy(uniqueCode("ghost"), "latest", null).getSuccess());
    }

    @Test
    @DisplayName("batchLoadDictByStrategy returns one result per request")
    void batchLoad() {
        String code = createVersionedDict();
        DictLoadRequest r = new DictLoadRequest();
        r.setCode(code);
        r.setVersionStrategy("latest");
        List<DictDataResult> results = versionService.batchLoadDictByStrategy(List.of(r));
        assertEquals(1, results.size());
        assertTrue(results.get(0).getSuccess());
    }

    // ==================== version info ====================

    @Test
    @DisplayName("getDictVersionInfo + getAvailableVersions report both versions")
    void versionInfo() {
        String code = createVersionedDict();

        DictVersionInfo info = versionService.getDictVersionInfo(code);
        assertEquals(Integer.valueOf(2), info.getTotalVersions());
        assertEquals(Integer.valueOf(1), info.getCurrentVersion());
        assertEquals(Integer.valueOf(2), info.getLatestVersion());
        assertEquals(2, info.getVersionHistory().size());

        assertEquals(2, versionService.getAvailableVersions(code).size());
    }

    @Test
    @DisplayName("getDictVersionInfo throws for an unknown code")
    void versionInfoMissing() {
        assertThrows(ValidationException.class, () -> versionService.getDictVersionInfo(uniqueCode("ghost")));
    }

    // ==================== entity-level loaders ====================

    @Test
    @DisplayName("loadUnifiedDictData / loadDynamicDictData / loadCascadeDictData load items from a dict entity")
    void entityLoaders() {
        String code = createVersionedDict();
        Dict current = versionService.getCurrentVersion(code);

        assertTrue(versionService.loadUnifiedDictData(current).getSuccess());
        assertEquals(2, versionService.loadDynamicDictData(current).getItems().size());

        DictDataResult roots = versionService.loadCascadeDictData(current, null);
        assertTrue(roots.getSuccess());
        assertEquals(2, roots.getItems().size(), "flat items are all top-level");
        assertTrue(versionService.loadCascadeDictData(current, "no-such-parent").getItems().isEmpty());
    }

    @Test
    @DisplayName("loadStaticDictData rejects non-static type and static-without-items")
    void staticLoaderErrors() {
        Dict dynamic = insertDict(uniqueCode("dyn"), 1, true, "dynamic", "published");
        assertFalse(versionService.loadStaticDictData(dynamic).getSuccess(), "non-static type rejected");

        Dict staticNoItems = insertDict(uniqueCode("static"), 1, true, "static", "published");
        assertFalse(versionService.loadStaticDictData(staticNoItems).getSuccess(), "static without items rejected");
    }

    // ==================== switching / compatibility / existence ====================

    @Test
    @DisplayName("switchCurrentVersion moves the current flag; unknown target returns false")
    void switchVersion() {
        String code = createVersionedDict();

        assertTrue(versionService.switchCurrentVersion(code, 2));
        assertEquals(Integer.valueOf(2), versionService.getCurrentVersion(code).getVersion());

        assertFalse(versionService.switchCurrentVersion(code, 999));
    }

    @Test
    @DisplayName("getSpecificVersion / isVersionExists / getVersionCompatibility")
    void specificAndCompatibility() {
        String code = createVersionedDict();

        assertEquals(Integer.valueOf(1), versionService.getSpecificVersion(code, 1).getVersion());
        assertTrue(versionService.isVersionExists(code, 1));
        assertFalse(versionService.isVersionExists(code, 999));

        assertEquals(Boolean.TRUE, versionService.getVersionCompatibility(code, 1, 2).get("compatible"));
        assertEquals(Boolean.FALSE, versionService.getVersionCompatibility(code, 1, 999).get("compatible"));
    }

    @Test
    @DisplayName("validateVersionStrategy covers latest/pinned/blank/non-numeric")
    void validateStrategy() {
        assertTrue(versionService.validateVersionStrategy("latest", null));
        assertTrue(versionService.validateVersionStrategy("pinned", "3"));
        assertFalse(versionService.validateVersionStrategy("pinned", null));
        assertFalse(versionService.validateVersionStrategy("pinned", "x"));
        assertFalse(versionService.validateVersionStrategy("", null));
        assertFalse(versionService.validateVersionStrategy("bogus", null));
    }

    // ==================== cache / stats ====================

    @Test
    @DisplayName("warmup / clear cache and stats accessors execute without error")
    void cacheAndStats() {
        String code = createVersionedDict();

        assertDoesNotThrow(() -> versionService.warmupDictCache(List.of(code)));
        assertDoesNotThrow(() -> versionService.clearDictCache(code));
        assertDoesNotThrow(() -> versionService.clearAllDictCache());

        assertTrue(versionService.getVersionStrategyStats().containsKey("latest"));
        assertTrue(versionService.getDictLoadPerformanceStats().containsKey("avgLoadTime"));
    }
}
