package com.auraboot.framework.openplatform.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.*;

/** Explicit tenant/installation keys protect every read and write. JSONB uses an explicit mapper cast. */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface ExternalIngressDefaultsMapper {
    record Stored(String requestHash, String envelope) { }

    @Insert("""
        INSERT INTO ab_external_ingress_defaults
            (tenant_id, installation_id, source_code, identity_kind, identity_hash, request_hash, envelope)
        VALUES (#{tenant}, #{installation}, #{source}, #{kind}, #{identity}, #{hash}, CAST(#{envelope} AS jsonb))
        ON CONFLICT (installation_id, source_code, identity_kind, identity_hash) DO NOTHING
        """)
    int claim(@Param("tenant") long tenant, @Param("installation") long installation,
              @Param("source") String source, @Param("kind") String kind, @Param("identity") String identity,
              @Param("hash") String hash, @Param("envelope") String envelope);

    @Select("""
        SELECT request_hash, envelope::text FROM ab_external_ingress_defaults
        WHERE tenant_id = #{tenant} AND installation_id = #{installation} AND source_code = #{source}
            AND identity_kind = #{kind} AND identity_hash = #{identity}
        """)
    Stored find(@Param("tenant") long tenant, @Param("installation") long installation,
                @Param("source") String source, @Param("kind") String kind, @Param("identity") String identity);

    @Update("""
        UPDATE ab_external_ingress_defaults SET envelope = CAST(#{envelope} AS jsonb)
        WHERE tenant_id = #{tenant} AND installation_id = #{installation} AND source_code = #{source}
            AND identity_kind = #{kind} AND identity_hash = #{identity} AND request_hash = #{hash}
        """)
    int align(@Param("tenant") long tenant, @Param("installation") long installation,
              @Param("source") String source, @Param("kind") String kind, @Param("identity") String identity,
              @Param("hash") String hash, @Param("envelope") String envelope);
}
