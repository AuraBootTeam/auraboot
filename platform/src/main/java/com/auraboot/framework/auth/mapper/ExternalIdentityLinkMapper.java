package com.auraboot.framework.auth.mapper;

import com.auraboot.framework.auth.dto.ExternalIdentityLinkSummary;
import com.auraboot.framework.auth.entity.ExternalIdentityLink;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface ExternalIdentityLinkMapper extends BaseMapper<ExternalIdentityLink> {

    @Select("""
            SELECT *
              FROM ab_external_identity_link
             WHERE identity_provider_instance_id = #{identityProviderInstanceId}
               AND external_subject = #{subject}
               AND unlinked_at IS NULL
             LIMIT 1
            """)
    ExternalIdentityLink findActiveBySubject(
            @Param("identityProviderInstanceId") Long identityProviderInstanceId,
            @Param("subject") String subject);

    @Select("""
            SELECT *
              FROM ab_external_identity_link
             WHERE user_id = #{userId}
               AND identity_provider_instance_id = #{identityProviderInstanceId}
               AND unlinked_at IS NULL
             LIMIT 1
            """)
    ExternalIdentityLink findActiveByUserAndInstance(
            @Param("userId") Long userId,
            @Param("identityProviderInstanceId") Long identityProviderInstanceId);

    @Select("""
            SELECT l.pid,
                   p.code AS provider,
                   l.external_username AS display_name,
                   l.claims ->> 'avatarUrl' AS avatar_url,
                   l.email,
                   l.linked_at
              FROM ab_external_identity_link l
              JOIN ab_identity_provider_instance p
                ON p.id = l.identity_provider_instance_id
             WHERE l.user_id = #{userId}
               AND l.tenant_id = #{tenantId}
               AND l.unlinked_at IS NULL
             ORDER BY l.linked_at DESC, p.code
            """)
    List<ExternalIdentityLinkSummary> listActiveByUser(
            @Param("userId") Long userId,
            @Param("tenantId") Long tenantId);
}
