import { describe, expect, it } from 'vitest';
import {
  bindSkill,
  editableSchema,
  inputSchema,
  schemaFromFields,
  stringList,
} from '../skillDefinition';

describe('skill authoring safety', () => {
  it('retains all existing bindings and does not duplicate the added skill', () => {
    expect(bindSkill('["existing","bom"]', 'bom')).toEqual(['existing', 'bom']);
    expect(bindSkill(['existing'], 'bom')).toEqual(['existing', 'bom']);
    expect(() => bindSkill('{"existing":true}', 'bom')).toThrow();
  });
  it.each([
    { type: 'object', properties: { amount: { type: 'number', minimum: 0 } } },
    { type: 'object', properties: { items: { type: 'array', items: { type: 'string' } } } },
    { type: 'object', oneOf: [{ required: ['code'] }] },
    { type: 'object', $ref: 'https://invalid.example/schema' },
  ])('refuses simplified authoring of constrained or nested schemas: %j', (schema) => {
    expect(editableSchema(schema)).toBe(false);
  });
  it('preserves additional-properties policy and property descriptions when editing labels', () => {
    const schema = inputSchema(
      '{"type":"object","properties":{"code":{"type":"string","description":"Business code"}},"additionalProperties":false}',
    );
    expect(editableSchema(schema)).toBe(true);
    expect(
      schemaFromFields(
        [{ name: 'code', title: 'Product', type: 'string', required: true }],
        schema,
      ),
    ).toEqual({
      type: 'object',
      properties: { code: { type: 'string', title: 'Product', description: 'Business code' } },
      required: ['code'],
      additionalProperties: false,
    });
  });
  it('rejects duplicate or invalid input names before persistence', () => {
    const field = { name: 'part', title: 'Part', type: 'string' as const, required: false };
    expect(() => schemaFromFields([field, field], {})).toThrow('invalidFields');
    expect(() => schemaFromFields([{ ...field, name: 'bad-name' }], {})).toThrow('invalidFields');
    expect(() => stringList('[1]')).toThrow();
  });
});
