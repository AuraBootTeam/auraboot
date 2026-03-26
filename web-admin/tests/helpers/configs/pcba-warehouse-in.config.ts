/**
 * PCBA Warehouse In — Model test configuration.
 * @since 6.1.0
 */

import { uniqueId } from '../../e2e/helpers';
import type { ModelTestConfig } from '../model-test-helper';

export const PCBA_WAREHOUSE_IN_CONFIG: ModelTestConfig = {
  modelCode: 'pe_warehouse_in',
  pageKey: 'pe-warehouse-in',
  namespace: 'pe',
  commands: {
    create: 'create_warehouse_in',
    update: 'update_warehouse_in',
    delete: 'delete_warehouse_in',
  },
  defaultData: () => ({
    pe_wh_in_code: `E2E-WI-${Date.now()}`,
    pe_wh_in_type: 'purchase',
  }),
  deleteOperationType: 'delete',
};
