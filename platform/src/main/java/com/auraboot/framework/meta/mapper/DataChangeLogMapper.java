package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.DataChangeLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Mapper for DataChangeLog entity.
 *
 * @since 5.1.0
 */
@Mapper
public interface DataChangeLogMapper extends BaseMapper<DataChangeLog> {

    @Select("""
        SELECT * FROM ab_data_change_log
        WHERE tenant_id = #{tenantId} AND model_code = #{modelCode} AND record_id = #{recordId}
        ORDER BY changed_at DESC
        """)
    List<DataChangeLog> findByRecord(@Param("tenantId") Long tenantId,
                                     @Param("modelCode") String modelCode,
                                     @Param("recordId") String recordId);

    @Select("""
        SELECT * FROM ab_data_change_log
        WHERE tenant_id = #{tenantId} AND changed_by = #{userId}
        ORDER BY changed_at DESC
        LIMIT #{limit} OFFSET #{offset}
        """)
    List<DataChangeLog> findByUser(@Param("tenantId") Long tenantId,
                                   @Param("userId") Long userId,
                                   @Param("limit") int limit,
                                   @Param("offset") int offset);

    @Select("""
        SELECT COUNT(*) FROM ab_data_change_log
        WHERE tenant_id = #{tenantId} AND changed_by = #{userId}
        """)
    long countByUser(@Param("tenantId") Long tenantId, @Param("userId") Long userId);

    @Select("""
        SELECT * FROM ab_data_change_log
        WHERE tenant_id = #{tenantId} AND id = #{id}
        """)
    DataChangeLog findById(@Param("tenantId") Long tenantId, @Param("id") Long id);
}
