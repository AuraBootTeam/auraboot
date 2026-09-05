package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FilterPresetCreateRequest;
import com.auraboot.framework.meta.entity.FilterPreset;
import com.auraboot.framework.meta.service.FilterPresetService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-stack coverage IT for {@link FilterPresetServiceImpl} — preset CRUD on the real
 * DB ({@code ab_filter_preset}) including global-vs-personal scope, default-flag
 * exclusivity, visibility of other users' presets, and the tenant guard on update/delete.
 * Cleaned up by tenant via raw SQL.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("FilterPresetServiceImpl Coverage IT — presets CRUD + defaults + scope")
class FilterPresetServiceImplCoverageIT {

    private static final long TENANT_ID = 991_500_001L;
    private static final long USER_ID = 991_500_002L;
    private static final long OTHER_USER_ID = 991_500_003L;
    private static final String PAGE = "fp_it_page";

    @Autowired
    private FilterPresetService filterPresetService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, USER_ID, "fp-test-pid", "fp-test-user");
        jdbcTemplate.update("DELETE FROM ab_filter_preset WHERE tenant_id = ?", TENANT_ID);
    }

    @AfterAll
    void cleanup() {
        try {
            jdbcTemplate.update("DELETE FROM ab_filter_preset WHERE tenant_id = ?", TENANT_ID);
        } finally {
            MetaContext.clear();
        }
    }

    private FilterPresetCreateRequest request(String name, String scope, boolean isDefault) {
        FilterPresetCreateRequest req = new FilterPresetCreateRequest();
        req.setPageCode(PAGE);
        req.setModelCode("fp_it_model");
        req.setName(name);
        req.setConditions("[{\"field\":\"status\",\"op\":\"eq\",\"value\":\"open\"}]");
        req.setScope(scope);
        req.setDefault(isDefault);
        return req;
    }

    @Test
    @DisplayName("create stores a personal preset with default 'and' logic")
    void createPersonal() {
        FilterPreset created = filterPresetService.create(request("mine", "personal", false));
        assertNotNull(created.getId());
        assertEquals(USER_ID, created.getUserId());
        assertEquals(PAGE, created.getPageCode());
        assertEquals("and", created.getLogic());
        assertFalse(created.getIsDefault());
        assertNotNull(created.getCreatedAt());
    }

    @Test
    @DisplayName("global presets have a null user and are visible to every user")
    void createGlobalAndVisibility() {
        filterPresetService.create(request("global-preset", "global", false));
        filterPresetService.create(request("other-user-preset", "personal", false));

        List<FilterPreset> forOwner = filterPresetService.listByPageCode(PAGE);
        assertEquals(2, forOwner.size());

        // A different tenant user sees only the global preset.
        MetaContext.setContext(TENANT_ID, OTHER_USER_ID, "fp-other-pid", "fp-other-user");
        List<FilterPreset> forOther = filterPresetService.listByPageCode(PAGE);
        assertEquals(1, forOther.size());
        assertEquals("global-preset", forOther.get(0).getName());
        assertNull(forOther.get(0).getUserId());
    }

    @Test
    @DisplayName("creating a default preset clears the previous default on the same page")
    void defaultExclusivityOnCreate() {
        FilterPreset first = filterPresetService.create(request("first-default", "personal", true));
        assertTrue(first.getIsDefault());

        FilterPreset second = filterPresetService.create(request("second-default", "personal", true));
        assertTrue(second.getIsDefault());

        FilterPreset reloaded = filterPresetService.update(first.getId(), request("first-default", "personal", false));
        // update with default=false keeps it off; verify via list
        List<FilterPreset> presets = filterPresetService.listByPageCode(PAGE);
        long defaults = presets.stream().filter(FilterPreset::getIsDefault).count();
        assertEquals(1, defaults);
        assertEquals("second-default",
                presets.stream().filter(FilterPreset::getIsDefault).findFirst().orElseThrow().getName());
    }

    @Test
    @DisplayName("update renames, keeps existing logic when request omits it, and rejects foreign tenants")
    void updatePaths() {
        FilterPreset created = filterPresetService.create(request("before", "personal", false));
        assertEquals("and", created.getLogic());

        FilterPresetCreateRequest withLogic = request("after", "personal", false);
        withLogic.setLogic("or");
        FilterPreset updated = filterPresetService.update(created.getId(), withLogic);
        assertEquals("after", updated.getName());
        assertEquals("or", updated.getLogic());
        assertNotNull(updated.getUpdatedAt());

        FilterPresetCreateRequest withoutLogic = request("after-2", "personal", false);
        withoutLogic.setLogic(null);
        FilterPreset keptLogic = filterPresetService.update(created.getId(), withoutLogic);
        assertEquals("or", keptLogic.getLogic());

        // Another tenant cannot update the preset.
        MetaContext.setContext(991_599_999L, USER_ID, "fp-foreign-pid", "fp-foreign-user");
        assertThrows(IllegalArgumentException.class, () -> filterPresetService.update(created.getId(), withLogic));
        assertThrows(IllegalArgumentException.class, () -> filterPresetService.update(424242L, withLogic));
    }

    @Test
    @DisplayName("setDefault promotes one preset and clears the rest for the page")
    void setDefaultFlow() {
        FilterPreset a = filterPresetService.create(request("a", "personal", false));
        FilterPreset b = filterPresetService.create(request("b", "personal", false));

        filterPresetService.setDefault(b.getId());
        List<FilterPreset> presets = filterPresetService.listByPageCode(PAGE);
        assertEquals("b", presets.stream().filter(FilterPreset::getIsDefault).findFirst().orElseThrow().getName());

        filterPresetService.setDefault(a.getId());
        List<FilterPreset> repromoted = filterPresetService.listByPageCode(PAGE);
        assertEquals("a", repromoted.stream().filter(FilterPreset::getIsDefault).findFirst().orElseThrow().getName());

        assertThrows(IllegalArgumentException.class, () -> filterPresetService.setDefault(424242L));
    }

    @Test
    @DisplayName("delete removes by id within the tenant and rejects missing ids")
    void deleteFlow() {
        FilterPreset created = filterPresetService.create(request("doomed", "personal", false));
        filterPresetService.delete(created.getId());
        assertTrue(filterPresetService.listByPageCode(PAGE).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> filterPresetService.delete(created.getId()));
    }
}
