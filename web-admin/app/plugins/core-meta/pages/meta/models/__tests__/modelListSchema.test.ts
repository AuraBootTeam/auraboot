import { describe, expect, it } from 'vitest';
import { MODEL_LIST_SCHEMA } from '../modelListSchema';

describe('MODEL_LIST_SCHEMA', () => {
  it('uses sourceType and status filters without modelType', () => {
    const filtersBlock = MODEL_LIST_SCHEMA.blocks.find((block) => block.blockType === 'filters');
    const fields = filtersBlock?.fields ?? [];

    expect(fields.map((field) => field.field)).toEqual(['sourceType', 'status']);
    expect(fields.some((field) => field.field === 'modelType')).toBe(false);
  });

  it('defines the agreed data source options', () => {
    const filtersBlock = MODEL_LIST_SCHEMA.blocks.find((block) => block.blockType === 'filters');
    const sourceTypeField = filtersBlock?.fields?.find((field) => field.field === 'sourceType');
    const data = (sourceTypeField?.dataSource &&
      typeof sourceTypeField.dataSource === 'object' &&
      'data' in sourceTypeField.dataSource
      ? sourceTypeField.dataSource.data
      : []) as Array<{ value: string; label: string }>;

    expect(data).toEqual([
      { value: '', label: '全部来源' },
      { value: 'physical', label: '物理表' },
      { value: 'namedQuery', label: 'NamedQuery' },
      { value: 'sqlView', label: 'SQL View' },
      { value: 'endpoint', label: 'Endpoint' },
    ]);
  });
});
