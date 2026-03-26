package com.auraboot.framework.bpm.mapper;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.auraboot.framework.bpm.entity.SlaConfigEntity;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * SLA config mapper.
 *
 * Note: warning_rules is a JSONB column requiring autoResultMap for proper type handler resolution.
 * Use default methods with selectList()/selectOne() instead of @Select.
 */
@Mapper
public interface SlaConfigMapper extends BaseMapper<SlaConfigEntity> {

    default SlaConfigEntity findByPid(String pid) {
        return selectOne(new QueryWrapper<SlaConfigEntity>()
                .eq("pid", pid));
    }

    default List<SlaConfigEntity> findAllEnabled(Long tenantId) {
        return selectList(new QueryWrapper<SlaConfigEntity>()
                .eq("tenant_id", tenantId)
                .eq("enabled", true)
                .orderByAsc("name"));
    }

    default List<SlaConfigEntity> findAll(Long tenantId) {
        return selectList(new QueryWrapper<SlaConfigEntity>()
                .eq("tenant_id", tenantId)
                .orderByAsc("name"));
    }

    default List<SlaConfigEntity> findByTarget(Long tenantId, String targetType, String targetKey) {
        return selectList(new QueryWrapper<SlaConfigEntity>()
                .eq("tenant_id", tenantId)
                .eq("target_type", targetType)
                .eq("target_key", targetKey)
                .eq("enabled", true));
    }

    default List<SlaConfigEntity> findByDomain(Long tenantId, String domainCode) {
        return selectList(new QueryWrapper<SlaConfigEntity>()
                .eq("tenant_id", tenantId)
                .eq("domain_code", domainCode)
                .eq("enabled", true));
    }
}
