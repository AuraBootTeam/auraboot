import { describe, it, expect } from 'vitest';
import {
  isKindCompatible,
  checkKindCompatibility,
  disabledKindsForCapabilities,
} from '~/shared/utils/kindCapability';
import type { ModelCapabilities } from '~/shared/hooks/useModelCapabilities';

const baseCaps: ModelCapabilities = {
  list: true,
  detail: true,
  create: true,
  update: true,
  delete: true,
  bulkDelete: true,
  export: true,
  sort: true,
  filter: true,
  paginate: true,
  sortableFields: [],
  filterableFields: [],
};

describe('kindCapability.isKindCompatible', () => {
  it('returns true when capabilities are unknown (inconclusive)', () => {
    expect(isKindCompatible('list', undefined)).toBe(true);
    expect(isKindCompatible('form', null)).toBe(true);
  });

  it('returns true when kind is missing', () => {
    expect(isKindCompatible(undefined, baseCaps)).toBe(true);
    expect(isKindCompatible('', baseCaps)).toBe(true);
  });

  it('list needs capabilities.list', () => {
    expect(isKindCompatible('list', baseCaps)).toBe(true);
    expect(isKindCompatible('list', { ...baseCaps, list: false })).toBe(false);
  });

  it('detail needs capabilities.detail', () => {
    expect(isKindCompatible('detail', baseCaps)).toBe(true);
    expect(isKindCompatible('detail', { ...baseCaps, detail: false })).toBe(false);
  });

  it('form needs create OR update', () => {
    expect(isKindCompatible('form', baseCaps)).toBe(true);
    expect(isKindCompatible('form', { ...baseCaps, create: false })).toBe(true);
    expect(isKindCompatible('form', { ...baseCaps, update: false })).toBe(true);
    expect(isKindCompatible('form', { ...baseCaps, create: false, update: false })).toBe(false);
  });

  it('is case-insensitive on kind', () => {
    expect(isKindCompatible('LIST', { ...baseCaps, list: false })).toBe(false);
  });

  it('unknown kind is treated as compatible (do not block)', () => {
    expect(isKindCompatible('kanban', { ...baseCaps, list: false })).toBe(true);
  });
});

describe('kindCapability.checkKindCompatibility', () => {
  it('returns a reason when incompatible', () => {
    const r = checkKindCompatibility('list', { ...baseCaps, list: false });
    expect(r.compatible).toBe(false);
    expect(r.reason).toMatch(/list/i);
  });

  it('returns compatible=true without reason when ok', () => {
    const r = checkKindCompatibility('form', baseCaps);
    expect(r.compatible).toBe(true);
    expect(r.reason).toBeUndefined();
  });
});

describe('kindCapability.disabledKindsForCapabilities', () => {
  it('returns empty when capabilities unknown', () => {
    expect(disabledKindsForCapabilities(undefined)).toEqual([]);
  });

  it('returns list/detail when model supports neither', () => {
    const disabled = disabledKindsForCapabilities({ ...baseCaps, list: false, detail: false });
    expect(disabled).toContain('list');
    expect(disabled).toContain('detail');
    expect(disabled).not.toContain('form');
  });

  it('returns form when model supports neither create nor update', () => {
    const disabled = disabledKindsForCapabilities({
      ...baseCaps,
      create: false,
      update: false,
    });
    expect(disabled).toEqual(['form']);
  });
});
