package com.auraboot.framework.saas.bootstrap.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BootstrapStatusMapper {

    @Select("SELECT COUNT(*) FROM ab_user_role ur "
          + "JOIN ab_role r ON r.id = ur.role_id "
          + "WHERE r.code = #{adminRoleCode} "
          + "AND (r.deleted_flag = FALSE OR r.deleted_flag IS NULL) "
          + "AND (ur.deleted_flag = FALSE OR ur.deleted_flag IS NULL)")
    long countPlatformAdminAssignments(@Param("adminRoleCode") String adminRoleCode);

    @Select("SELECT COUNT(*) FROM ab_tenant "
          + "WHERE id = #{tenantId} "
          + "AND (deleted_flag = FALSE OR deleted_flag IS NULL)")
    long countTenantById(@Param("tenantId") long tenantId);
}
