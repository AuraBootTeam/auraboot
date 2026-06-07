package com.auraboot.framework.decision.mapper;

import com.auraboot.framework.decision.entity.DrtDefinitionEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Mapper for {@link DrtDefinitionEntity}.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Mapper
public interface DrtDefinitionMapper extends BaseMapper<DrtDefinitionEntity> {

    /**
     * Find a definition by (tenant_id, decision_code).
     */
    @Select("SELECT * FROM ab_drt_definition WHERE tenant_id = #{tenantId} AND decision_code = #{decisionCode}")
    DrtDefinitionEntity findByTenantAndCode(
            @Param("tenantId") Long tenantId,
            @Param("decisionCode") String decisionCode);
}
