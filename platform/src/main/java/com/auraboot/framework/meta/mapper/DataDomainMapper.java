package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.DataDomain;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Mapper for DataDomain entity.
 *
 * @since 5.2.0
 */
@Mapper
public interface DataDomainMapper extends BaseMapper<DataDomain> {

    @Select("""
        SELECT * FROM ab_data_domain
        WHERE tenant_id = #{tenantId}
          AND deleted_flag = FALSE
        ORDER BY domain_code
        """)
    List<DataDomain> findByTenantId(@Param("tenantId") Long tenantId);

    @Select("""
        SELECT * FROM ab_data_domain
        WHERE tenant_id = #{tenantId} AND domain_code = #{domainCode}
          AND deleted_flag = FALSE
        """)
    DataDomain findByCode(@Param("tenantId") Long tenantId,
                           @Param("domainCode") String domainCode);

    @Select("""
        SELECT * FROM ab_data_domain
        WHERE tenant_id = #{tenantId} AND enabled = TRUE
          AND deleted_flag = FALSE
        ORDER BY domain_code
        """)
    List<DataDomain> findEnabled(@Param("tenantId") Long tenantId);

    @Select("""
        SELECT * FROM ab_data_domain
        WHERE tenant_id = #{tenantId} AND parent_domain_id = #{parentId}
          AND deleted_flag = FALSE
        ORDER BY domain_code
        """)
    List<DataDomain> findChildren(@Param("tenantId") Long tenantId,
                                   @Param("parentId") Long parentId);

    /**
     * Recursively find all descendant domain IDs (including the given domain itself).
     */
    @Select("""
        WITH RECURSIVE domain_tree AS (
            SELECT id FROM ab_data_domain
            WHERE id = #{domainId} AND tenant_id = #{tenantId}
              AND deleted_flag = FALSE
            UNION ALL
            SELECT d.id FROM ab_data_domain d
            INNER JOIN domain_tree dt ON d.parent_domain_id = dt.id
            WHERE d.tenant_id = #{tenantId}
              AND (d.deleted_flag = FALSE OR d.deleted_flag IS NULL)
        )
        SELECT id FROM domain_tree
        """)
    List<Long> findDescendantIds(@Param("tenantId") Long tenantId,
                                  @Param("domainId") Long domainId);
}
