/**
 * Binding Service Module
 *
 * Field-component binding system for the designer.
 */

export { FieldBindingService, fieldBindingService, default } from './FieldBindingService';
export { BindingSyncManager, bindingSyncManager } from './BindingSyncManager';
export { BindingValidator, bindingValidator } from './BindingValidator';

export type {
  FieldBinding,
  BindingMode,
  BindingStatus,
  FieldChanges,
  ValidationRule,
  BindingValidationResult,
  BindingSuggestion,
  FieldBindingGroup,
  ComponentBindingGroup,
  BindingChangeEvent,
  ViewModelFieldInfo,
  BindingServiceOptions,
} from './types';

export type { ValidationSummary } from './BindingValidator';
