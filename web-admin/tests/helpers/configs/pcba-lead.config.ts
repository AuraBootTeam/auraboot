/**
 * PCBA Lead — Model test configuration.
 * @since 6.1.0
 */

import { uniqueId } from '../../e2e/helpers';
import type { ModelTestConfig } from '../model-test-helper';

export const PCBA_LEAD_CONFIG: ModelTestConfig = {
  modelCode: 'pe_lead',
  pageKey: 'pe-lead',
  namespace: 'pe',
  commands: {
    create: 'create_lead',
    update: 'update_lead',
    delete: 'delete_lead',
  },
  defaultData: () => ({
    pe_lead_company: `E2E Lead ${uniqueId('L')}`,
    pe_lead_contact_name: 'E2E Contact',
    pe_lead_source: 'website',
  }),
  deleteOperationType: 'delete',
};
