package com.auraboot.framework.workbench.mapper;

import com.auraboot.framework.workbench.entity.Announcement;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Mapper for Announcement entity.
 *
 * @since 6.5.0
 */
@Mapper
public interface AnnouncementMapper extends BaseMapper<Announcement> {

    @Select("""
        SELECT * FROM ab_announcement
        WHERE tenant_id = #{tenantId}
          AND status = #{status}
          AND (deleted_flag = FALSE OR deleted_flag IS NULL)
          AND (expires_at IS NULL OR expires_at > now())
        ORDER BY pinned DESC, published_at DESC
        LIMIT #{limit}
        """)
    List<Announcement> findByStatus(@Param("tenantId") Long tenantId,
                                    @Param("status") String status,
                                    @Param("limit") int limit);

    @Update("""
        UPDATE ab_announcement SET deleted_flag = TRUE, updated_at = now()
        WHERE tenant_id = #{tenantId} AND id = #{id}
        """)
    int softDelete(@Param("tenantId") Long tenantId, @Param("id") Long id);
}
