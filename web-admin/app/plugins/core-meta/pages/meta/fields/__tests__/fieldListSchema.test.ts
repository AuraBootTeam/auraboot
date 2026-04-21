import { describe, expect, it } from 'vitest';
import { buildFieldListSchema, FIELD_LIST_DATA_TYPES_FALLBACK } from '../fieldListSchema';

describe('buildFieldListSchema', () => {
  it('uses the agreed field filters without unsupported status', () => {
    const schema = buildFieldListSchema(FIELD_LIST_DATA_TYPES_FALLBACK);
    const filtersBlock = schema.blocks.find((block) => block.blockType === 'filters');
    const fields = filtersBlock?.fields ?? [];

    expect(fields.map((field) => field.field)).toEqual([
      'baseType',
      'semanticType',
      'systemFieldsOnly',
      'unusedOnly',
    ]);
    expect(fields.some((field) => field.field === 'status')).toBe(false);
  });

  it('defines the agreed base type options', () => {
    const schema = buildFieldListSchema(FIELD_LIST_DATA_TYPES_FALLBACK);
    const filtersBlock = schema.blocks.find((block) => block.blockType === 'filters');
    const baseTypeField = filtersBlock?.fields?.find((field) => field.field === 'baseType');
    const data = (baseTypeField?.dataSource &&
      typeof baseTypeField.dataSource === 'object' &&
      'data' in baseTypeField.dataSource
      ? baseTypeField.dataSource.data
      : []) as Array<{ value: string; label: string }>;

    expect(data.slice(0, 4)).toEqual([
      { value: '', label: '全部类型' },
      { value: 'string', label: 'String' },
      { value: 'integer', label: 'Integer' },
      { value: 'long', label: 'Long' },
    ]);
  });
});
