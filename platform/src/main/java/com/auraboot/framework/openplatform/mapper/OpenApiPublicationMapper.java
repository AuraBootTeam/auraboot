package com.auraboot.framework.openplatform.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** Explicit tenant predicates also protect calls before a human context is installed. */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface OpenApiPublicationMapper {
    record PublicationRow(String code, String draft, long draftRevision, Integer activeVersion, String status) { }

    @Select("""
        SELECT code, draft::text, draft_revision, active_version, status
        FROM ab_open_api_publication WHERE tenant_id = #{tenantId} ORDER BY code
        """)
    List<PublicationRow> list(@Param("tenantId") Long tenantId);

    @Select("""
        SELECT code, draft::text, draft_revision, active_version, status
        FROM ab_open_api_publication WHERE tenant_id = #{tenantId} AND code = #{code} FOR UPDATE
        """)
    PublicationRow lock(@Param("tenantId") Long tenantId, @Param("code") String code);

    @Insert("""
        INSERT INTO ab_open_api_publication(tenant_id, code, draft, updated_by_pid)
        VALUES (#{tenantId}, #{code}, CAST(#{definition} AS jsonb), #{actor})
        ON CONFLICT (tenant_id, code) DO NOTHING
        """)
    int create(@Param("tenantId") Long tenantId, @Param("code") String code,
               @Param("definition") String definition, @Param("actor") String actor);

    @Update("""
        UPDATE ab_open_api_publication SET draft = CAST(#{definition} AS jsonb),
            draft_revision = draft_revision + 1, updated_by_pid = #{actor}, updated_at = NOW()
        WHERE tenant_id = #{tenantId} AND code = #{code} AND draft_revision = #{expected}
        """)
    int updateDraft(@Param("tenantId") Long tenantId, @Param("code") String code,
                    @Param("definition") String definition, @Param("actor") String actor,
                    @Param("expected") long expected);

    @Select("""
        SELECT definition::text FROM ab_open_api_publication_snapshot
        WHERE tenant_id = #{tenantId} AND code = #{code} AND schema_version = #{version}
        """)
    String snapshot(@Param("tenantId") Long tenantId, @Param("code") String code, @Param("version") int version);

    @Select("""
        SELECT s.definition::text FROM ab_open_api_publication p
        JOIN ab_open_api_publication_snapshot s ON s.tenant_id = p.tenant_id
            AND s.code = p.code AND s.schema_version = p.active_version
        WHERE p.tenant_id = #{tenantId} AND p.code = #{code} AND p.status = 'published'
        """)
    String active(@Param("tenantId") Long tenantId, @Param("code") String code);

    @Insert("""
        INSERT INTO ab_open_api_publication_snapshot(tenant_id, code, schema_version, definition, published_by_pid)
        VALUES (#{tenantId}, #{code}, #{version}, CAST(#{definition} AS jsonb), #{actor})
        """)
    int insertSnapshot(@Param("tenantId") Long tenantId, @Param("code") String code,
                       @Param("version") int version, @Param("definition") String definition, @Param("actor") String actor);

    @Update("""
        UPDATE ab_open_api_publication SET active_version = #{version}, status = #{status},
            updated_by_pid = #{actor}, updated_at = NOW()
        WHERE tenant_id = #{tenantId} AND code = #{code}
        """)
    int activate(@Param("tenantId") Long tenantId, @Param("code") String code,
                 @Param("version") Integer version, @Param("status") String status, @Param("actor") String actor);

    @Insert("""
        INSERT INTO ab_open_api_publication_audit(tenant_id, resource_code, installation_id, action, actor_pid, revision)
        VALUES (#{tenantId}, #{code}, #{installationId}, #{action}, #{actor}, #{revision})
        """)
    int audit(@Param("tenantId") Long tenantId, @Param("code") String code,
              @Param("installationId") Long installationId, @Param("action") String action,
              @Param("actor") String actor, @Param("revision") long revision);

    @Insert("""
        INSERT INTO ab_open_api_resource_grant(tenant_id, installation_id, resource_code, policy, updated_by_pid)
        VALUES (#{tenantId}, #{installationId}, #{code}, CAST(#{policy} AS jsonb), #{actor})
        ON CONFLICT (tenant_id, installation_id, resource_code) DO UPDATE
        SET policy = EXCLUDED.policy, revision = ab_open_api_resource_grant.revision + 1,
            updated_by_pid = EXCLUDED.updated_by_pid, updated_at = NOW()
        """)
    int grant(@Param("tenantId") Long tenantId, @Param("installationId") Long installationId,
              @Param("code") String code, @Param("policy") String policy, @Param("actor") String actor);

    @Select("""
        SELECT g.policy::text FROM ab_open_api_resource_grant g
        JOIN ab_application_installation i ON i.tenant_id = g.tenant_id AND i.id = g.installation_id
        JOIN ab_external_application a ON a.id = i.application_id
        WHERE g.tenant_id = #{tenantId} AND g.installation_id = #{installationId}
            AND g.resource_code = #{code} AND i.status = 'active' AND a.status = 'active'
        """)
    String policy(@Param("tenantId") Long tenantId, @Param("installationId") Long installationId, @Param("code") String code);

    @Delete("""
        DELETE FROM ab_open_api_resource_grant
        WHERE tenant_id = #{tenantId} AND installation_id = #{installationId} AND resource_code = #{code}
        """)
    int revoke(@Param("tenantId") Long tenantId, @Param("installationId") Long installationId, @Param("code") String code);
}
