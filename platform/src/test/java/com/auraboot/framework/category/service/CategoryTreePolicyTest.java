package com.auraboot.framework.category.service;

import com.auraboot.framework.category.config.CategoryProperties;
import com.auraboot.framework.category.entity.Category;
import com.auraboot.framework.category.service.impl.CategoryTreePolicy;
import com.auraboot.framework.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CategoryTreePolicyTest {

    @Test
    void defaultCategoryTypeKeepsTwoLevelBackCompatibility() {
        CategoryTreePolicy policy = new CategoryTreePolicy(new CategoryProperties());
        Category levelTwoParent = category(2, "product", "product-parent", "/product-root/product-parent");

        assertThatThrownBy(() -> policy.assertChildAllowed(levelTwoParent, "product"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最多支持 2 级");
    }

    @Test
    void commerceProductCategoryTypeAllowsFiveLevelsButRejectsSixth() {
        CategoryTreePolicy policy = new CategoryTreePolicy(new CategoryProperties());
        Category levelFourParent = category(4, "commerce_product", "level-four",
                "/root/level-two/level-three/level-four");
        Category levelFiveParent = category(5, "commerce_product", "level-five",
                "/root/level-two/level-three/level-four/level-five");

        policy.assertChildAllowed(levelFourParent, "commerce_product");

        assertThatThrownBy(() -> policy.assertChildAllowed(levelFiveParent, "commerce_product"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最多支持 5 级");
    }

    @Test
    void materializedPathUsesParentPathWhenAvailable() {
        CategoryTreePolicy policy = new CategoryTreePolicy(new CategoryProperties());
        Category parent = category(1, "commerce_product", "apparel", "/apparel");

        assertThat(policy.rootPath("root")).isEqualTo("/root");
        assertThat(policy.childPath(parent, "dresses")).isEqualTo("/apparel/dresses");
    }

    @Test
    void materializedPathFallsBackToParentPidWhenParentPathIsMissing() {
        CategoryTreePolicy policy = new CategoryTreePolicy(new CategoryProperties());
        Category parent = category(1, "commerce_product", "apparel", null);

        assertThat(policy.childPath(parent, "dresses")).isEqualTo("/apparel/dresses");
    }

    private Category category(int level, String categoryType, String pid, String materializedPath) {
        Category category = new Category();
        category.setLevel(level);
        category.setCategoryType(categoryType);
        category.setPid(pid);
        category.setMaterializedPath(materializedPath);
        return category;
    }
}
