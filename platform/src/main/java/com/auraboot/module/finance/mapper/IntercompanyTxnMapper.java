package com.auraboot.module.finance.mapper;

import com.auraboot.module.finance.entity.IntercompanyTxn;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * MyBatis mapper for {@code fin_intercompany_txn}.
 */
@Mapper
public interface IntercompanyTxnMapper extends BaseMapper<IntercompanyTxn> {

    /**
     * Return all transactions for a tenant that have not yet been eliminated,
     * ordered by transaction date descending.
     * These are the candidates for the next consolidation elimination run.
     */
    @Select("""
            SELECT id, pid, tenant_id, from_entity_id, to_entity_id,
                   txn_date, txn_type, amount, currency, description,
                   is_eliminated, created_at
            FROM fin_intercompany_txn
            WHERE tenant_id = #{tenantId}
              AND is_eliminated = FALSE
            ORDER BY txn_date DESC
            """)
    List<IntercompanyTxn> findPendingEliminations(@Param("tenantId") Long tenantId);

    /**
     * Return all transactions between two specific entities (in either direction).
     */
    @Select("""
            SELECT id, pid, tenant_id, from_entity_id, to_entity_id,
                   txn_date, txn_type, amount, currency, description,
                   is_eliminated, created_at
            FROM fin_intercompany_txn
            WHERE tenant_id = #{tenantId}
              AND (
                    (from_entity_id = #{entityA} AND to_entity_id = #{entityB})
                 OR (from_entity_id = #{entityB} AND to_entity_id = #{entityA})
              )
            ORDER BY txn_date DESC
            """)
    List<IntercompanyTxn> findByEntityPair(@Param("tenantId")  Long tenantId,
                                           @Param("entityA")   Long entityA,
                                           @Param("entityB")   Long entityB);
}
