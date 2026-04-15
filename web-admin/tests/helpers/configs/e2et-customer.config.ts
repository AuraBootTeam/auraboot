/**
 * E2E Test Customer — Model test configuration.
 *
 * Defines the e2et_customer model config for use with ModelTestHelper.
 * Covers UNIQUE_COMPOSITE validation and search/filter UI testing.
 *
 * @since 6.2.0
 */

import { uniqueId } from '../../e2e/helpers';
import type { ModelTestConfig } from '../model-test-helper';

export const E2ET_CUSTOMER_CONFIG: ModelTestConfig = {
  modelCode: 'e2et_customer',
  pageKey: 'e2et_customer',
  namespace: 'e2et',
  commands: {
    create: 'create_customer',
    update: 'update_customer',
    delete: 'delete_customer',
  },
  defaultData: () => ({
    e2et_cust_code: `CUST-${uniqueId()}`,
    e2et_cust_name: `Test Customer ${uniqueId()}`,
    e2et_cust_region: 'east',
    e2et_cust_contact: 'John Doe',
    e2et_cust_email: 'test@example.com',
    e2et_cust_active: true,
  }),
  deleteOperationType: 'delete',
};
