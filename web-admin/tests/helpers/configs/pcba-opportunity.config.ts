/**
 * PCBA Opportunity — Model test configuration.
 * @since 6.1.0
 */

import { uniqueId } from '../../e2e/helpers';
import type { ModelTestConfig } from '../model-test-helper';

export const PCBA_OPPORTUNITY_CONFIG: ModelTestConfig = {
  modelCode: 'pe_opportunity',
  pageKey: 'pe-opportunity',
  namespace: 'pe',
  commands: {
    create: 'create_opportunity',
    update: 'update_opportunity',
    delete: 'delete_opportunity',
  },
  defaultData: () => ({
    pe_opp_name: `E2E Opportunity ${uniqueId('O')}`,
    pe_opp_expected_amount: 50000,
    pe_opp_probability: 60,
  }),
  deleteOperationType: 'delete',
};
