import { renderHook } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { useListExport } from '../useListExport';
import type { ButtonConfig } from '~/framework/meta/schemas/types';

function options(selectedCount: number): Parameters<typeof useListExport>[0] {
  return {
    runtime: null,
    canUseButton: () => true,
    pagination: { current: 1, pageSize: 20, total: 50 },
    data: [],
    filters: {},
    tableBulkActions: [],
    selectedIdList: selectedCount ? ['record'] : [],
    allMatchingSelected: selectedCount > 1,
    effectiveSelectedCount: selectedCount,
    tableColumns: [],
    tableBulkCapabilities: undefined,
    hasPermission: () => true,
    getTabFilter: () => null,
    chipFilters: [],
    modelCode: 'delivery',
    keywordRef: { current: '' },
    showErrorToast: () => {},
    allMatchingExcludedIds: [],
    explicitSelectedIds: [],
    effectiveViewConfig: undefined,
    schemaExtension: {},
    ensureViewAndUpdateConfig: async () => {},
  };
}
describe('toolbar enablement uses current selection and shared expression precedence', () => {
  it('disables empty selection, enables selected/all-matching and disables after clearing', () => {
    const { result, rerender } = renderHook(({ count }: { count: number }) => useListExport(options(count)), {
      initialProps: { count: 0 },
    });
    const action = {
      code: 'deleteSelected',
      enableWhen: '${state.selectedCount > 0}',
    } as ButtonConfig;
    expect(result.current.evaluateButtonDisabled(action)).toBe(true);
    rerender({ count: 1 });
    expect(result.current.evaluateButtonDisabled(action)).toBe(false);
    rerender({ count: 50 });
    expect(result.current.evaluateButtonDisabled(action)).toBe(false);
    rerender({ count: 0 });
    expect(result.current.evaluateButtonDisabled(action)).toBe(true);
    expect(result.current.evaluateButtonDisabled({ ...action, disabled: true })).toBe(true);
    expect(
      result.current.evaluateButtonDisabled({
        code: 'create',
        disableWhen: 'total > 0',
      } as ButtonConfig),
    ).toBe(true);
  });
});
