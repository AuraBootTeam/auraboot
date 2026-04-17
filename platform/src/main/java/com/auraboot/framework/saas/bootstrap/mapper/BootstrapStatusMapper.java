package com.auraboot.framework.saas.bootstrap.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BootstrapStatusMapper {

    @Select("SELECT COUNT(*) FROM ab_user_role ur "
          + "JOIN ab_role r ON r.id = ur.role_id "
          + "WHERE r.code = 'platform_admin' "
          + "AND (r.deleted_flag = FALSE OR r.deleted_flag IS NULL) "
          + "AND (ur.deleted_flag = FALSE OR ur.deleted_flag IS NULL)")
    long countPlatformAdminAssignments();

    @Select("SELECT COUNT(*) FROM ab_tenant "
          + "WHERE id = 1 "
          + "AND (deleted_flag = FALSE OR deleted_flag IS NULL)")
    long countSystemTenant();
}
