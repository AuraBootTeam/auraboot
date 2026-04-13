/**
 * Workflows Index
 *
 * Re-exports all workflow functions for convenient importing.
 *
 * @example
 * ```typescript
 * import { createModel, ensureLoggedIn } from '../workflows';
 * ```
 *
 * @since 4.0.0
 */

// Login workflows
export { ensureLoggedIn, performLogin, performLogout, TEST_CREDENTIALS } from './login.workflow';

// Model workflows
export {
  createModel,
  createAndPublishModel,
  deleteModel,
  createMultipleModels,
  type CreateModelOptions,
  type CreateModelResult,
} from './create-model.workflow';

// Record workflows
export {
  createRecord,
  deleteRecord,
  navigateToCreateRecordPage,
  navigateToRecordList,
  navigateToRecordDetail,
  type CreateRecordOptions,
  type CreateRecordResult,
} from './create-record.workflow';
