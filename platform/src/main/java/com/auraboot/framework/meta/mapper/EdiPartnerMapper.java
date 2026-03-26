package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.EdiPartner;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Mapper for EdiPartner entity.
 *
 * @since 5.3.0
 */
@Mapper
public interface EdiPartnerMapper extends BaseMapper<EdiPartner> {

    @Select("""
        SELECT * FROM ab_edi_partner
        WHERE tenant_id = #{tenantId}
          AND deleted_flag = FALSE
        ORDER BY partner_code
        """)
    List<EdiPartner> findByTenantId(@Param("tenantId") Long tenantId);

    @Select("""
        SELECT * FROM ab_edi_partner
        WHERE tenant_id = #{tenantId} AND partner_code = #{partnerCode}
          AND deleted_flag = FALSE
        """)
    EdiPartner findByCode(@Param("tenantId") Long tenantId,
                           @Param("partnerCode") String partnerCode);

    @Select("""
        SELECT * FROM ab_edi_partner
        WHERE tenant_id = #{tenantId} AND partner_type = #{partnerType}
          AND enabled = TRUE
          AND deleted_flag = FALSE
        ORDER BY partner_code
        """)
    List<EdiPartner> findEnabledByType(@Param("tenantId") Long tenantId,
                                        @Param("partnerType") String partnerType);

    @Select("""
        SELECT * FROM ab_edi_partner
        WHERE tenant_id = #{tenantId} AND enabled = TRUE
          AND deleted_flag = FALSE
        ORDER BY partner_code
        """)
    List<EdiPartner> findEnabled(@Param("tenantId") Long tenantId);
}
