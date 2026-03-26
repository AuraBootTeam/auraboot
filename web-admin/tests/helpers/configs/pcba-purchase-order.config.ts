/**
 * PCBA Purchase Order — Model test configuration.
 * @since 6.1.0
 */

import { uniqueId, todayStr } from '../../e2e/helpers';
import type { ModelTestConfig } from '../model-test-helper';

export const PCBA_PURCHASE_ORDER_CONFIG: ModelTestConfig = {
  modelCode: 'pe_purchase_order',
  pageKey: 'pe-purchase-order',
  namespace: 'pe',
  commands: {
    create: 'create_purchase_order',
    update: 'update_purchase_order',
    delete: 'delete_purchase_order',
  },
  defaultData: () => ({
    pe_po_code: `E2E-PO-${Date.now()}`,
    pe_po_date: todayStr(),
  }),
  deleteOperationType: 'delete',
};
