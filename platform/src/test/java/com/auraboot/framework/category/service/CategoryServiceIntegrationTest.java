package com.auraboot.framework.category.service;

import com.auraboot.framework.category.entity.Category;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * CategoryService integration tests.
 *
 * <p>Covers:
 * <ul>
 *   <li>C1-01 to C1-04: root category CRUD</li>
 *   <li>C2-01 to C2-04: child category creation and hierarchy</li>
 *   <li>C3-01 to C3-03: duplicate code rejection, depth limit</li>
 *   <li>C4-01 to C4-03: enable/disable/delete lifecycle</li>
 *   <li>C5-01 to C5-03: tree query, pagination, type filter</li>
 *   <li>C6-01 to C6-02: move category, sort order</li>
 * </ul>
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CategoryServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private CategoryService categoryService;

    private final String runId = String.valueOf(System.currentTimeMillis());
    private Long rootCategoryId;
    private Long childCategoryId;

    // ==================== C1: root category CRUD ====================

    @Test
    @Order(1)
    @DisplayName("C1-01: createCategory persists root category with correct fields")
    void createCategory_rootCategory_persists() {
        Category cat = buildCategory("ROOT-" + runId, "Root Category " + runId, null, "product");

        Category saved = categoryService.createCategory(cat);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getPid()).isNotBlank();
        assertThat(saved.getLevel()).isEqualTo(1);
        assertThat(saved.isLeaf()).isTrue();
        assertThat(saved.getStatus()).isEqualTo("active");
        rootCategoryId = saved.getId();
        log.info("C1-01: created root category id={}", rootCategoryId);
    }

    @Test
    @Order(2)
    @DisplayName("C1-02: findByCodeAndTenantId returns the created root category")
    void findByCodeAndTenantId_returnsRootCategory() {
        assertThat(rootCategoryId).as("rootCategoryId must be set by C1-01").isNotNull();

        Category found = categoryService.findByCodeAndTenantId(
                "ROOT-" + runId, getTestTenant().getId());

        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(rootCategoryId);
    }

    @Test
    @Order(3)
    @DisplayName("C1-03: findByPid returns the root category")
    void findByPid_returnsRootCategory() {
        assertThat(rootCategoryId).as("rootCategoryId must be set by C1-01").isNotNull();
        Category root = categoryService.getById(rootCategoryId);

        Category found = categoryService.findByPid(root.getPid());

        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(rootCategoryId);
    }

    @Test
    @Order(4)
    @DisplayName("C1-04: updateCategory changes name")
    void updateCategory_changesName() {
        assertThat(rootCategoryId).as("rootCategoryId must be set by C1-01").isNotNull();
        Category existing = categoryService.getById(rootCategoryId);
        existing.setName("Updated Root " + runId);

        Category updated = categoryService.updateCategory(existing);

        assertThat(updated.getName()).isEqualTo("Updated Root " + runId);
    }

    // ==================== C2: child category ====================

    @Test
    @Order(10)
    @DisplayName("C2-01: createCategory with parentId creates level-2 child")
    void createCategory_child_levelTwo() {
        assertThat(rootCategoryId).as("rootCategoryId must be set by C1-01").isNotNull();

        Category child = buildCategory("CHILD-" + runId, "Child Category " + runId, rootCategoryId, "product");

        Category saved = categoryService.createCategory(child);

        assertThat(saved.getLevel()).isEqualTo(2);
        assertThat(saved.getParentId()).isEqualTo(rootCategoryId);
        childCategoryId = saved.getId();
        log.info("C2-01: created child category id={}", childCategoryId);
    }

    @Test
    @Order(11)
    @DisplayName("C2-02: root category isLeaf becomes false after child is added")
    void createCategory_child_parentIsNoLongerLeaf() {
        assertThat(childCategoryId).as("childCategoryId must be set by C2-01").isNotNull();

        Category root = categoryService.getById(rootCategoryId);

        assertThat(root.isLeaf()).isFalse();
    }

    @Test
    @Order(12)
    @DisplayName("C2-03: findByParentId returns the child")
    void findByParentId_returnsChild() {
        assertThat(childCategoryId).as("childCategoryId must be set by C2-01").isNotNull();

        List<Category> children = categoryService.findByParentId(rootCategoryId);

        assertThat(children).isNotEmpty();
        boolean found = children.stream().anyMatch(c -> c.getId().equals(childCategoryId));
        assertThat(found).isTrue();
    }

    @Test
    @Order(13)
    @DisplayName("C2-04: findRootCategoriesByTenantId does not include child")
    void findRootCategoriesByTenantId_excludesChild() {
        assertThat(rootCategoryId).as("rootCategoryId must be set by C1-01").isNotNull();

        List<Category> roots = categoryService.findRootCategoriesByTenantId(getTestTenant().getId());

        boolean rootPresent = roots.stream().anyMatch(c -> c.getId().equals(rootCategoryId));
        assertThat(rootPresent).isTrue();

        boolean childPresent = roots.stream().anyMatch(c -> c.getId().equals(childCategoryId));
        assertThat(childPresent).isFalse();
    }

    // ==================== C3: validation constraints ====================

    @Test
    @Order(20)
    @DisplayName("C3-01: duplicate code in same tenant throws exception")
    void createCategory_duplicateCode_throwsException() {
        assertThat(rootCategoryId).as("rootCategoryId must be set by C1-01").isNotNull();
        Category dup = buildCategory("ROOT-" + runId, "Duplicate " + runId, null, "product");

        assertThatThrownBy(() -> categoryService.createCategory(dup))
                .isInstanceOf(Exception.class);
    }

    @Test
    @Order(21)
    @DisplayName("C3-02: isCodeAvailable returns false for existing code")
    void isCodeAvailable_existingCode_returnsFalse() {
        boolean available = categoryService.isCodeAvailable("ROOT-" + runId, getTestTenant().getId());
        assertThat(available).isFalse();
    }

    @Test
    @Order(22)
    @DisplayName("C3-03: creating level-3 category under level-2 parent throws exception")
    void createCategory_threeLevel_throwsException() {
        assertThat(childCategoryId).as("childCategoryId must be set by C2-01").isNotNull();
        Category grandChild = buildCategory("GRAND-" + runId, "Grand Child", childCategoryId, "product");

        assertThatThrownBy(() -> categoryService.createCategory(grandChild))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("三级");
    }

    // ==================== C4: lifecycle (enable/disable/delete) ====================

    @Test
    @Order(30)
    @DisplayName("C4-01: disableCategory changes status to INACTIVE")
    void disableCategory_changesStatus() {
        assertThat(childCategoryId).as("childCategoryId must be set by C2-01").isNotNull();

        boolean result = categoryService.disableCategory(childCategoryId);

        assertThat(result).isTrue();
        Category updated = categoryService.getById(childCategoryId);
        assertThat(updated.getStatus()).isEqualTo("inactive");
    }

    @Test
    @Order(31)
    @DisplayName("C4-02: enableCategory restores status to ACTIVE")
    void enableCategory_restoresStatus() {
        assertThat(childCategoryId).as("childCategoryId must be set by C2-01").isNotNull();

        boolean result = categoryService.enableCategory(childCategoryId);

        assertThat(result).isTrue();
        Category updated = categoryService.getById(childCategoryId);
        assertThat(updated.getStatus()).isEqualTo("active");
    }

    @Test
    @Order(32)
    @DisplayName("C4-03: deleteCategory with children throws exception")
    void deleteCategory_withChildren_throwsException() {
        assertThat(rootCategoryId).as("rootCategoryId must be set by C1-01").isNotNull();

        assertThatThrownBy(() -> categoryService.deleteCategory(rootCategoryId))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("子类目");
    }

    @Test
    @Order(33)
    @DisplayName("C4-04: deleteCategory leaf succeeds and marks parent as leaf")
    void deleteCategory_leaf_succeeds() {
        assertThat(childCategoryId).as("childCategoryId must be set by C2-01").isNotNull();

        boolean result = categoryService.deleteCategory(childCategoryId);

        assertThat(result).isTrue();
        // Verify parent becomes leaf again
        Category root = categoryService.getById(rootCategoryId);
        assertThat(root.isLeaf()).isTrue();
    }

    // ==================== C5: query and tree ====================

    @Test
    @Order(40)
    @DisplayName("C5-01: getCategoryTree returns tree structure with root")
    void getCategoryTree_returnsTree() {
        assertThat(rootCategoryId).as("rootCategoryId must be set by C1-01").isNotNull();

        List<Map<String, Object>> tree = categoryService.getCategoryTree(getTestTenant().getId());

        assertThat(tree).isNotNull().isNotEmpty();
        boolean found = tree.stream().anyMatch(n -> rootCategoryId.equals(n.get("id")));
        assertThat(found).isTrue();
    }

    @Test
    @Order(41)
    @DisplayName("C5-02: findCategories pagination returns non-empty page")
    void findCategories_pagination_returnsResults() {
        var page = categoryService.findCategories(
                getTestTenant().getId(), 1, 10, null, "product", "active");

        assertThat(page).isNotNull();
        assertThat(page.getTotal()).isGreaterThan(0);
    }

    @Test
    @Order(42)
    @DisplayName("C5-03: findByTenantIdAndType returns categories of given type")
    void findByTenantIdAndType_returnsCorrectType() {
        List<Category> result = categoryService.findByTenantIdAndType(
                getTestTenant().getId(), "product");

        assertThat(result).isNotNull();
        result.forEach(c -> assertThat(c.getCategoryType()).isEqualTo("product"));
    }

    // ==================== C6: sort order ====================

    @Test
    @Order(50)
    @DisplayName("C6-01: updateSortOrder changes the sort weight")
    void updateSortOrder_changesSortWeight() {
        assertThat(rootCategoryId).as("rootCategoryId must be set by C1-01").isNotNull();

        boolean result = categoryService.updateSortOrder(rootCategoryId, 99);

        assertThat(result).isTrue();
        Category updated = categoryService.getById(rootCategoryId);
        assertThat(updated.getSortOrder()).isEqualTo(99);
    }

    // ==================== helper ====================

    private Category buildCategory(String code, String name, Long parentId, String type) {
        Category cat = new Category();
        cat.setCode(code);
        cat.setName(name);
        cat.setTenantId(getTestTenant().getId());
        cat.setParentId(parentId);
        cat.setCategoryType(type);
        cat.setStatus("active");
        cat.setVisible(true);
        cat.setSortOrder(0);
        return cat;
    }
}
