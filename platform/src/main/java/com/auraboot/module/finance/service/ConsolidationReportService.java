package com.auraboot.module.finance.service;

import com.auraboot.module.finance.dto.ConsolidationReportRequest;
import com.auraboot.module.finance.dto.ConsolidationReportResult;

/**
 * Generates a consolidated financial report for the tenant's legal entity group.
 *
 * <p>Consolidation steps:
 * <ol>
 *   <li>Load all legal entities for the tenant.</li>
 *   <li>Convert each entity's financials from its functional currency to the reporting currency.</li>
 *   <li>Sum converted financials across all entities.</li>
 *   <li>Find and eliminate pending intercompany transactions (mark as eliminated in DB).</li>
 *   <li>Deduct eliminated transaction amounts from consolidated revenue/expenses.</li>
 * </ol>
 */
public interface ConsolidationReportService {

    ConsolidationReportResult generate(ConsolidationReportRequest request, Long tenantId);
}
