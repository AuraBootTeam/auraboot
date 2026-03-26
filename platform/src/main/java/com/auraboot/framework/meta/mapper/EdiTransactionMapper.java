package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.EdiTransaction;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.util.List;

/**
 * Mapper for EdiTransaction entity.
 *
 * <p>Note: ab_edi_transaction has no deleted_flag (no soft delete), so
 * raw @Select queries do not need deleted_flag filters.
 *
 * @since 5.3.0
 */
@Mapper
public interface EdiTransactionMapper extends BaseMapper<EdiTransaction> {

    @Select("""
        SELECT * FROM ab_edi_transaction
        WHERE tenant_id = #{tenantId} AND partner_id = #{partnerId}
        ORDER BY created_at DESC
        LIMIT #{limit}
        """)
    List<EdiTransaction> findByPartnerId(@Param("tenantId") Long tenantId,
                                          @Param("partnerId") Long partnerId,
                                          @Param("limit") int limit);

    @Select("""
        SELECT * FROM ab_edi_transaction
        WHERE tenant_id = #{tenantId} AND status = #{status}
        ORDER BY created_at DESC
        LIMIT #{limit}
        """)
    List<EdiTransaction> findByStatus(@Param("tenantId") Long tenantId,
                                       @Param("status") String status,
                                       @Param("limit") int limit);

    @Select("""
        SELECT * FROM ab_edi_transaction
        WHERE tenant_id = #{tenantId}
          AND partner_id = #{partnerId}
          AND status = #{status}
        ORDER BY created_at DESC
        LIMIT #{limit}
        """)
    List<EdiTransaction> findByPartnerAndStatus(@Param("tenantId") Long tenantId,
                                                 @Param("partnerId") Long partnerId,
                                                 @Param("status") String status,
                                                 @Param("limit") int limit);

    @Select("""
        SELECT * FROM ab_edi_transaction
        WHERE tenant_id = #{tenantId} AND transaction_no = #{transactionNo}
        """)
    EdiTransaction findByTransactionNo(@Param("tenantId") Long tenantId,
                                        @Param("transactionNo") String transactionNo);

    @Select("""
        SELECT * FROM ab_edi_transaction
        WHERE tenant_id = #{tenantId}
          AND created_at >= #{start} AND created_at <= #{end}
        ORDER BY created_at DESC
        """)
    List<EdiTransaction> findByDateRange(@Param("tenantId") Long tenantId,
                                          @Param("start") Instant start,
                                          @Param("end") Instant end);

    @Update("""
        UPDATE ab_edi_transaction
        SET status = #{status}, error_message = #{errorMessage},
            retry_count = retry_count + 1
        WHERE id = #{id} AND tenant_id = #{tenantId}
        """)
    int updateStatusWithError(@Param("id") Long id,
                               @Param("tenantId") Long tenantId,
                               @Param("status") String status,
                               @Param("errorMessage") String errorMessage);
}
