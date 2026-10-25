package com.auraboot.framework.i18n;

import com.auraboot.framework.i18n.service.I18nService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins that the RBAC + category BusinessException messages migrated to {@code $i18n:} keys
 * (RoleServiceImpl / CategoryServiceImpl) are registered in the i18n catalog and resolve per
 * locale, including the parameterized ({0}) ones. GlobalExceptionHandler resolves these at
 * error-response time.
 */
class RoleCategoryMessageI18nIT extends BaseIntegrationTest {

    @Autowired
    private I18nService i18nService;

    @Test
    @DisplayName("role.* keys resolve per locale (static + parameterized)")
    void roleKeys() {
        assertThat(i18nService.getValue("zh-CN", "role.system_no_delete")).isEqualTo("系统角色不允许删除");
        assertThat(i18nService.getValue("en-US", "role.system_no_delete")).isEqualTo("System roles cannot be deleted");
        assertThat(i18nService.getValue("zh-CN", "role.in_use")).isEqualTo("该角色正在被使用，无法删除");
        assertThat(i18nService.getMessage("zh-CN", "role.not_found", 7L)).isEqualTo("角色不存在: 7");
        assertThat(i18nService.getMessage("en-US", "role.not_found", 7L)).isEqualTo("Role not found: 7");
        assertThat(i18nService.getMessage("en-US", "role.code_exists", "ADMIN")).isEqualTo("Role code already exists: ADMIN");
    }

    @Test
    @DisplayName("category.* keys resolve per locale (static + parameterized)")
    void categoryKeys() {
        assertThat(i18nService.getValue("zh-CN", "category.not_found")).isEqualTo("类目不存在");
        assertThat(i18nService.getValue("en-US", "category.not_found")).isEqualTo("Category not found");
        assertThat(i18nService.getValue("zh-CN", "category.has_children")).isEqualTo("存在子类目，无法删除");
        assertThat(i18nService.getMessage("en-US", "category.code_exists", "C-001"))
                .isEqualTo("Category code already exists in this tenant: C-001");
    }

    @Test
    @DisplayName("permission.* keys (cause-preserving exception wraps) resolve per locale")
    void permissionKeys() {
        assertThat(i18nService.getValue("zh-CN", "permission.assign_failed")).isEqualTo("分配权限失败");
        assertThat(i18nService.getValue("en-US", "permission.assign_failed")).isEqualTo("Failed to assign permission");
        assertThat(i18nService.getValue("zh-CN", "permission.remove_all_failed")).isEqualTo("移除所有权限失败");
        assertThat(i18nService.getValue("en-US", "permission.copy_failed")).isEqualTo("Failed to copy permissions");
    }
}
