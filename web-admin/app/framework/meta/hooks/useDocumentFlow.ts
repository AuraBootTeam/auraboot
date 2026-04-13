/**
 * useDocumentFlow - Hook to resolve document flow steps for a given model and record.
 *
 * Determines where the current document sits in a workflow chain
 * (e.g., Quotation -> Order -> Shipment -> Collection) and resolves
 * linked record IDs where possible.
 *
 * @since 3.8.0
 */

import { useMemo } from 'react';
import {
  resolveFlowSteps,
  getFlowChain,
  type DocumentFlowStep,
} from '~/framework/meta/components/DocumentFlowConfig';

interface UseDocumentFlowResult {
  /** Resolved steps with status and record IDs filled in */
  steps: DocumentFlowStep[];
  /** Whether this model participates in a known document flow */
  hasFlow: boolean;
}

/**
 * Resolves the document flow chain for a given model and record.
 *
 * @param modelCode - The model code of the document being viewed (e.g., "pe_sales_order")
 * @param recordId  - The ID of the current record
 * @param record    - The full record data (key-value pairs from the API)
 * @returns The resolved steps and a flag indicating whether a flow exists
 */
export function useDocumentFlow(
  modelCode: string,
  recordId: string,
  record: Record<string, any>,
): UseDocumentFlowResult {
  const steps = useMemo(() => {
    if (!modelCode || !record) return [];
    return resolveFlowSteps(modelCode, record);
  }, [modelCode, record]);

  const hasFlow = useMemo(() => {
    return getFlowChain(modelCode) !== null;
  }, [modelCode]);

  return { steps, hasFlow };
}
