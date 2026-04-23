import { describe, it, expect } from 'vitest';
import {
  validateListVm,
  validateDetailVm,
  hasBlockingErrors,
} from '../capabilityValidator';
import type { ModelCapabilities } from '~/shared/hooks/useModelCapabilities';
import type { ListViewModel } from '../../list-config/mapper';
import type { DetailViewModel } from '../../detail-config/mapper';

const caps: ModelCapabilities = {
  list: true,
  detail: true,
  create: true,
  update: false,
  delete: false,
  bulkDelete: false,
  export: false,
  sort: true,
  filter: true,
  paginate: true,
  sortableFields: ['name', 'created_at'],
  filterableFields: ['status'],
};

const baseVm: ListViewModel = {
  columns: [],
  filters: [],
  toolbar: { presets: [], customButtons: [] },
  behavior: {
    pageSize: 20,
    multiSelect: false,
    defaultSortOrder: 'desc',
    rowClickAction: 'detail',
  },
};

describe('validateListVm', () => {
  it('passes when VM is empty against full-feature caps', () => {
    const errors = validateListVm(baseVm, caps);
    expect(errors).toEqual([]);
  });

  it('rejects filter on non-whitelisted field', () => {
    const errors = validateListVm(
      { ...baseVm, filters: [{ field: 'not_whitelisted' }] },
      caps,
    );
    expect(errors.filter((e) => e.severity === 'error')).toHaveLength(1);
    expect(errors[0].tab).toBe('filters');
  });

  it('rejects defaultSortField outside whitelist', () => {
    const vm: ListViewModel = {
      ...baseVm,
      behavior: { ...baseVm.behavior, defaultSortField: 'unknown' },
    };
    const errors = validateListVm(vm, caps);
    expect(hasBlockingErrors(errors)).toBe(true);
  });

  it('accepts defaultSortField within whitelist', () => {
    const vm: ListViewModel = {
      ...baseVm,
      behavior: { ...baseVm.behavior, defaultSortField: 'name' },
    };
    const errors = validateListVm(vm, caps);
    expect(errors).toEqual([]);
  });

  it('rejects bulkDelete preset when caps.bulkDelete=false', () => {
    const vm: ListViewModel = {
      ...baseVm,
      toolbar: { presets: ['bulkDelete'], customButtons: [] },
    };
    const errors = validateListVm(vm, caps);
    expect(hasBlockingErrors(errors)).toBe(true);
  });

  it('warns (non-blocking) when export preset but caps.export=false', () => {
    const vm: ListViewModel = {
      ...baseVm,
      toolbar: { presets: ['export'], customButtons: [] },
    };
    const errors = validateListVm(vm, caps);
    expect(errors.some((e) => e.severity === 'warning')).toBe(true);
    expect(hasBlockingErrors(errors)).toBe(false);
  });

  it('warns when pagination unsupported but pageSize > 0', () => {
    const noPage: ModelCapabilities = { ...caps, paginate: false };
    const errors = validateListVm(baseVm, noPage);
    expect(errors.some((e) => e.tab === 'behavior' && e.severity === 'warning')).toBe(
      true,
    );
  });

  it('warns when multiSelect enabled but no selection action exists', () => {
    const vm: ListViewModel = {
      ...baseVm,
      behavior: { ...baseVm.behavior, multiSelect: true },
    };
    const errors = validateListVm(vm, caps);
    expect(errors.some((e) => e.tab === 'behavior' && e.severity === 'warning')).toBe(
      true,
    );
  });

  it('does not warn when multiSelect paired with selection-required custom button', () => {
    const vm: ListViewModel = {
      ...baseVm,
      behavior: { ...baseVm.behavior, multiSelect: true },
      toolbar: {
        presets: [],
        customButtons: [
          { label: 'Archive', command: 'archive', requiresSelection: true },
        ],
      },
    };
    const errors = validateListVm(vm, caps);
    expect(errors.filter((e) => e.tab === 'behavior')).toEqual([]);
  });

  it('returns empty when caps undefined', () => {
    expect(validateListVm(baseVm, undefined)).toEqual([]);
  });
});

describe('validateDetailVm', () => {
  const detailVm: DetailViewModel = {
    sections: [],
    actions: { presets: ['edit'], customButtons: [] },
    passthroughBlocks: [],
  };

  it('rejects edit preset when caps.update=false', () => {
    const errors = validateDetailVm(detailVm, caps);
    expect(hasBlockingErrors(errors)).toBe(true);
    expect(errors.some((e) => e.tab === 'actions')).toBe(true);
  });

  it('rejects delete preset when caps.delete=false', () => {
    const vm: DetailViewModel = {
      sections: [],
      actions: { presets: ['delete'], customButtons: [] },
      passthroughBlocks: [],
    };
    const errors = validateDetailVm(vm, caps);
    expect(errors.some((e) => e.tab === 'actions' && e.severity === 'error')).toBe(
      true,
    );
  });

  it('rejects when caps.detail=false', () => {
    const noDetailCaps: ModelCapabilities = { ...caps, detail: false };
    const errors = validateDetailVm(
      { sections: [], actions: { presets: [], customButtons: [] }, passthroughBlocks: [] },
      noDetailCaps,
    );
    expect(errors.some((e) => e.tab === 'sections')).toBe(true);
  });

  it('returns empty when caps undefined', () => {
    expect(
      validateDetailVm(
        { sections: [], actions: { presets: [], customButtons: [] }, passthroughBlocks: [] },
        undefined,
      ),
    ).toEqual([]);
  });
});
