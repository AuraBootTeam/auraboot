package com.auraboot.framework.workbench.mapper;

import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.Map;

/** Exact tenant/version evidence; mutable control reads must bypass the MyBatis session cache. */
@Mapper
public interface AnnouncementContentSealMapper {
    @Insert("""
        INSERT INTO ab_announcement_content_seal(tenant_id,announcement_pid,version_pid,snapshot_hash,
            projection_schema,canonical_projection,document_digest,seal_identity,source_identity_kind)
        VALUES(#{tenant},#{root},#{version},#{hash},'operating-policy-document.v1',#{canonical},#{digest},#{identity},'SEAL_OBSERVED_CONTENT')
        """)
    int insert(@Param("tenant") Long tenant,@Param("root") String root,@Param("version") String version,
               @Param("hash") String hash,@Param("canonical") String canonical,@Param("digest") String digest,@Param("identity") String identity);
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    @Select("SELECT * FROM ab_announcement_content_seal WHERE tenant_id=#{tenant} AND version_pid=#{version}")
    Map<String,Object> seal(@Param("tenant") Long tenant,@Param("version") String version);
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    @Select("""
        SELECT v.announcement_pid,v.snapshot_schema,v.snapshot_hash,v.snapshot::text AS snapshot,c.*
        FROM ab_announcement_version v JOIN ab_announcement_version_control c
          ON c.tenant_id=v.tenant_id AND c.version_pid=v.pid
        WHERE v.tenant_id=#{tenant} AND v.pid=#{version}
        """)
    Map<String,Object> version(@Param("tenant") Long tenant,@Param("version") String version);
    @Select("SELECT clock_timestamp()") Instant databaseNow();
}
