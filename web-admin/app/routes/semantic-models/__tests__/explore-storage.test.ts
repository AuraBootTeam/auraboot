import { describe, expect, it, beforeEach } from 'vitest';
import {
  saveExploration,
  loadExploration,
  clearExploration,
  qualifiedDimension,
} from '../explore-storage';

describe('explore-storage (R4 saved explorations)', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('saves and loads a full exploration round-trip', () => {
    saveExploration({
      modelCode: 'sales',
      metrics: ['total_sales'],
      dimensions: ['order_date', 'region'],
      grains: { order_date: 'month' },
      limit: 50,
    });

    const loaded = loadExploration('sales');
    expect(loaded).toEqual({
      modelCode: 'sales',
      metrics: ['total_sales'],
      dimensions: ['order_date', 'region'],
      grains: { order_date: 'month' },
      limit: 50,
    });
  });

  it('returns null for a missing or corrupt entry', () => {
    expect(loadExploration('nope')).toBeNull();
    localStorage.setItem('semantic_explore:bad', '{not json');
    expect(loadExploration('bad')).toBeNull();
  });

  it('rejects a saved entry whose modelCode does not match the key', () => {
    localStorage.setItem('semantic_explore:sales', JSON.stringify({ modelCode: 'other', metrics: [] }));
    expect(loadExploration('sales')).toBeNull();
  });

  it('normalizes a saved entry with a bogus limit', () => {
    localStorage.setItem(
      'semantic_explore:sales',
      JSON.stringify({ modelCode: 'sales', metrics: ['m'], limit: -5 }),
    );
    expect(loadExploration('sales')?.limit).toBe(100);
  });

  it('clear removes only the model’s own entry', () => {
    saveExploration({ modelCode: 'a', metrics: [], dimensions: [], grains: {}, limit: 10 });
    saveExploration({ modelCode: 'b', metrics: [], dimensions: [], grains: {}, limit: 10 });
    clearExploration('a');
    expect(loadExploration('a')).toBeNull();
    expect(loadExploration('b')).not.toBeNull();
  });

  it('qualifiedDimension appends the __grain suffix only when a grain is chosen', () => {
    expect(qualifiedDimension('sales', 'order_date')).toBe('sales.order_date');
    expect(qualifiedDimension('sales', 'order_date', 'month')).toBe('sales.order_date__month');
  });
});
