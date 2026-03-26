/**
 * Data Permission — Model test configuration for platform-admin plugin.
 * @since 7.0.0
 */

import { uniqueId } from '../../e2e/helpers';
import type { ModelTestConfig } from '../model-test-helper';

export const ADMIN_DATA_PERMISSION_CONFIG: ModelTestConfig = {
  modelCode: 'data_permission',
  pageKey: 'data-permission',
  namespace: 'admin',
  commands: {
    create: 'create_data_permission',
    update: 'update_data_permission',
    delete: 'delete_data_permission',
  },
  defaultData: () => ({
    name: `DP-${uniqueId()}`,
    model_code: 'e2et_order',
    policy_type: 'row',
    priority: 0,
    enabled: true,
  }),
  deleteOperationType: 'delete',
};
