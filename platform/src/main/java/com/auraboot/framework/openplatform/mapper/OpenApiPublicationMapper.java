package com.auraboot.framework.openplatform.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** Explicit tenant predicates also protect calls before a human context is installed. */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface OpenApiPublicationMapper {
    @Select("SELECT set_config('statement_timeout', #{timeout}, true)")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    String setStatementTimeout(@Param("timeout") String timeout);

    @Select("SELECT pg_try_advisory_xact_lock(hashtextextended(concat('open-query:', #{tenantId}, ':', #{code}, ':', #{slot}), 0))")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    boolean tryQuerySlot(@Param("tenantId") Long tenantId, @Param("code") String code, @Param("slot") int slot);
    record PublicationRow(String code, String draft, long draftRevision, Integer activeVersion, String status) { }
    record ResourceAuthority(String definition, String policy, Long installationId) { }

    @Select("""
        SELECT s.definition::text, g.policy::text, i.id AS installation_id
        FROM ab_open_api_publication p
        JOIN ab_open_api_publication_snapshot s ON s.tenant_id = p.tenant_id
            AND s.code = p.code AND s.schema_version = p.active_version
        JOIN ab_open_api_resource_grant g ON g.tenant_id = p.tenant_id AND g.resource_code = p.code
        JOIN ab_application_installation i ON i.tenant_id = g.tenant_id AND i.id = g.installation_id
        JOIN ab_external_application a ON a.id = i.application_id
        JOIN ab_application_access_token t ON t.tenant_id = i.tenant_id AND t.installation_id = i.id
        WHERE p.tenant_id = #{tenantId} AND p.code = #{code} AND p.status = 'published'
            AND i.pid = #{installationPid} AND i.status = 'active' AND a.status = 'active'
            AND t.pid = #{tokenPid} AND t.revoked_at IS NULL
            AND (t.never_expires OR t.expires_at > clock_timestamp())
            AND (t.accept_until IS NULL OR t.accept_until > clock_timestamp())
            AND t.audience = 'auraboot-open-api'
            AND (t.token_kind = 'integration' OR t.scopes @> jsonb_build_array(s.definition->'scopes'->>#{operation}))
            AND EXISTS (SELECT 1 FROM ab_application_scope_grant sg
                WHERE sg.tenant_id = i.tenant_id AND sg.installation_id = i.id
                    AND sg.scope_code = s.definition->'scopes'->>#{operation})
        """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    ResourceAuthority authority(@Param("tenantId") Long tenantId, @Param("installationPid") String installationPid,
                                @Param("tokenPid") String tokenPid, @Param("code") String code, @Param("operation") String operation);

    @Select("""
        SELECT s.definition::text, g.policy::text, i.id AS installation_id
        FROM ab_open_api_publication p
        JOIN ab_open_api_publication_snapshot s ON s.tenant_id = p.tenant_id
            AND s.code = p.code AND s.schema_version = p.active_version
        JOIN ab_open_api_resource_grant g ON g.tenant_id = p.tenant_id AND g.resource_code = p.code
        JOIN ab_application_installation i ON i.tenant_id = g.tenant_id AND i.id = g.installation_id
        JOIN ab_external_application a ON a.id = i.application_id
        WHERE p.tenant_id = #{tenantId} AND p.code = #{code} AND p.status = 'published'
            AND i.pid = #{installationPid} AND i.status = 'active' AND a.status = 'active'
            AND EXISTS (SELECT 1 FROM ab_application_scope_grant sg
                WHERE sg.tenant_id = i.tenant_id AND sg.installation_id = i.id
                AND ((jsonb_exists(g.policy->'operations', 'get') AND sg.scope_code = s.definition->'scopes'->>'get')
                  OR (jsonb_exists(g.policy->'operations', 'list') AND sg.scope_code = s.definition->'scopes'->>'list')))
        """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    ResourceAuthority eventAuthority(@Param("tenantId") Long tenantId, @Param("installationPid") String installationPid,
                                     @Param("code") String code);

    @Select("""
        SELECT definition::text FROM ab_open_api_publication_snapshot
        WHERE tenant_id = #{tenantId} AND code = #{code} ORDER BY schema_version
        """)
    List<String> resourceSnapshots(@Param("tenantId") Long tenantId, @Param("code") String code);

    @Select("""
        SELECT code, draft::text, draft_revision, active_version, status
        FROM ab_open_api_publication WHERE tenant_id = #{tenantId} AND jsonb_exists(draft, 'modelCode') ORDER BY code
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

    @Select("""
        SELECT s.definition::text FROM ab_open_api_publication p
        JOIN ab_open_api_publication_snapshot s ON s.tenant_id = p.tenant_id
            AND s.code = p.code AND s.schema_version = p.active_version
        WHERE p.tenant_id = #{tenantId} AND p.status = 'published' AND jsonb_exists(s.definition, 'modelCode') ORDER BY p.code
        """)
    List<String> activeDefinitions(@Param("tenantId") Long tenantId);

    @Select("""
        SELECT code, draft::text, draft_revision, active_version, status
        FROM ab_open_api_publication WHERE tenant_id = #{tenantId} AND jsonb_exists(draft, 'queryCode') ORDER BY code
        """)
    List<PublicationRow> listQueries(@Param("tenantId") Long tenantId);

    @Select("""
        SELECT s.definition::text FROM ab_open_api_publication p
        JOIN ab_open_api_publication_snapshot s ON s.tenant_id = p.tenant_id
            AND s.code = p.code AND s.schema_version = p.active_version
        WHERE p.tenant_id = #{tenantId} AND p.status = 'published' AND jsonb_exists(s.definition, 'queryCode') ORDER BY p.code
        """)
    List<String> activeQueryDefinitions(@Param("tenantId") Long tenantId);

    @Insert("""
        INSERT INTO ab_open_api_publication_snapshot(tenant_id, code, schema_version, definition, published_by_pid)
        VALUES (#{tenantId}, #{code}, #{version}, CAST(#{definition} AS jsonb), #{actor})
        """)
    int insertSnapshot(@Param("tenantId") Long tenantId, @Param("code") String code,
                       @Param("version") int version, @Param("definition") String definition, @Param("actor") String actor);

    @Insert("""
        INSERT INTO ab_open_api_publication_snapshot(tenant_id, code, schema_version, definition, published_by_pid, source_binding)
        VALUES (#{tenantId}, #{code}, #{version}, CAST(#{definition} AS jsonb), #{actor}, CAST(#{binding} AS jsonb))
        """)
    int insertResourceSnapshot(@Param("tenantId") Long tenantId, @Param("code") String code,
                               @Param("version") int version, @Param("definition") String definition,
                               @Param("actor") String actor, @Param("binding") String binding);

    @Select("""
        SELECT source_binding::text FROM ab_open_api_publication_snapshot
        WHERE tenant_id = #{tenantId} AND code = #{code} AND schema_version = #{version}
        """)
    String sourceBinding(@Param("tenantId") Long tenantId, @Param("code") String code, @Param("version") int version);

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
