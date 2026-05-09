package com.auraboot.framework.connector.jdbc.mapper;

import com.auraboot.framework.connector.jdbc.entity.JdbcConnector;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Delete;

import java.util.List;

@Mapper
public interface JdbcConnectorMapper extends BaseMapper<JdbcConnector> {
    @Select("SELECT * FROM ab_jdbc_connector WHERE tenant_id = #{tenantId} AND pid = #{pid} LIMIT 1")
    JdbcConnector findByPid(@Param("tenantId") Long tenantId, @Param("pid") String pid);

    @Select("SELECT * FROM ab_jdbc_connector WHERE tenant_id = #{tenantId} ORDER BY id DESC")
    List<JdbcConnector> findByTenant(@Param("tenantId") Long tenantId);

    @Delete("DELETE FROM ab_jdbc_connector WHERE tenant_id = #{tenantId} AND pid = #{pid}")
    int deleteByPid(@Param("tenantId") Long tenantId, @Param("pid") String pid);
}
