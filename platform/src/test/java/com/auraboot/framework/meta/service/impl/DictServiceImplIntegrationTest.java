package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.dto.DictCreateRequest;
import com.auraboot.framework.meta.dto.DictDTO;
import com.auraboot.framework.meta.dto.DictDataResult;
import com.auraboot.framework.meta.dto.DictImportResult;
import com.auraboot.framework.meta.dto.DictLoadRequest;
import com.auraboot.framework.meta.dto.DictQueryRequest;
import com.auraboot.framework.meta.dto.DictStatistics;
import com.auraboot.framework.meta.dto.DictUpdateRequest;
import com.auraboot.framework.meta.dto.DictValidationResult;
import com.auraboot.framework.meta.entity.Dict;
import com.auraboot.framework.meta.entity.DictItem;
import com.auraboot.framework.meta.mapper.DictItemMapper;
import com.auraboot.framework.meta.mapper.DictMapper;
import com.auraboot.framework.meta.service.DictService;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-stack integration test for {@link DictServiceImpl}.
 *
 * <p>Part of OSS coverage initiative #8/#9 (tracker:
 * {@code docs/backlog/2026-06-10-oss-coverage-to-80-tracker.md}) — lifts the
 * {@code meta/service/impl} command-pipeline / dictionary domain from mock-light
 * coverage by exercising the real service against the real shared database
 * (no mocked mappers / bridges, per AGENTS.md §2.2 seam discipline).
 *
 * <p>Uses the {@code integration-test} profile (shared Postgres on :5432). All test
 * data is created under a dedicated tenant with {@code covdict}-prefixed codes and
 * hard-deleted in {@link #tearDown()} to keep the shared DB clean and avoid the
 * {@code (tenant_id, code, version)} unique-constraint colliding across runs.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@DisplayName("DictServiceImpl Real-Stack Integration Test")
class DictServiceImplIntegrationTest {

    private static final String CODE_PREFIX = "covdict";
    /** Stable per-class-run nonce so codes are unique across re-runs (alnum only, LIKE-safe). */
    private static final String RUN = Long.toString(Math.abs(System.nanoTime()), 36);

    @Autowired
    private DictService dictService;
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
    private User testUser;
    private Tenant testTenant;

    private String uniqueCode(String label) {
        return CODE_PREFIX + RUN + "_" + seq.incrementAndGet() + "_" + label;
    }

    @BeforeEach
    void setUp() {
        String testEmail = "covdict-test@auraboot.com";
        testUser = userService.findByEmail(testEmail);
        if (testUser == null) {
            testUser = userService.signUp(testEmail, "test-password-123");
        }

        String testTenantName = "covdict-test-tenant";
        testTenant = tenantService.findByName(testTenantName);
        if (testTenant == null) {
            Tenant tenant = new Tenant();
            tenant.setPid(UniqueIdGenerator.generate());
            tenant.setName(testTenantName);
            tenant.setDisplayName("Dict Coverage Test Tenant");
            tenant.setStatus("active");
            tenant.setContactEmail("admin@covdict-test.com");
            tenant.setDescription("Test tenant for dict-domain coverage IT");
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
            log.warn("dict cleanup failed: {}", e.getMessage());
        } finally {
            MetaContext.clear();
        }
    }

    // ---------- factory helpers ----------

    private DictCreateRequest newRequest(String code, String name) {
        DictCreateRequest req = new DictCreateRequest();
        req.setCode(code);
        req.setName(name);
        req.setDescription("desc-" + name);
        req.setDictType("simple");
        req.setSourceType("static");
        return req;
    }

    private DictCreateRequest.DictItemCreateRequest item(String value, String label, int sort) {
        DictCreateRequest.DictItemCreateRequest it = new DictCreateRequest.DictItemCreateRequest();
        it.setValue(value);
        it.setLabel(label);
        it.setSortOrder(sort);
        return it;
    }

    // ==================== create / find ====================

    @Test
    @DisplayName("create persists dict (published, v1, current) and is retrievable by pid and code")
    void createAndFind() {
        String code = uniqueCode("basic");
        DictDTO dto = dictService.create(newRequest(code, "Basic Dict"));

        assertNotNull(dto.getPid());
        assertEquals(code, dto.getCode());
        assertEquals("Basic Dict", dto.getName());
        assertEquals("published", dto.getStatus());
        assertEquals(Integer.valueOf(1), dto.getVersion());
        assertEquals(Boolean.TRUE, dto.getIsCurrent());
        // DTO surfaces the frontend type ("simple"); the backend persists the mapped type ("dynamic")
        assertEquals("simple", dto.getDictType());
        assertEquals("dynamic", dictMapper.findByPid(dto.getPid()).getDictType());

        DictDTO byPid = dictService.findByPid(dto.getPid());
        assertNotNull(byPid);
        assertEquals(code, byPid.getCode());

        DictDTO byCode = dictService.findByCode(code);
        assertNotNull(byCode);
        assertEquals(dto.getPid(), byCode.getPid());
    }

    @Test
    @DisplayName("create with items persists dict_item rows")
    void createWithItems() {
        String code = uniqueCode("items");
        DictCreateRequest req = newRequest(code, "Item Dict");
        req.setItems(List.of(item("a", "Alpha", 1), item("b", "Beta", 2)));

        DictDTO dto = dictService.create(req);
        Dict entity = dictMapper.findByPid(dto.getPid());
        List<DictItem> items = dictItemMapper.findByDictId(entity.getId());
        assertEquals(2, items.size());
    }

    @Test
    @DisplayName("create rejects null / blank-field requests")
    void createValidation() {
        assertThrows(ValidationException.class, () -> dictService.create(null));

        DictCreateRequest blankCode = newRequest("", "x");
        assertThrows(ValidationException.class, () -> dictService.create(blankCode));

        DictCreateRequest blankName = newRequest(uniqueCode("bn"), "");
        assertThrows(ValidationException.class, () -> dictService.create(blankName));

        DictCreateRequest blankType = newRequest(uniqueCode("bt"), "x");
        blankType.setDictType("");
        assertThrows(ValidationException.class, () -> dictService.create(blankType));
    }

    @Test
    @DisplayName("create rejects duplicate code")
    void createDuplicateCode() {
        String code = uniqueCode("dup");
        dictService.create(newRequest(code, "First"));
        assertThrows(ValidationException.class, () -> dictService.create(newRequest(code, "Second")));
    }

    @Test
    @DisplayName("update mutates name/description")
    void update() {
        DictDTO dto = dictService.create(newRequest(uniqueCode("upd"), "Before"));
        DictUpdateRequest upd = new DictUpdateRequest();
        upd.setName("After");
        upd.setDescription("changed");

        DictDTO updated = dictService.update(dto.getPid(), upd);
        assertEquals("After", updated.getName());

        DictDTO reread = dictService.findByPid(dto.getPid());
        assertEquals("After", reread.getName());
    }

    @Test
    @DisplayName("findByCode / findByPid return null for missing or blank input")
    void findMissing() {
        assertNull(dictService.findByCode(uniqueCode("nope")));
        assertNull(dictService.findByCode(""));
        assertNull(dictService.findByPid("does-not-exist"));
        assertNull(dictService.findByPid(""));
    }

    // ==================== delete / lifecycle ====================

    @Test
    @DisplayName("delete of a PUBLISHED dict is rejected; after unpublish it soft-deletes")
    void deleteLifecycle() {
        DictDTO dto = dictService.create(newRequest(uniqueCode("del"), "ToDelete"));
        // published cannot be deleted
        assertThrows(ValidationException.class, () -> dictService.delete(dto.getPid()));

        dictService.unpublish(dto.getPid());
        dictService.delete(dto.getPid());

        Dict entity = dictMapper.findByPid(dto.getPid());
        assertEquals("disabled", entity.getStatus());
    }

    @Test
    @DisplayName("publish/unpublish status transitions")
    void publishUnpublishCycle() {
        DictDTO dto = dictService.create(newRequest(uniqueCode("pub"), "Pub"));

        // already published -> publish is a no-op returning current
        DictDTO republished = dictService.publish(dto.getPid(), "again");
        assertEquals("published", republished.getStatus());

        // unpublish -> deprecated, no longer current
        DictDTO unpublished = dictService.unpublish(dto.getPid());
        assertEquals("deprecated", unpublished.getStatus());
        assertNull(dictService.findByCode(dto.getCode()), "deprecated dict is no longer current-by-code");

        // publish from deprecated -> published + current again
        DictDTO repub = dictService.publish(dto.getPid(), "restore");
        assertEquals("published", repub.getStatus());
        assertEquals(Boolean.TRUE, repub.getIsCurrent());
    }

    @Test
    @DisplayName("unpublish of a non-published dict is rejected")
    void unpublishNonPublished() {
        DictDTO dto = dictService.create(newRequest(uniqueCode("unp"), "Unp"));
        dictService.unpublish(dto.getPid()); // -> deprecated
        assertThrows(ValidationException.class, () -> dictService.unpublish(dto.getPid()));
    }

    @Test
    @DisplayName("createVersion produces a draft v2 and version history lists both")
    void createVersionAndHistory() {
        DictDTO dto = dictService.create(newRequest(uniqueCode("ver"), "Versioned"));
        DictDTO v2 = dictService.createVersion(dto.getPid(), "second");

        assertEquals(Integer.valueOf(2), v2.getVersion());
        assertEquals("draft", v2.getStatus());

        List<DictDTO> history = dictService.getVersionHistory(dto.getCode());
        assertTrue(history.size() >= 2, "history should contain v1 and v2");
    }

    // ==================== query surfaces ====================

    @Test
    @DisplayName("findPage paginates and filters by code")
    void findPage() {
        String code = uniqueCode("page");
        dictService.create(newRequest(code, "Pageable"));

        DictQueryRequest req = new DictQueryRequest();
        req.setPageNum(1);
        req.setPageSize(10);
        req.setCode(code);
        Page<DictDTO> page = dictService.findPage(req);

        assertEquals(1L, page.getTotal());
        assertEquals(1, page.getRecords().size());
        assertEquals(code, page.getRecords().get(0).getCode());
    }

    @Test
    @DisplayName("findByTenant / findByStatus / findByType / search return created dict")
    void listingQueries() {
        String code = uniqueCode("list");
        DictDTO dto = dictService.create(newRequest(code, "Listed"));

        assertTrue(dictService.findByTenant().stream().anyMatch(d -> code.equals(d.getCode())));
        assertTrue(dictService.findByStatus("published").stream().anyMatch(d -> code.equals(d.getCode())));
        // "simple" was mapped to backend type "dynamic"
        assertTrue(dictService.findByType("dynamic").stream().anyMatch(d -> code.equals(d.getCode())));
        assertTrue(dictService.search("Listed").stream().anyMatch(d -> code.equals(d.getCode())));
        assertEquals(dto.getCode(), code);
    }

    @Test
    @DisplayName("findByStatus rejects invalid status; findByType rejects blank type")
    void invalidQueryArgs() {
        assertThrows(ValidationException.class, () -> dictService.findByStatus("not-a-status"));
        assertThrows(ValidationException.class, () -> dictService.findByType(""));
    }

    // ==================== statistics / validation ====================

    @Test
    @DisplayName("getStatistics reflects created dicts; isCodeUnique + validateConfig behave")
    void statisticsAndValidation() {
        String code = uniqueCode("stat");
        DictDTO dto = dictService.create(newRequest(code, "Stat"));

        DictStatistics stats = dictService.getStatistics();
        assertTrue(stats.getTotalCount() >= 1);
        assertTrue(stats.getPublishedCount() >= 1);
        assertNotNull(stats.getStatusDistribution());

        assertFalse(dictService.isCodeUnique(code, null), "existing code is not unique");
        assertTrue(dictService.isCodeUnique(uniqueCode("fresh"), null), "fresh code is unique");
        // excluding the owning dict makes its own code count as unique
        assertTrue(dictService.isCodeUnique(code, dto.getPid()));

        DictValidationResult result = dictService.validateConfig(dto.getPid());
        assertTrue(result.getValid(), "well-formed dict validates clean");
    }

    // ==================== batch ====================

    @Test
    @DisplayName("batchCreate / batchUpdateStatus / batchDelete")
    void batchOps() {
        String c1 = uniqueCode("b1");
        String c2 = uniqueCode("b2");
        List<DictDTO> created = dictService.batchCreate(
                List.of(newRequest(c1, "B1"), newRequest(c2, "B2")));
        assertEquals(2, created.size());

        List<String> pids = new ArrayList<>();
        created.forEach(d -> pids.add(d.getPid()));

        int moved = dictService.batchUpdateStatus(pids, "deprecated");
        assertEquals(2, moved);
        assertEquals("deprecated", dictMapper.findByPid(pids.get(0)).getStatus());

        int deleted = dictService.batchDelete(pids);
        assertEquals(2, deleted);
        assertEquals("disabled", dictMapper.findByPid(pids.get(0)).getStatus());
    }

    @Test
    @DisplayName("batchUpdateStatus with an invalid status returns 0")
    void batchUpdateInvalidStatus() {
        DictDTO dto = dictService.create(newRequest(uniqueCode("bis"), "Bis"));
        assertEquals(0, dictService.batchUpdateStatus(List.of(dto.getPid()), "bogus"));
    }

    // ==================== import / export ====================

    @Test
    @DisplayName("importDicts imports new and skips duplicates; exportDicts returns by code")
    void importExport() {
        String existing = uniqueCode("imp-exist");
        dictService.create(newRequest(existing, "Existing"));

        String fresh = uniqueCode("imp-fresh");
        DictImportResult result = dictService.importDicts(
                List.of(newRequest(fresh, "Fresh"), newRequest(existing, "Dup")));

        assertEquals(1, result.getSuccessItems().size());
        assertEquals(1, result.getSkipItems().size());

        List<DictDTO> exported = dictService.exportDicts(List.of(existing, fresh));
        assertEquals(2, exported.size());
    }

    // ==================== item management ====================

    @Test
    @DisplayName("replaceItems swaps the item set; markItemsAsPluginSource + replacePluginItems preserve user items")
    void itemManagement() {
        String code = uniqueCode("itemmgmt");
        DictCreateRequest req = newRequest(code, "ItemMgmt");
        req.setDictType("tree");
        req.setItems(List.of(item("u1", "User1", 1)));
        DictDTO dto = dictService.create(req);
        Long dictId = dictMapper.findByPid(dto.getPid()).getId();

        // replaceItems wipes and re-creates
        dictService.replaceItems(dto.getPid(), List.of(item("x", "X", 1), item("y", "Y", 2)));
        assertEquals(2, dictItemMapper.findByDictId(dictId).size());

        // mark all current items as plugin-sourced, then add a user item
        dictService.markItemsAsPluginSource(dto.getPid());
        DictItem userItem = new DictItem();
        userItem.setPid(UniqueIdGenerator.generate());
        userItem.setTenantId(testTenant.getId());
        userItem.setDictId(dictId);
        userItem.setValue("usr");
        userItem.setLabel("Usr");
        userItem.setSortNo(3);
        userItem.setStatus("enabled");
        userItem.setSource("user");
        userItem.setCreatedAt(Instant.now());
        userItem.setUpdatedAt(Instant.now());
        dictItemMapper.insert(userItem);

        // replacePluginItems replaces only plugin-sourced rows, keeping the user row
        dictService.replacePluginItems(dto.getPid(), List.of(item("p", "P", 1)));
        List<DictItem> after = dictItemMapper.findByDictId(dictId);
        assertTrue(after.stream().anyMatch(i -> "usr".equals(i.getValue())), "user item preserved");
        assertTrue(after.stream().anyMatch(i -> "p".equals(i.getValue())), "new plugin item present");
    }

    @Test
    @DisplayName("loadDictData / batchLoadDictData return data for a dynamic dict")
    void loadDictData() {
        String code = uniqueCode("load");
        DictCreateRequest req = newRequest(code, "Loadable");
        req.setItems(List.of(item("k1", "L1", 1), item("k2", "L2", 2)));
        dictService.create(req);

        DictDataResult single = dictService.loadDictData(code, "latest", null);
        assertNotNull(single);
        assertEquals(code, single.getCode());

        DictLoadRequest lr = new DictLoadRequest();
        lr.setCode(code);
        lr.setVersionStrategy("latest");
        List<DictDataResult> batch = dictService.batchLoadDictData(List.of(lr));
        assertEquals(1, batch.size());
    }
}
