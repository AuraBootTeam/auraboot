package com.auraboot.framework.user.mapper;

import com.auraboot.framework.user.dao.entity.UserApplicationPreference;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface UserApplicationPreferenceMapper extends BaseMapper<UserApplicationPreference> {
    @Select("""
            SELECT * FROM ab_user_application_preference
             WHERE user_id = #{userId}
               AND application_id = #{applicationId}
               AND preference_key = #{preferenceKey}
             LIMIT 1
            """)
    UserApplicationPreference find(
            @Param("userId") Long userId,
            @Param("applicationId") Long applicationId,
            @Param("preferenceKey") String preferenceKey);

    @Insert("""
            INSERT INTO ab_user_application_preference (
                pid, user_id, application_id, preference_key, preference_value, schema_version
            ) VALUES (
                #{pid}, #{userId}, #{applicationId}, #{preferenceKey},
                CAST(#{preferenceValue} AS jsonb), #{schemaVersion}
            )
            ON CONFLICT (user_id, application_id, preference_key) DO UPDATE
               SET preference_value = EXCLUDED.preference_value,
                   schema_version = EXCLUDED.schema_version,
                   updated_at = CURRENT_TIMESTAMP
            """)
    void upsert(
            @Param("pid") String pid,
            @Param("userId") Long userId,
            @Param("applicationId") Long applicationId,
            @Param("preferenceKey") String preferenceKey,
            @Param("preferenceValue") String preferenceValue,
            @Param("schemaVersion") int schemaVersion);
}
