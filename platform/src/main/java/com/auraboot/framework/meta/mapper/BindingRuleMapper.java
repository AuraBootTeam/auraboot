package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.BindingRule;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Binding Rule Mapper
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Mapper
public interface BindingRuleMapper extends BaseMapper<BindingRule> {

    @Insert("""
        INSERT INTO ab_binding_rule
        (pid, tenant_id, command_id, rule_type, expression,
         target_model, target_field, source_field, handler_class, event_type,
         config, sequence, enabled, extension, status, deleted_flag,
         created_at, updated_at)
        VALUES
        (#{pid}, #{tenantId}, #{commandId}, #{ruleType}, #{expression},
         #{targetModel}, #{targetField}, #{sourceField}, #{handlerClass}, #{eventType},
         #{config, typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler},
         #{sequence}, #{enabled},
         #{extension, typeHandler=com.auraboot.framework.application.database.mybatis.ExtensionTypeHandler},
         #{status}, #{deletedFlag},
         #{createdAt}, #{updatedAt})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertRule(BindingRule rule);

    @Select("SELECT * FROM ab_binding_rule WHERE command_id = #{commandId} AND deleted_flag = false ORDER BY sequence")
    List<BindingRule> findByCommandId(@Param("commandId") Long commandId);

    @Select("SELECT * FROM ab_binding_rule WHERE command_id = #{commandId} AND rule_type = #{ruleType} AND enabled = TRUE AND deleted_flag = false ORDER BY sequence")
    List<BindingRule> findByCommandIdAndType(@Param("commandId") Long commandId, @Param("ruleType") String ruleType);

    @Select("SELECT * FROM ab_binding_rule WHERE pid = #{pid} AND deleted_flag = false")
    BindingRule findByPid(@Param("pid") String pid);

    @Update("UPDATE ab_binding_rule SET deleted_flag = TRUE, updated_at = NOW() WHERE pid = #{pid}")
    int softDelete(@Param("pid") String pid);

    @Update("UPDATE ab_binding_rule SET sequence = #{sequence}, updated_at = NOW() WHERE pid = #{pid}")
    int updateSequence(@Param("pid") String pid, @Param("sequence") int sequence);

    /**
     * Update plugin_pid for a binding rule (used during plugin import).
     */
    @Update("UPDATE ab_binding_rule SET plugin_pid = #{pluginPid}, updated_at = NOW() WHERE pid = #{pid}")
    int updatePluginPid(@Param("pluginPid") String pluginPid, @Param("pid") String pid);
}
