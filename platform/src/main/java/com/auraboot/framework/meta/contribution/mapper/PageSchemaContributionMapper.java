package com.auraboot.framework.meta.contribution.mapper;

import com.auraboot.framework.meta.contribution.PersistedPageSchemaContribution;
import com.auraboot.framework.plugin.typehandler.PluginSettingsTypeHandler;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PageSchemaContributionMapper extends BaseMapper<PersistedPageSchemaContribution> {

    @Select("""
            SELECT c.*, p.plugin_id AS contributor_id
              FROM ab_page_schema_contribution c
              JOIN ab_plugin p
                ON p.pid = c.plugin_pid
               AND p.tenant_id = c.tenant_id
             WHERE c.tenant_id = #{tenantId}
               AND c.env_id = #{envId}
               AND c.target_page_key = #{targetPageKey}
               AND c.active = TRUE
               AND c.deleted_flag = FALSE
               AND p.status = 'enabled'
               AND p.deleted_flag = FALSE
             ORDER BY c.priority DESC, p.plugin_id ASC, c.contribution_id ASC
            """)
    @Results(id = "pageContributionResult", value = {
            @Result(column = "contributor_id", property = "contributorId"),
            @Result(column = "payload", property = "payload", typeHandler = PluginSettingsTypeHandler.class)
    })
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<PersistedPageSchemaContribution> findActiveForPage(
            @Param("tenantId") Long tenantId,
            @Param("envId") Long envId,
            @Param("targetPageKey") String targetPageKey);
}
