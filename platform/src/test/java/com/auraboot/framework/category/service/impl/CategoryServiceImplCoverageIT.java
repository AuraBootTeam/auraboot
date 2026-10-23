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

    @Test
    void promotingChildToRootClearsParentAndRefreshesDescendantPlacement() {
        Category parent = root("move_parent_" + seq.incrementAndGet()); parent.setCategoryType("commerce_product");
        categoryService.createCategory(parent);
        Category child = root("move_child_" + seq.incrementAndGet()); child.setCategoryType("commerce_product"); child.setParentId(parent.getId());
        categoryService.createCategory(child);
        Category leaf = root("move_leaf_" + seq.incrementAndGet()); leaf.setCategoryType("commerce_product"); leaf.setParentId(child.getId());
        categoryService.createCategory(leaf);
        assertTrue(categoryService.moveCategory(child.getId(), null));
        Category promoted = categoryService.getById(child.getId());
        org.junit.jupiter.api.Assertions.assertNull(promoted.getParentId());
        assertEquals(1, promoted.getLevel()); assertEquals("/" + child.getPid(), promoted.getMaterializedPath());
        Category movedLeaf = categoryService.getById(leaf.getId());
        assertEquals(child.getId(), movedLeaf.getParentId()); assertEquals(2, movedLeaf.getLevel());
        assertEquals("/" + child.getPid() + "/" + leaf.getPid(), movedLeaf.getMaterializedPath());
        // Generic updates must not accidentally promote a node when a partial DTO omits its parent.
        Category patch = new Category(); patch.setId(leaf.getId()); patch.setTenantId(TENANT_ID);
        patch.setCode(leaf.getCode()); patch.setName("Partial rename");
        categoryService.updateCategory(patch);
        assertEquals(child.getId(), categoryService.getById(leaf.getId()).getParentId());
    }

    @Test
    void nativeJsonCategoryFieldsRoundTripThroughInsertAndUpdate() {
        Category node = root("json_" + seq.incrementAndGet());
        node.setI18nName("{\"en\":\"Tops\",\"zh-CN\":\"上装\"}");
        node.setExternalTaxonomy("{\"provider\":\"external-123\"}"); node.setExtra("{\"featured\":true}");
        categoryService.createCategory(node);
        Category stored = categoryService.getById(node.getId());
        assertTrue(stored.getI18nName().contains("Tops")); assertTrue(stored.getExternalTaxonomy().contains("external-123"));
        assertTrue(stored.getExtra().contains("true"));
        stored.setI18nName("{\"en\":\"New tops\"}"); categoryService.updateCategory(stored);
        assertTrue(categoryService.getById(node.getId()).getI18nName().contains("New tops"));
        assertEquals("jsonb", jdbcTemplate.queryForObject("SELECT pg_typeof(i18n_name)::text FROM ab_category WHERE id=?", String.class, node.getId()));
    }

}
