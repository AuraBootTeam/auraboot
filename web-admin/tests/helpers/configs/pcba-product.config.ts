/**
 * PCBA Product — Model test configuration.
 * @since 6.1.0
 */

import { uniqueId } from '../../e2e/helpers';
import type { ModelTestConfig } from '../model-test-helper';

export const PCBA_PRODUCT_CONFIG: ModelTestConfig = {
  modelCode: 'prod_product',
  pageKey: 'prod-product',
  namespace: 'prod',
  commands: {
    create: 'create_product',
    update: 'update_product',
    delete: 'delete_product',
  },
  defaultData: () => ({
    prod_name: `E2E Product ${uniqueId('P')}`,
    prod_code: `E2E-P-${Date.now()}`,
    prod_unit: 'pcs',
  }),
  deleteOperationType: 'delete',
};
