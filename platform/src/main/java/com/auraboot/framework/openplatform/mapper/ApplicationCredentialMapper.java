package com.auraboot.framework.openplatform.mapper;

import com.auraboot.framework.openplatform.entity.ApplicationCredential;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

@Mapper
public interface ApplicationCredentialMapper extends BaseMapper<ApplicationCredential> {
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT * FROM ab_application_credential
            WHERE client_id = #{clientId}
            LIMIT 1
            """)
    ApplicationCredential findByClientId(@Param("clientId") String clientId);

    @Select("""
            SELECT * FROM ab_application_credential
            WHERE tenant_id = #{tenantId} AND installation_id = #{installationId}
            ORDER BY created_at DESC
            """)
    List<ApplicationCredential> findByInstallation(@Param("tenantId") Long tenantId,
                                                    @Param("installationId") Long installationId);

    @Update("""
            UPDATE ab_application_credential
            SET status = 'revoked', revoked_at = #{now}
            WHERE tenant_id = #{tenantId} AND pid = #{pid} AND status = 'active'
            """)
    int revoke(@Param("tenantId") Long tenantId, @Param("pid") String pid, @Param("now") Instant now);
}
