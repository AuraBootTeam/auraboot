/**
 * SLA Config — Model test configuration for platform-admin plugin.
 * @since 7.0.0
 */

import { uniqueId } from '../../e2e/helpers';
import type { ModelTestConfig } from '../model-test-helper';

export const ADMIN_SLA_CONFIG: ModelTestConfig = {
  modelCode: 'sla_config',
  pageKey: 'sla-config',
  namespace: 'admin',
  commands: {
    create: 'create_sla_config',
    update: 'update_sla_config',
    delete: 'delete_sla_config',
  },
  defaultData: () => ({
    name: `SLA-${uniqueId()}`,
    target_type: 'process',
    deadline_mode: 'fixed',
    deadline_value: 'pt2h',
    business_calendar: false,
    suspend_policy: 'pause',
    enabled: true,
  }),
  deleteOperationType: 'delete',
};
