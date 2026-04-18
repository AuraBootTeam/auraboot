/**
 * Capability Validator
 *
 * Design-time validation that compares a ListViewModel or DetailViewModel
 * against the model's declared capabilities. Blocks saves that would produce
 * invalid runtime behaviour (filter on non-whitelisted field, bulkDelete when
 * the model does not support it, etc.) and warns on non-blocking mismatches.
 *
 * Part of P3-T8 (virtual model backend plan).
 */

import type { ListViewModel } from '../list-config/mapper';
import type { DetailViewModel } from '../detail-config/mapper';
import type { ModelCapabilities } from '~/shared/hooks/useModelCapabilities';

export interface ValidationError {
  tab: string;
  field?: string;
  severity: 'error' | 'warning';
  message: string;
}

export function validateListVm(
  vm: ListViewModel,
  caps: ModelCapabilities | undefined,
): ValidationError[] {
  const errors: ValidationError[] = [];
  if (!caps) return errors; // can't validate without capabilities yet

  // Filter fields must be in whitelist
  const filterableSet = new Set(caps.filterableFields);
  for (const f of vm.filters) {
    if (!filterableSet.has(f.field)) {
      errors.push({
        tab: 'filters',
        field: f.field,
        severity: 'error',
        message: `Field "${f.field}" is not in the filterable whitelist`,
      });
    }
  }

  // Default sort field must be in whitelist
  const sortableSet = new Set(caps.sortableFields);
  if (vm.behavior.defaultSortField && !sortableSet.has(vm.behavior.defaultSortField)) {
    errors.push({
      tab: 'behavior',
      field: vm.behavior.defaultSortField,
      severity: 'error',
      message: `Default sort field "${vm.behavior.defaultSortField}" is not in the sortable whitelist`,
    });
  }

  // Toolbar preset gating against capabilities
  if (vm.toolbar.presets.includes('bulkDelete') && !caps.bulkDelete) {
    errors.push({
      tab: 'toolbar',
      severity: 'error',
      message: 'Model does not support bulkDelete capability',
    });
  }
  if (vm.toolbar.presets.includes('create') && !caps.create) {
    errors.push({
      tab: 'toolbar',
      severity: 'error',
      message: 'Model does not support create capability',
    });
  }
  if (vm.toolbar.presets.includes('export') && !caps.export) {
    errors.push({
      tab: 'toolbar',
      severity: 'warning',
      message: 'Model does not support export; button will render but runtime will fail',
    });
  }

  // Pagination (pageSize>0) requires capabilities.paginate
  if (vm.behavior.pageSize > 0 && !caps.paginate) {
    errors.push({
      tab: 'behavior',
      severity: 'warning',
      message: 'Model does not support pagination; pageSize will be ignored at runtime',
    });
  }

  // Multi-select requires either bulkDelete or at least one selection-required action
  if (
    vm.behavior.multiSelect &&
    !caps.bulkDelete &&
    !vm.toolbar.customButtons.some((b) => b.requiresSelection)
  ) {
    errors.push({
      tab: 'behavior',
      severity: 'warning',
      message: 'Multi-select is enabled but no action requires a selection',
    });
  }

  return errors;
}

export function validateDetailVm(
  vm: DetailViewModel,
  caps: ModelCapabilities | undefined,
): ValidationError[] {
  const errors: ValidationError[] = [];
  if (!caps) return errors;

  if (vm.actions.presets.includes('edit') && !caps.update) {
    errors.push({
      tab: 'actions',
      severity: 'error',
      message: 'Model does not support update capability',
    });
  }
  if (vm.actions.presets.includes('delete') && !caps.delete) {
    errors.push({
      tab: 'actions',
      severity: 'error',
      message: 'Model does not support delete capability',
    });
  }
  if (!caps.detail) {
    errors.push({
      tab: 'sections',
      severity: 'error',
      message: 'Model does not support detail view',
    });
  }

  return errors;
}

export function hasBlockingErrors(errors: ValidationError[]): boolean {
  return errors.some((e) => e.severity === 'error');
}
