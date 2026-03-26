/**
 * Workbench Panels Module
 *
 * Unified exports for all workbench panels.
 *
 * @since 3.2.0
 */

// Core panels
export * from './properties';
export * from './fields';
export * from './actions';

// Data panels
export * from './datasource';
export * from './binding';
export * from './computed';

// Utility panels
export * from './filters';
export {
  LinkagePanel,
  LinkageRuleEditor,
  TriggerConfig,
  LinkageActionConfig,
  FieldMultiSelect,
} from './linkage';
export type {
  LinkageRule,
  LinkageAction,
  LinkageTrigger,
  LinkageActionType,
  TriggerEvent,
  ValidationRule,
} from './linkage';
export * from './debug';

// Enhanced panels
export * from './property-editors';
export * from './shortcuts';
export * from './preview';

// Page management
export * from './page-list';
export * from './new-page-wizard';

// Settings
export * from './settings';

// Version history
export * from './version-history';

// Import/Export
export * from './import-export';
