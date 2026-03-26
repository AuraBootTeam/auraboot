package com.auraboot.module.finance.mapper;

import com.auraboot.module.finance.entity.LegalEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * MyBatis mapper for {@code ab_legal_entity}.
 */
@Mapper
public interface LegalEntityMapper extends BaseMapper<LegalEntity> {

    /**
     * Find all legal entities for a tenant, ordered by entity code.
     * Uses an explicit tenant_id predicate (TenantLineInterceptor is not applied
     * to tables that are not registered as tenant-scoped DSL models).
     */
    @Select("""
            SELECT id, pid, tenant_id, entity_code, entity_name,
                   parent_id, currency, ownership_pct, is_parent,
                   created_at, updated_at
            FROM ab_legal_entity
            WHERE tenant_id = #{tenantId}
            ORDER BY entity_code
            """)
    List<LegalEntity> findAllByTenantId(@Param("tenantId") Long tenantId);

    /**
     * Find entities that are direct children of the given parent entity.
     */
    @Select("""
            SELECT id, pid, tenant_id, entity_code, entity_name,
                   parent_id, currency, ownership_pct, is_parent,
                   created_at, updated_at
            FROM ab_legal_entity
            WHERE tenant_id = #{tenantId}
              AND parent_id = #{parentId}
            ORDER BY entity_code
            """)
    List<LegalEntity> findChildrenByParentId(@Param("tenantId") Long tenantId,
                                             @Param("parentId") Long parentId);
}
