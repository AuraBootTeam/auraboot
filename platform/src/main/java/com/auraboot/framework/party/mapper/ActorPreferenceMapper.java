package com.auraboot.framework.party.mapper;

import com.auraboot.framework.party.entity.ActorPreference;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;

@Mapper
public interface ActorPreferenceMapper extends BaseMapper<ActorPreference> {
    @Select("""
            SELECT * FROM ab_actor_preference
             WHERE tenant_id = #{tenantId} AND tenant_member_id = #{tenantMemberId}
             LIMIT 1
            """)
    ActorPreference findByMember(
            @Param("tenantId") Long tenantId,
            @Param("tenantMemberId") Long tenantMemberId);

    @Select("""
            INSERT INTO ab_actor_preference (
                pid, tenant_id, tenant_member_id, last_party_id,
                actor_selection_mode, context_version
            ) VALUES (
                #{pid}, #{tenantId}, #{tenantMemberId}, #{partyId},
                'last_used', #{minimumContextVersion}
            )
            ON CONFLICT (tenant_id, tenant_member_id) DO UPDATE
               SET last_party_id = EXCLUDED.last_party_id,
                   actor_selection_mode = 'last_used',
                   context_version = GREATEST(
                       ab_actor_preference.context_version + 1,
                       EXCLUDED.context_version
                   ),
                   updated_at = CURRENT_TIMESTAMP
            RETURNING context_version
            """)
    @InterceptorIgnore(tenantLine = "true")
    long advanceContextVersion(
            @Param("pid") String pid,
            @Param("tenantId") Long tenantId,
            @Param("tenantMemberId") Long tenantMemberId,
            @Param("partyId") Long partyId,
            @Param("minimumContextVersion") long minimumContextVersion);
}
