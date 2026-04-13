import { describe, it, expect } from 'vitest';
import { DEFAULT_COL_SPAN_BY_TYPE, getDefaultColSpan } from '../layout-constants';

describe('DEFAULT_COL_SPAN_BY_TYPE', () => {
  it('returns 4 for stat-card', () => {
    expect(getDefaultColSpan('stat-card')).toBe(4);
  });
  it('returns 6 for chart', () => {
    expect(getDefaultColSpan('chart')).toBe(6);
  });
  it('returns 6 for form-section', () => {
    expect(getDefaultColSpan('form-section')).toBe(6);
  });
  it('returns 12 for table', () => {
    expect(getDefaultColSpan('table')).toBe(12);
  });
  it('returns 12 for toolbar', () => {
    expect(getDefaultColSpan('toolbar')).toBe(12);
  });
  it('returns 12 for filters', () => {
    expect(getDefaultColSpan('filters')).toBe(12);
  });
  it('returns 12 for sub-table', () => {
    expect(getDefaultColSpan('sub-table')).toBe(12);
  });
  it('returns 12 for tabs', () => {
    expect(getDefaultColSpan('tabs')).toBe(12);
  });
  it('returns 12 for divider', () => {
    expect(getDefaultColSpan('divider')).toBe(12);
  });
  it('returns 6 for rich-text', () => {
    expect(getDefaultColSpan('rich-text')).toBe(6);
  });
  it('returns 6 for detail-section', () => {
    expect(getDefaultColSpan('detail-section')).toBe(6);
  });
  it('returns 6 for unknown block type', () => {
    expect(getDefaultColSpan('custom-widget')).toBe(6);
  });
});
