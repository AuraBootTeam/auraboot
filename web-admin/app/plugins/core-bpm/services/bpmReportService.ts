/**
 * BPM Report Service - Approval chain reports
 */

import { get, ErrorCodes } from '~/shared/services/http-client';

const API_BASE = '/api/bpm/reports';

// ==================== Types ====================

export interface ApprovalChainEntry {
  id: string;
  eventType: string;
  activityId: string;
  operatorId: string;
  description: string;
  details: Record<string, any>;
  timestamp: string;
}

export interface ApprovalChainReport {
  processInstanceId: string;
  totalSteps: number;
  chain: ApprovalChainEntry[];
  generatedAt: string;
}

// ==================== Helper Functions ====================

function isSuccess(code: string): boolean {
  return code === ErrorCodes.SUCCESS;
}

// ==================== API Functions ====================

/**
 * Get approval chain for a process instance
 */
export async function getApprovalChain(processInstanceId: string): Promise<ApprovalChainReport> {
  const result = await get<ApprovalChainReport>(`${API_BASE}/approval-chain/${processInstanceId}`);
  if (!isSuccess(result.code) || !result.data) {
    throw new Error(result.desc || 'Failed to get approval chain');
  }
  return result.data;
}
