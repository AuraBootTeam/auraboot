package com.auraboot.framework.automation.mapper;

import com.auraboot.framework.automation.entity.Automation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Automation Mapper interface
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Mapper
public interface AutomationMapper extends BaseMapper<Automation> {

    /**
     * Find automation by PID
     */
    @Select("SELECT * FROM ab_automation WHERE pid = #{pid} AND deleted_flag = false")
    Automation findByPid(@Param("pid") String pid);

    /**
     * Find enabled automations for a model
     */
    @Select("""
        SELECT * FROM ab_automation
        WHERE model_code = #{modelCode}
          AND enabled = true
          AND deleted_flag = false
        ORDER BY created_at
        """)
    List<Automation> findEnabledByModelCode(@Param("modelCode") String modelCode);

    /**
     * Find enabled automations by trigger type for a model
     */
    @Select("""
        SELECT * FROM ab_automation
        WHERE model_code = #{modelCode}
          AND trigger_type = #{triggerType}
          AND enabled = true
          AND deleted_flag = false
        ORDER BY created_at
        """)
    List<Automation> findEnabledByModelCodeAndTriggerType(
            @Param("modelCode") String modelCode,
            @Param("triggerType") String triggerType);

    /**
     * Find all automations for a model
     */
    @Select("""
        SELECT * FROM ab_automation
        WHERE model_code = #{modelCode}
          AND deleted_flag = false
        ORDER BY created_at DESC
        """)
    List<Automation> findByModelCode(@Param("modelCode") String modelCode);

    /**
     * Find all scheduled automations that are enabled
     */
    @Select("""
        SELECT * FROM ab_automation
        WHERE trigger_type = 'scheduled'
          AND enabled = true
          AND deleted_flag = false
        ORDER BY created_at
        """)
    List<Automation> findEnabledScheduled();

    /**
     * Find all inactivity-triggered automations that are enabled
     */
    @Select("""
        SELECT * FROM ab_automation
        WHERE trigger_type = 'on_inactivity'
          AND enabled = true
          AND deleted_flag = false
        ORDER BY created_at
        """)
    List<Automation> findEnabledInactivity();

    /**
     * Update trigger statistics
     */
    @Update("""
        UPDATE ab_automation
        SET last_triggered_at = NOW(),
            trigger_count = COALESCE(trigger_count, 0) + 1,
            updated_at = NOW()
        WHERE pid = #{pid}
        """)
    int updateTriggerStats(@Param("pid") String pid);

    /**
     * Enable/disable automation
     */
    @Update("""
        UPDATE ab_automation
        SET enabled = #{enabled},
            updated_at = NOW(),
            updated_by = #{updatedBy}
        WHERE pid = #{pid} AND deleted_flag = false
        """)
    int updateEnabled(
            @Param("pid") String pid,
            @Param("enabled") Boolean enabled,
            @Param("updatedBy") String updatedBy);

    /**
     * Insert with JSONB handling
     */
    @Insert("""
        INSERT INTO ab_automation (
            pid, tenant_id, name, description, model_code,
            trigger_type, trigger_config, trigger_condition, actions,
            flow_config, enabled, trigger_count, deleted_flag, created_at, updated_at,
            created_by, updated_by
        ) VALUES (
            #{pid}, #{tenantId}, #{name}, #{description}, #{modelCode},
            #{triggerType},
            #{triggerConfig, jdbcType=OTHER, typeHandler=com.auraboot.framework.automation.typehandler.TriggerConfigTypeHandler},
            #{triggerCondition},
            #{actions, jdbcType=OTHER, typeHandler=com.auraboot.framework.automation.typehandler.ActionsTypeHandler},
            #{flowConfig, jdbcType=OTHER, typeHandler=com.auraboot.framework.automation.typehandler.TriggerPayloadTypeHandler},
            #{enabled}, #{triggerCount}, #{deletedFlag}, #{createdAt}, #{updatedAt},
            #{createdBy}, #{updatedBy}
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertAutomation(Automation automation);

    /**
     * Update with JSONB handling
     */
    @Update("""
        UPDATE ab_automation SET
            name = #{name},
            description = #{description},
            trigger_type = #{triggerType},
            trigger_config = #{triggerConfig, jdbcType=OTHER, typeHandler=com.auraboot.framework.automation.typehandler.TriggerConfigTypeHandler},
            trigger_condition = #{triggerCondition},
            actions = #{actions, jdbcType=OTHER, typeHandler=com.auraboot.framework.automation.typehandler.ActionsTypeHandler},
            flow_config = #{flowConfig, jdbcType=OTHER, typeHandler=com.auraboot.framework.automation.typehandler.TriggerPayloadTypeHandler},
            enabled = #{enabled},
            updated_at = #{updatedAt},
            updated_by = #{updatedBy}
        WHERE pid = #{pid} AND deleted_flag = false
        """)
    int updateAutomation(Automation automation);
}
