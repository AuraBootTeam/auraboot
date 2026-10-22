package com.auraboot.framework.category.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.category.entity.Category;
import com.auraboot.framework.category.service.CategoryService;
import com.auraboot.framework.exception.BusinessException;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-stack coverage IT for {@link CategoryServiceImpl} — root-category CRUD (create with
 * unique-code guard, update, findByPid/Code, root/type/paged queries, enable/disable). Dedicated
 * synthetic tenant; raw teardown.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("CategoryServiceImpl Coverage IT — category CRUD")
class CategoryServiceImplCoverageIT {

    private static final long TENANT_ID = 991_200_001L;
    private final AtomicLong seq = new AtomicLong();

    @Autowired
    private CategoryService categoryService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, 991_200_002L, "cat-test-pid", "cat-test-user");
    }

    @AfterAll
    void cleanup() {
        try {
            jdbcTemplate.update("DELETE FROM ab_category WHERE tenant_id = ?", TENANT_ID);
        } finally {
            MetaContext.clear();
        }
    }

    private Category root(String code) {
        Category c = new Category();
        c.setTenantId(TENANT_ID);
        c.setCode(code);
        c.setName("Category " + code);
        c.setCategoryType("product");
        c.setStatus("ACTIVE");
        c.setSortOrder(0);
        return c;
    }

    @Test
    @DisplayName("create -> findByPid -> update -> queries -> enable/disable")
    void crud() {
        String code = "cat_" + seq.incrementAndGet();
        Category created = categoryService.createCategory(root(code));
        assertNotNull(created.getId());
        assertNotNull(created.getPid());

        assertEquals(created.getPid(), categoryService.findByPid(created.getPid()).getPid());
        assertEquals(code, categoryService.findByCodeAndTenantId(code, TENANT_ID).getCode());

        created.setName("renamed category");
        Category updated = categoryService.updateCategory(created);
        assertEquals("renamed category", updated.getName());

        List<Category> roots = categoryService.findRootCategoriesByTenantId(TENANT_ID);
        assertTrue(roots.stream().anyMatch(c -> c.getPid().equals(created.getPid())));
        assertFalse(categoryService.findByTenantIdAndType(TENANT_ID, "product").isEmpty());

        Page<Category> page = categoryService.findCategories(TENANT_ID, 1, 10, code, "product", "ACTIVE");
        assertTrue(page.getRecords().stream().anyMatch(c -> c.getPid().equals(created.getPid())));

        assertTrue(categoryService.disableCategory(created.getId()));
        assertTrue(categoryService.enableCategory(created.getId()));
    }

    @Test
    @DisplayName("create rejects a duplicate code within the tenant")
    void duplicateCodeRejected() {
        String code = "cat_dup_" + seq.incrementAndGet();
        categoryService.createCategory(root(code));
        assertThrows(BusinessException.class, () -> categoryService.createCategory(root(code)));
    }
}
