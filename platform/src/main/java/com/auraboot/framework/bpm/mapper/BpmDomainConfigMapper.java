package com.auraboot.framework.bpm.mapper;

import com.auraboot.framework.bpm.entity.BpmDomainConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Mapper for BPM domain configurations.
 */
@Mapper
public interface BpmDomainConfigMapper extends BaseMapper<BpmDomainConfig> {

    /**
     * Find by PID.
     */
    @Select("SELECT * FROM ab_bpm_domain_config WHERE pid = #{pid} AND deleted_flag = false")
    BpmDomainConfig findByPid(@Param("pid") String pid);

    /**
     * Find by domain code within a tenant.
     */
    @Select("SELECT * FROM ab_bpm_domain_config WHERE domain_code = #{domainCode} AND deleted_flag = false")
    BpmDomainConfig findByDomainCode(@Param("domainCode") String domainCode);

    /**
     * Find all enabled domain configs for a tenant.
     */
    @Select("SELECT * FROM ab_bpm_domain_config WHERE enabled = TRUE AND deleted_flag = false ORDER BY domain_name")
    List<BpmDomainConfig> findAllEnabled();

    /**
     * Check if a domain code already exists within a tenant.
     */
    @Select("SELECT COUNT(*) > 0 FROM ab_bpm_domain_config WHERE domain_code = #{domainCode} AND deleted_flag = false")
    boolean existsByDomainCode(@Param("domainCode") String domainCode);
}
