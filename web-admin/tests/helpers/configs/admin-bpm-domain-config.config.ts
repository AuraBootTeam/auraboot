/**
 * BPM Domain Config — Model test configuration for platform-admin plugin.
 * @since 7.0.0
 */

import { uniqueId } from '../../e2e/helpers';
import type { ModelTestConfig } from '../model-test-helper';

export const ADMIN_BPM_DOMAIN_CONFIG: ModelTestConfig = {
  modelCode: 'bpm_domain_config',
  pageKey: 'bpm-domain-config',
  namespace: 'admin',
  commands: {
    create: 'create_bpm_domain_config',
    update: 'update_bpm_domain_config',
    delete: 'delete_bpm_domain_config',
  },
  defaultData: () => ({
    domain_code: `DOM-${uniqueId()}`,
    domain_name: `Domain ${uniqueId()}`,
    enabled: true,
  }),
  deleteOperationType: 'delete',
};
