package com.auraboot.framework.meta.contribution;

import com.auraboot.framework.meta.contribution.mapper.PageSchemaContributionMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class PageSchemaContributionMapperContractTest {

    @Test
    void activeQueryIsTenantEnvironmentAndPluginLifecycleScopedWithStableContributorId()
            throws Exception {
        Method method = PageSchemaContributionMapper.class.getMethod(
                "findActiveForPage", Long.class, Long.class, String.class);
        String sql = String.join("\n", method.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("p.plugin_id AS contributor_id")
                .contains("p.pid = c.plugin_pid")
                .contains("p.tenant_id = c.tenant_id")
                .contains("c.tenant_id = #{tenantId}")
                .contains("c.env_id = #{envId}")
                .contains("c.target_page_key = #{targetPageKey}")
                .contains("c.active = TRUE")
                .contains("c.deleted_flag = FALSE")
                .contains("p.status = 'enabled'")
                .contains("p.deleted_flag = FALSE")
                .contains("ORDER BY c.priority DESC, p.plugin_id ASC, c.contribution_id ASC");
    }
}
