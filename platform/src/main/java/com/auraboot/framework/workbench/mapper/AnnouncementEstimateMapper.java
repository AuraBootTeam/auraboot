package com.auraboot.framework.workbench.mapper;

import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.*;

/** Immutable estimated audience, never an actual recipient or feedback fact. */
@Mapper
public interface AnnouncementEstimateMapper {
    @Insert("INSERT INTO ab_announcement_audience_estimate_header(tenant_id,version_pid,resolved_at,recipient_count) VALUES(#{tenant},#{version},#{at},#{count})")
    int header(@Param("tenant") Long tenant,@Param("version") String version,@Param("at") Instant at,@Param("count") int count);
    @Insert("""
        <script>
        INSERT INTO ab_announcement_audience_estimate(tenant_id,version_pid,user_pid,member_pid,display_name,matched_rule_refs) VALUES
        <foreach collection="rows" item="r" separator=",">
          (#{tenant},#{version},#{r.user},#{r.member},#{r.name},CAST(#{r.rules} AS jsonb))
        </foreach>
        </script>
        """)
    int rows(@Param("tenant") Long tenant,@Param("version") String version,@Param("rows") List<Map<String,Object>> rows);
}
