package com.auraboot.framework.plugin.marketplace.mapper;

import com.auraboot.framework.plugin.marketplace.entity.MarketplaceSolutionInstall;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface MarketplaceSolutionInstallMapper extends BaseMapper<MarketplaceSolutionInstall> {

    @Select("SELECT * FROM ab_marketplace_solution_install WHERE tenant_id = #{tenantId} ORDER BY installed_at DESC")
    List<MarketplaceSolutionInstall> findByTenant(@Param("tenantId") Long tenantId);

    @Select("SELECT * FROM ab_marketplace_solution_install WHERE tenant_id = #{tenantId} AND solution_pid = #{solutionPid}")
    MarketplaceSolutionInstall findByTenantAndSolution(@Param("tenantId") Long tenantId, @Param("solutionPid") String solutionPid);

    @Delete("DELETE FROM ab_marketplace_solution_install WHERE tenant_id = #{tenantId} AND solution_pid = #{solutionPid}")
    int deleteByTenantAndSolution(@Param("tenantId") Long tenantId, @Param("solutionPid") String solutionPid);
}
