package com.auraboot.framework.organization.mapper;

import com.auraboot.framework.organization.entity.Team;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TeamMapper extends BaseMapper<Team> {

    @Select("""
            SELECT * FROM ab_team
            WHERE tenant_id = #{tenantId}
              AND code = #{code}
              AND deleted_flag = FALSE
            LIMIT 1
            """)
    Team findByTenantIdAndCode(@Param("tenantId") Long tenantId, @Param("code") String code);

    @Select("""
            SELECT * FROM ab_team
            WHERE pid = #{pid}
              AND deleted_flag = FALSE
            LIMIT 1
            """)
    Team findByPid(@Param("pid") String pid);
}
