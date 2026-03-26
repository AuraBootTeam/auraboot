/**
 * Webhook — Model test configuration for platform-admin plugin.
 * @since 7.0.0
 */

import { uniqueId } from '../../e2e/helpers';
import type { ModelTestConfig } from '../model-test-helper';

export const ADMIN_WEBHOOK_CONFIG: ModelTestConfig = {
  modelCode: 'webhook',
  pageKey: 'webhook',
  namespace: 'admin',
  commands: {
    create: 'create_webhook',
    update: 'update_webhook',
    delete: 'delete_webhook',
  },
  defaultData: () => ({
    name: `WH-${uniqueId()}`,
    target_url: 'https://example.com/webhook',
    event_type: 'record_created',
    max_retries: 3,
    timeout_ms: 10000,
    enabled: true,
  }),
  deleteOperationType: 'delete',
};
