package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.ReconciliationProfile;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Mapper for ab_reconciliation_profile table.
 */
@Mapper
public interface ReconciliationProfileMapper extends BaseMapper<ReconciliationProfile> {

    @Select("SELECT * FROM ab_reconciliation_profile WHERE profile_code = #{profileCode} AND deleted_flag = FALSE")
    ReconciliationProfile findByCode(@Param("profileCode") String profileCode);

    @Select("SELECT * FROM ab_reconciliation_profile WHERE enabled = TRUE AND deleted_flag = FALSE ORDER BY profile_name")
    List<ReconciliationProfile> findAllEnabled();
}
