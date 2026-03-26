/**
 * API Connector — Model test configuration for platform-admin plugin.
 * @since 7.0.0
 */

import { uniqueId } from '../../e2e/helpers';
import type { ModelTestConfig } from '../model-test-helper';

export const ADMIN_API_CONNECTOR_CONFIG: ModelTestConfig = {
  modelCode: 'api_connector',
  pageKey: 'api-connector',
  namespace: 'admin',
  commands: {
    create: 'create_api_connector',
    update: 'update_api_connector',
    delete: 'delete_api_connector',
  },
  defaultData: () => ({
    name: `API-${uniqueId()}`,
    base_url: 'https://api.example.com',
    auth_type: 'none',
    timeout_ms: 10000,
    enabled: true,
  }),
  deleteOperationType: 'delete',
};
