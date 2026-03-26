/**
 * PCBA Production Plan — Model test configuration.
 * @since 6.1.0
 */

import { uniqueId } from '../../e2e/helpers';
import type { ModelTestConfig } from '../model-test-helper';

export const PCBA_PRODUCTION_PLAN_CONFIG: ModelTestConfig = {
  modelCode: 'pe_production_plan',
  pageKey: 'pe-production-plan',
  namespace: 'pe',
  commands: {
    create: 'create_production_plan',
    update: 'update_production_plan',
    delete: 'delete_production_plan',
  },
  defaultData: () => ({
    pe_pp_name: `E2E Plan ${uniqueId('PP')}`,
    pe_pp_code: `E2E-PP-${Date.now()}`,
  }),
  deleteOperationType: 'delete',
};
