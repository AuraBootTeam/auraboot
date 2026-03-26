package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.SodRule;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Mapper for ab_sod_rule table.
 * Provides standard CRUD via BaseMapper and custom query methods
 * for finding rules by command code.
 *
 * @author AuraBoot Team
 * @since 6.2.0
 */
@Mapper
public interface SodRuleMapper extends BaseMapper<SodRule> {

    /**
     * Find all enabled rules where either command_a or command_b matches the given command code.
     * Note: tenant_id filter is automatically added by TenantLineInterceptor.
     * Soft-delete filter is added by MyBatis Plus @TableLogic.
     */
    @Select("""
        SELECT * FROM ab_sod_rule
        WHERE enabled = TRUE
          AND deleted_flag = FALSE
          AND (command_a = #{commandCode} OR command_b = #{commandCode})
        """)
    List<SodRule> findByCommandCode(@Param("commandCode") String commandCode);

    /**
     * Find all enabled rules for the current tenant.
     * Used for cache warming and listing.
     */
    @Select("""
        SELECT * FROM ab_sod_rule
        WHERE enabled = TRUE
          AND deleted_flag = FALSE
        ORDER BY rule_code
        """)
    List<SodRule> findAllEnabled();
}
