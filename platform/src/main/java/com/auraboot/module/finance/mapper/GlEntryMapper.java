package com.auraboot.module.finance.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * MyBatis mapper for biz_gl_entry table.
 * Raw @Select queries include explicit tenant_id scoping per convention.
 */
@Mapper
public interface GlEntryMapper {

    @Insert("""
            INSERT INTO biz_gl_entry
                (pid, tenant_id, journal_pid, account_code, account_name,
                 debit_amount, credit_amount, currency, description,
                 entry_date, fiscal_period, reference_type, reference_pid, created_by, created_at)
            VALUES
                (#{pid}, #{tenantId}, #{journalPid}, #{accountCode}, #{accountName},
                 #{debitAmount}, #{creditAmount}, #{currency}, #{description},
                 #{entryDate}, #{fiscalPeriod}, #{referenceType}, #{referencePid}, #{createdBy}, CURRENT_TIMESTAMP)
            """)
    void insert(@Param("pid") String pid,
                @Param("tenantId") Long tenantId,
                @Param("journalPid") String journalPid,
                @Param("accountCode") String accountCode,
                @Param("accountName") String accountName,
                @Param("debitAmount") BigDecimal debitAmount,
                @Param("creditAmount") BigDecimal creditAmount,
                @Param("currency") String currency,
                @Param("description") String description,
                @Param("entryDate") LocalDate entryDate,
                @Param("fiscalPeriod") String fiscalPeriod,
                @Param("referenceType") String referenceType,
                @Param("referencePid") String referencePid,
                @Param("createdBy") Long createdBy);

    /**
     * Trial balance: aggregate debit and credit totals per account for a fiscal period.
     */
    @Select("""
            SELECT account_code, account_name,
                   COALESCE(SUM(debit_amount), 0)  AS total_debit,
                   COALESCE(SUM(credit_amount), 0) AS total_credit,
                   COALESCE(SUM(debit_amount), 0) - COALESCE(SUM(credit_amount), 0) AS net_balance
            FROM biz_gl_entry
            WHERE tenant_id = #{tenantId}
              AND fiscal_period = #{fiscalPeriod}
            GROUP BY account_code, account_name
            ORDER BY account_code
            """)
    List<Map<String, Object>> trialBalance(@Param("tenantId") Long tenantId,
                                           @Param("fiscalPeriod") String fiscalPeriod);

    /**
     * Account balance summary between two dates.
     */
    @Select("""
            SELECT account_code, account_name,
                   COALESCE(SUM(debit_amount), 0)  AS total_debit,
                   COALESCE(SUM(credit_amount), 0) AS total_credit,
                   COALESCE(SUM(debit_amount), 0) - COALESCE(SUM(credit_amount), 0) AS net_balance
            FROM biz_gl_entry
            WHERE tenant_id = #{tenantId}
              AND account_code = #{accountCode}
              AND entry_date BETWEEN #{fromDate} AND #{toDate}
            GROUP BY account_code, account_name
            """)
    Map<String, Object> accountBalance(@Param("tenantId") Long tenantId,
                                       @Param("accountCode") String accountCode,
                                       @Param("fromDate") LocalDate fromDate,
                                       @Param("toDate") LocalDate toDate);

    /**
     * General ledger detail: all entries for an account between two dates, ordered by date.
     */
    @Select("""
            SELECT pid, journal_pid, account_code, account_name,
                   debit_amount, credit_amount, currency, description,
                   entry_date, fiscal_period, reference_type, reference_pid, created_at
            FROM biz_gl_entry
            WHERE tenant_id = #{tenantId}
              AND account_code = #{accountCode}
              AND entry_date BETWEEN #{fromDate} AND #{toDate}
            ORDER BY entry_date, created_at
            LIMIT #{pageSize} OFFSET #{offset}
            """)
    List<Map<String, Object>> generalLedger(@Param("tenantId") Long tenantId,
                                            @Param("accountCode") String accountCode,
                                            @Param("fromDate") LocalDate fromDate,
                                            @Param("toDate") LocalDate toDate,
                                            @Param("pageSize") int pageSize,
                                            @Param("offset") int offset);

    @Select("""
            SELECT COUNT(*)
            FROM biz_gl_entry
            WHERE tenant_id = #{tenantId}
              AND account_code = #{accountCode}
              AND entry_date BETWEEN #{fromDate} AND #{toDate}
            """)
    long countByAccount(@Param("tenantId") Long tenantId,
                        @Param("accountCode") String accountCode,
                        @Param("fromDate") LocalDate fromDate,
                        @Param("toDate") LocalDate toDate);
}
