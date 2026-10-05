package com.auraboot.framework.environment.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.auraboot.framework.environment.dao.entity.Environment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Options;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;

import java.util.List;

/**
 * Mapper for environment table.
 */
@Mapper
public interface EnvironmentMapper extends BaseMapper<Environment> {

    // The tenant row exists before its first environment. Lock it rather than
    // recovering a unique violation inside an already-aborted transaction.
    @Select("SELECT id FROM ab_tenant WHERE id = #{tenantId} AND deleted_flag = FALSE FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @InterceptorIgnore(tenantLine = "true")
    Long lockTenantForDefaultCreation(@Param("tenantId") Long tenantId);

    @Select("SELECT * FROM ab_environment WHERE tenant_id = #{tenantId} AND code = #{code} AND deleted_flag = FALSE")
    Environment findByTenantAndCode(@Param("tenantId") Long tenantId, @Param("code") String code);

    @Select("SELECT * FROM ab_environment WHERE tenant_id = #{tenantId} AND deleted_flag = FALSE ORDER BY sort_order ASC, created_at ASC")
    List<Environment> findAllByTenant(@Param("tenantId") Long tenantId);

    @Select("SELECT * FROM ab_environment WHERE tenant_id = #{tenantId} AND is_default = TRUE AND deleted_flag = FALSE LIMIT 1")
    Environment findDefaultByTenant(@Param("tenantId") Long tenantId);

    @Update("UPDATE ab_environment SET is_default = FALSE WHERE tenant_id = #{tenantId} AND deleted_flag = FALSE")
    int clearDefaultForTenant(@Param("tenantId") Long tenantId);
}
