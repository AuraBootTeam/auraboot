package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.EdiMessageType;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Mapper for EdiMessageType entity.
 *
 * @since 5.3.0
 */
@Mapper
public interface EdiMessageTypeMapper extends BaseMapper<EdiMessageType> {

    @Select("""
        SELECT * FROM ab_edi_message_type
        WHERE tenant_id = #{tenantId}
          AND deleted_flag = FALSE
        ORDER BY type_code
        """)
    List<EdiMessageType> findByTenantId(@Param("tenantId") Long tenantId);

    @Select("""
        SELECT * FROM ab_edi_message_type
        WHERE tenant_id = #{tenantId} AND type_code = #{typeCode}
          AND deleted_flag = FALSE
        """)
    EdiMessageType findByCode(@Param("tenantId") Long tenantId,
                               @Param("typeCode") String typeCode);

    @Select("""
        SELECT * FROM ab_edi_message_type
        WHERE tenant_id = #{tenantId} AND direction = #{direction}
          AND enabled = TRUE
          AND deleted_flag = FALSE
        ORDER BY type_code
        """)
    List<EdiMessageType> findEnabledByDirection(@Param("tenantId") Long tenantId,
                                                 @Param("direction") String direction);

    @Select("""
        SELECT * FROM ab_edi_message_type
        WHERE tenant_id = #{tenantId} AND protocol = #{protocol}
          AND deleted_flag = FALSE
        ORDER BY type_code
        """)
    List<EdiMessageType> findByProtocol(@Param("tenantId") Long tenantId,
                                         @Param("protocol") String protocol);
}
