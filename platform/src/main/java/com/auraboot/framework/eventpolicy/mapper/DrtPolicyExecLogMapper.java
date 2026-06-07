package com.auraboot.framework.eventpolicy.mapper;

import com.auraboot.framework.eventpolicy.entity.DrtPolicyExecLogEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Mapper for {@link DrtPolicyExecLogEntity}.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Mapper
public interface DrtPolicyExecLogMapper extends BaseMapper<DrtPolicyExecLogEntity> {

    @Select("SELECT * FROM ab_drt_policy_exec_log WHERE tenant_id = #{tenantId} AND idempotency_key = #{key}")
    DrtPolicyExecLogEntity findByTenantAndKey(@Param("tenantId") Long tenantId, @Param("key") String key);
}
