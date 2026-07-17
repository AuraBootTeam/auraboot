package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.dto.DictDTO;
import com.auraboot.framework.meta.dto.DictDataResult;
import com.auraboot.framework.meta.dto.DictCreateRequest;
import com.auraboot.framework.meta.entity.Dict;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.meta.mapper.DictMapper;
import com.auraboot.framework.meta.service.DictService;
import com.auraboot.framework.meta.service.DictVersionService;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Real-stack test for dict cache invalidation.
 *
 * <p>{@code DictVersionServiceImpl#loadDictByStrategy} is {@code @Cacheable("dictData")}
 * behind a 30-minute in-process Caffeine cache, and nothing in production ever evicted it:
 * {@code clearDictCache} / {@code clearAllDictCache} existed but had zero callers. So a
 * dict edit — through the admin CRUD path or through a plugin import — updated the database
 * and then went unnoticed by readers until the backend restarted or the TTL lapsed.
 *
 * <p>Observed on a live stack before the fix: after importing a changed
 * {@code pm_page_kind}, psql showed {@code extra = {"color": "gray"}} while
 * {@code GET /api/meta/dict/by-code/pm_page_kind/data} kept returning {@code blue}.
 *
 * <p>Each test primes the cache with a read, mutates the dict, and reads again — the second
 * read must observe the mutation.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@DisplayName("Dict cache eviction — a dict edit must be visible to readers")
class DictCacheEvictionIntegrationTest {

    private static final String CODE_PREFIX = "dictevict";
    private static final String RUN = Long.toString(Math.abs(System.nanoTime()), 36);

    @Autowired
    private DictService dictService;
    @Autowired
    private DictVersionService versionService;
    @Autowired
    private DictMapper dictMapper;
    @Autowired
    private UserService userService;
    @Autowired
    private TenantService tenantService;
    @Autowired
    private TenantMemberService tenantMemberService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // Same fixture shape as DictVersionServiceImplIntegrationTest — self-provisioning,
        // so the test does not depend on what the integration-test profile happens to seed.
        String testEmail = "dictevict-test@auraboot.com";
        User testUser = userService.findByEmail(testEmail);
        if (testUser == null) {
            testUser = userService.signUp(testEmail, "test-password-123");
        }

        String testTenantName = "dictevict-test-tenant";
        Tenant testTenant = tenantService.findByName(testTenantName);
        if (testTenant == null) {
            Tenant tenant = new Tenant();
            tenant.setPid(UniqueIdGenerator.generate());
            tenant.setName(testTenantName);
            tenant.setDisplayName("Dict Cache Eviction Test Tenant");
            tenant.setStatus("active");
            tenant.setContactEmail("admin@dictevict-test.com");
            tenant.setDescription("Test tenant for dict cache eviction IT");
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
            List<Dict> dicts = dictMapper.selectList(
                    new LambdaQueryWrapper<Dict>().likeRight(Dict::getCode, CODE_PREFIX));
            for (Dict dict : dicts) {
                dictService.delete(dict.getPid());
            }
        } catch (RuntimeException e) {
            log.warn("dict cleanup failed: {}", e.getMessage());
        } finally {
            MetaContext.clear();
        }
    }

    private DictCreateRequest.DictItemCreateRequest item(String colour) {
        DictCreateRequest.DictItemCreateRequest item = new DictCreateRequest.DictItemCreateRequest();
        item.setValue("list");
        item.setLabel("List");
        item.setSortOrder(10);
        item.setExtension(objectMapper.createObjectNode().put("color", colour));
        return item;
    }

    private String seedDict(String suffix, String colour) {
        DictCreateRequest request = new DictCreateRequest();
        request.setCode(CODE_PREFIX + suffix + RUN);
        request.setName("Dict cache eviction " + suffix);
        request.setDictType("static");
        request.setItems(List.of(item(colour)));

        DictDTO created = dictService.create(request);
        assertNotNull(created);
        return created.getPid();
    }

    private String readColour(String code) {
        DictDataResult result = versionService.loadDictByStrategy(code, "latest", null);
        assertNotNull(result);
        assertEquals(1, result.getItems().size(), "seed declares exactly one item");
        // DictItemData#extension is declared as Object — go through Jackson rather than casting.
        JsonNode extension = objectMapper.valueToTree(result.getItems().get(0).getExtension());
        assertNotNull(extension.get("color"), "seed always writes an extension.color");
        return extension.get("color").asText();
    }

    @Test
    @DisplayName("replaceItems: a colour change is visible on the next read, not 30 minutes later")
    void replaceItemsEvictsDictDataCache() {
        String pid = seedDict("crud", "blue");
        String code = CODE_PREFIX + "crud" + RUN;

        // Prime the cache — this is what a page render does before the admin edits anything.
        assertEquals("blue", readColour(code));

        dictService.replaceItems(pid, List.of(item("gray")));

        // Before the fix this returned "blue": the row was gray in the database, but the
        // reader was still being served the cached projection.
        assertEquals("gray", readColour(code),
                "dict items were replaced; readers must not keep seeing the stale colour");
    }

    @Test
    @DisplayName("replacePluginItems (the plugin-import path): a colour change is visible immediately")
    void replacePluginItemsEvictsDictDataCache() {
        // The exact path a plugin import takes (PluginResourceImporterImpl#importDict →
        // dictService.replacePluginItems). A *different* method from the admin CRUD path
        // above, so it needs its own eviction and its own test.
        //
        // The dict is seeded with no items: replacePluginItems only deletes PLUGIN-sourced
        // rows (USER-sourced ones survive by design), so seeding a user item and then
        // pushing a plugin item with the same value collides on the unique key.
        String code = CODE_PREFIX + "plugin" + RUN;
        DictCreateRequest request = new DictCreateRequest();
        request.setCode(code);
        request.setName("Dict cache eviction plugin");
        request.setDictType("static");
        DictDTO created = dictService.create(request);
        assertNotNull(created);

        dictService.replacePluginItems(created.getPid(), List.of(item("green")));
        assertEquals("green", readColour(code));

        dictService.replacePluginItems(created.getPid(), List.of(item("gray")));

        assertEquals("gray", readColour(code),
                "a plugin import replaced the items; readers must not keep seeing the stale colour");
    }
}
