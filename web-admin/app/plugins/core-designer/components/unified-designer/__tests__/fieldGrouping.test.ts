import { describe, expect, it } from 'vitest';
import { fieldGroupKey, groupModelFields } from '../utils/fieldGrouping';
import type { ModelFieldDefinition } from '../types';

function field(partial: Partial<ModelFieldDefinition> & { code: string }): ModelFieldDefinition {
  return { modelCode: 'm', label: partial.code, ...partial };
}

describe('fieldGrouping', () => {
  it('derives a coarse group from field type / refTarget / dictCode', () => {
    expect(fieldGroupKey(field({ code: 'a', type: 'string' }))).toBe('text');
    expect(fieldGroupKey(field({ code: 'b', type: 'decimal' }))).toBe('number');
    expect(fieldGroupKey(field({ code: 'c', type: 'enum' }))).toBe('choice');
    expect(fieldGroupKey(field({ code: 'd', type: 'string', dictCode: 'gender' }))).toBe('choice');
    expect(fieldGroupKey(field({ code: 'e', type: 'datetime' }))).toBe('datetime');
    expect(fieldGroupKey(field({ code: 'f', type: 'boolean' }))).toBe('boolean');
    expect(fieldGroupKey(field({ code: 'g', type: 'string', refTarget: { modelCode: 'user' } }))).toBe(
      'relation',
    );
    expect(fieldGroupKey(field({ code: 'h', type: 'file' }))).toBe('file');
    expect(fieldGroupKey(field({ code: 'i', type: 'json' }))).toBe('json');
  });

  it('groups fields in stable order and skips empty groups', () => {
    const groups = groupModelFields([
      field({ code: 'amount', type: 'decimal' }),
      field({ code: 'title', type: 'string' }),
      field({ code: 'status', type: 'enum' }),
    ]);
    expect(groups.map((group) => group.key)).toEqual(['text', 'number', 'choice']);
    expect(groups.find((group) => group.key === 'text')?.fields.map((f) => f.code)).toEqual([
      'title',
    ]);
  });
});
