/**
 * DocumentFlowConfig - Defines standard document flow chains for PCBA-ERP.
 *
 * Each flow chain maps a model code to its ordered sequence of steps.
 * Steps include the label, model code, and optional reference field that
 * links from the current document to the step's model.
 *
 * Reference field naming convention:
 * - Forward refs (current → next): field on the next model pointing back to current
 * - Backward refs (current ← prev): field on current model pointing to previous
 *
 * @since 3.8.0
 */

export interface DocumentFlowStep {
  /** Display label for this step (Chinese) */
  label: string;
  /** Model code of this step's document */
  modelCode: string;
  /** Record ID of the related document (resolved at runtime) */
  recordId?: string;
  /** Step status: completed, current, upcoming, or skipped */
  status: 'completed' | 'current' | 'upcoming' | 'skipped';
  /** Field name to check for status display */
  statusField?: string;
  /** Current status value (resolved at runtime) */
  statusValue?: string;
}

export interface DocumentFlowStepperProps {
  /** Ordered steps in the document flow */
  steps: DocumentFlowStep[];
  /** Model code of the currently viewed document */
  currentModelCode: string;
  /** Callback when a step circle is clicked */
  onStepClick?: (step: DocumentFlowStep) => void;
  /** Additional CSS class */
  className?: string;
}

/**
 * Flow chain definition used internally by the config.
 * `refField` is the field on the step's model that references back to a
 * related model, allowing the hook to resolve linked record IDs.
 */
interface FlowStepDef {
  label: string;
  modelCode: string;
  statusField?: string;
  /**
   * Map of modelCode -> fieldCode that can be used to find the related record.
   * Key = the model we are looking FROM, value = field on current record that holds the ref.
   * Example: from pe_sales_order, pe_ship_order_id on pe_shipment points back to pe_sales_order.
   */
  refFields?: Record<string, string>;
}

interface FlowChainDef {
  steps: FlowStepDef[];
}

/**
 * Standard document flow chains.
 *
 * The key is any model code that participates in the chain.
 * All models in the same chain share the same step definitions.
 */
const SALES_FLOW_STEPS: FlowStepDef[] = [
  {
    label: '报价',
    modelCode: 'sl_sales_quotation',
    statusField: 'sl_sq_status',
  },
  {
    label: '订单',
    modelCode: 'sl_sales_order',
    statusField: 'sl_so_status',
    // Sales order doesn't have a direct quotation_id ref — linked indirectly
  },
  {
    label: '发货',
    modelCode: 'sl_shipment',
    statusField: 'sl_sh_status',
    refFields: {
      // sl_shipment has sl_sh_order_id referencing sl_sales_order
      sl_sales_order: 'sl_sh_order_id',
    },
  },
  {
    label: '收款',
    modelCode: 'sl_sales_collection',
    statusField: 'sl_col_status',
    refFields: {
      // sl_sales_collection has sl_col_order_id referencing sl_sales_order
      sl_sales_order: 'sl_col_order_id',
    },
  },
];

const PURCHASE_FLOW_STEPS: FlowStepDef[] = [
  {
    label: '需求',
    modelCode: 'pr_purchase_request',
    statusField: 'pr_preq_status',
  },
  {
    label: '订单',
    modelCode: 'pr_purchase_order',
    statusField: 'pr_po_status',
  },
  {
    label: '收货',
    modelCode: 'pr_purchase_receipt',
    statusField: 'pr_rcpt_status',
    refFields: {
      pr_purchase_order: 'pr_rcpt_po_id',
    },
  },
  {
    label: '付款',
    modelCode: 'pr_purchase_payment',
    statusField: 'pr_pay_status',
    refFields: {
      pr_purchase_order: 'pr_pay_po_id',
    },
  },
];

const PRODUCTION_FLOW_STEPS: FlowStepDef[] = [
  {
    label: '计划',
    modelCode: 'pe_production_plan',
    statusField: 'pe_pp_status',
  },
  {
    label: '工单',
    modelCode: 'pe_work_order_op',
    statusField: 'pe_woo_status',
    refFields: {
      pe_production_plan: 'pe_woo_work_order_id',
    },
  },
  {
    label: '报工',
    modelCode: 'pe_work_report',
    refFields: {
      pe_work_order_op: 'pe_wr_work_order_op_id',
    },
  },
  {
    label: '入库',
    modelCode: 'pe_warehouse_in',
    statusField: 'pe_wh_in_status',
  },
];

const QUALITY_IQC_FLOW_STEPS: FlowStepDef[] = [
  {
    label: '收货',
    modelCode: 'pe_purchase_receipt',
    statusField: 'pe_rcpt_status',
    refFields: {
      qc_iqc_order: 'qc_iqc_receipt_id',
    },
  },
  {
    label: '来料检',
    modelCode: 'qc_iqc_order',
    statusField: 'qc_iqc_result',
  },
  {
    label: '入库',
    modelCode: 'pe_warehouse_in',
    statusField: 'pe_wh_in_status',
  },
];

const PURCHASE_RETURN_FLOW_STEPS: FlowStepDef[] = [
  {
    label: '采购订单',
    modelCode: 'pe_purchase_order',
    statusField: 'pe_po_status',
    refFields: {
      pe_purchase_return: 'pe_pr_po_id',
    },
  },
  {
    label: '退货',
    modelCode: 'pe_purchase_return',
    statusField: 'pe_pr_status',
  },
];

const SALES_RETURN_FLOW_STEPS: FlowStepDef[] = [
  {
    label: '销售订单',
    modelCode: 'pe_sales_order',
    statusField: 'pe_so_status',
    refFields: {
      pe_sales_return: 'pe_sr_so_id',
    },
  },
  {
    label: '退货',
    modelCode: 'pe_sales_return',
    statusField: 'pe_sr_status',
  },
];

// Build the flow chain map: every model in a chain points to the same chain definition
function buildFlowMap(chains: FlowStepDef[][]): Record<string, FlowChainDef> {
  const map: Record<string, FlowChainDef> = {};
  for (const steps of chains) {
    const chain: FlowChainDef = { steps };
    for (const step of steps) {
      // Only set if not already mapped (first chain wins for shared models)
      if (!map[step.modelCode]) {
        map[step.modelCode] = chain;
      }
    }
  }
  return map;
}

export const DOCUMENT_FLOW_MAP = buildFlowMap([
  SALES_FLOW_STEPS,
  PURCHASE_FLOW_STEPS,
  PRODUCTION_FLOW_STEPS,
  QUALITY_IQC_FLOW_STEPS,
  PURCHASE_RETURN_FLOW_STEPS,
  SALES_RETURN_FLOW_STEPS,
]);

/**
 * Look up a flow chain for a given model code.
 * Returns null if the model does not participate in any known flow.
 */
export function getFlowChain(modelCode: string): FlowChainDef | null {
  return DOCUMENT_FLOW_MAP[modelCode] ?? null;
}

/**
 * Resolve the ordered steps for a given model and record, filling in
 * status and reference fields where possible.
 *
 * @param modelCode - The model code of the document being viewed
 * @param record    - The current record data (key-value pairs)
 * @returns Resolved steps with status filled, or empty array if no flow
 */
export function resolveFlowSteps(
  modelCode: string,
  record: Record<string, any>,
): DocumentFlowStep[] {
  const chain = getFlowChain(modelCode);
  if (!chain) return [];

  const currentIndex = chain.steps.findIndex((s) => s.modelCode === modelCode);
  if (currentIndex === -1) return [];

  return chain.steps.map((stepDef, index) => {
    let status: DocumentFlowStep['status'];
    if (index < currentIndex) {
      status = 'completed';
    } else if (index === currentIndex) {
      status = 'current';
    } else {
      status = 'upcoming';
    }

    // Try to resolve the related record ID from the current record.
    // If this step's model has a refField mapping FROM the current model,
    // the current record may directly contain that reference value.
    let recordId: string | undefined;

    if (index === currentIndex) {
      // Current step: use the record's own ID
      recordId = record.id || record.pid;
    } else if (stepDef.refFields) {
      // Check if the current model has a ref field pointing to this step's model
      // (or vice versa)
      const refField = stepDef.refFields[modelCode];
      if (refField && record[refField]) {
        recordId = String(record[refField]);
      }
    }

    // Also check if the current record has a field that references this step's model.
    // Common pattern: field named like `pe_{prefix}_{step_model_suffix}_id`
    if (!recordId && index !== currentIndex) {
      // Scan record fields for any that might reference this model
      for (const [key, value] of Object.entries(record)) {
        if (value && typeof value === 'string' && key.endsWith('_id')) {
          // This is a heuristic — we trust the explicit refFields config above
          // but leave this as fallback for future extensibility
        }
      }
    }

    // Resolve status value from the record (only meaningful for current step)
    let statusValue: string | undefined;
    if (index === currentIndex && stepDef.statusField && record[stepDef.statusField]) {
      statusValue = String(record[stepDef.statusField]);
    }

    return {
      label: stepDef.label,
      modelCode: stepDef.modelCode,
      recordId,
      status,
      statusField: stepDef.statusField,
      statusValue,
    };
  });
}
