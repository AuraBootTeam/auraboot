import { describe, it, expect } from 'vitest';
import { serialize, deserialize } from '../serializer';
import type { ConditionGroup } from '../types';

describe('serialize', () => {
  it('serializes a single string equality condition', () => {
    const group: ConditionGroup = {
      operator: 'and',
      conditions: [{ id: '1', field: 'status', operator: '===', value: 'draft' }],
    };
    expect(serialize(group)).toBe("status === 'draft'");
  });

  it('serializes a single number comparison', () => {
    const group: ConditionGroup = {
      operator: 'and',
      conditions: [{ id: '1', field: 'amount', operator: '>', value: '1000' }],
    };
    expect(serialize(group)).toBe('amount > 1000');
  });

  it('serializes boolean value without quotes', () => {
    const group: ConditionGroup = {
      operator: 'and',
      conditions: [{ id: '1', field: 'active', operator: '===', value: 'true' }],
    };
    expect(serialize(group)).toBe('active === true');
  });

  it('serializes null/undefined without quotes', () => {
    const group: ConditionGroup = {
      operator: 'and',
      conditions: [{ id: '1', field: 'name', operator: '!==', value: 'null' }],
    };
    expect(serialize(group)).toBe('name !== null');
  });

  it('serializes includes operator', () => {
    const group: ConditionGroup = {
      operator: 'and',
      conditions: [{ id: '1', field: '$user.roles', operator: 'includes', value: 'ADMIN' }],
    };
    expect(serialize(group)).toBe("$user.roles.includes('ADMIN')");
  });

  it('serializes !includes operator', () => {
    const group: ConditionGroup = {
      operator: 'and',
      conditions: [{ id: '1', field: '$user.roles', operator: '!includes', value: 'GUEST' }],
    };
    expect(serialize(group)).toBe("!$user.roles.includes('GUEST')");
  });

  it('joins multiple conditions with && for AND', () => {
    const group: ConditionGroup = {
      operator: 'and',
      conditions: [
        { id: '1', field: 'status', operator: '===', value: 'draft' },
        { id: '2', field: 'amount', operator: '>', value: '1000' },
      ],
    };
    expect(serialize(group)).toBe("status === 'draft' && amount > 1000");
  });

  it('joins multiple conditions with || for OR', () => {
    const group: ConditionGroup = {
      operator: 'or',
      conditions: [
        { id: '1', field: 'status', operator: '===', value: 'draft' },
        { id: '2', field: 'status', operator: '===', value: 'pending' },
      ],
    };
    expect(serialize(group)).toBe("status === 'draft' || status === 'pending'");
  });

  it('returns empty string for empty conditions', () => {
    const group: ConditionGroup = { operator: 'and', conditions: [] };
    expect(serialize(group)).toBe('');
  });

  it('serializes decimal numbers without quotes', () => {
    const group: ConditionGroup = {
      operator: 'and',
      conditions: [{ id: '1', field: 'rate', operator: '>=', value: '3.14' }],
    };
    expect(serialize(group)).toBe('rate >= 3.14');
  });
});

describe('deserialize', () => {
  it('deserializes a single string equality', () => {
    const result = deserialize("status === 'draft'");
    expect(result).not.toBeNull();
    expect(result!.operator).toBe('and');
    expect(result!.conditions).toHaveLength(1);
    expect(result!.conditions[0].field).toBe('status');
    expect(result!.conditions[0].operator).toBe('===');
    expect(result!.conditions[0].value).toBe('draft');
  });

  it('deserializes a number comparison', () => {
    const result = deserialize('amount > 1000');
    expect(result).not.toBeNull();
    expect(result!.conditions[0].field).toBe('amount');
    expect(result!.conditions[0].operator).toBe('>');
    expect(result!.conditions[0].value).toBe('1000');
  });

  it('deserializes boolean value', () => {
    const result = deserialize('active === true');
    expect(result).not.toBeNull();
    expect(result!.conditions[0].value).toBe('true');
  });

  it('deserializes includes expression', () => {
    const result = deserialize("$user.roles.includes('ADMIN')");
    expect(result).not.toBeNull();
    expect(result!.conditions[0].field).toBe('$user.roles');
    expect(result!.conditions[0].operator).toBe('includes');
    expect(result!.conditions[0].value).toBe('ADMIN');
  });

  it('deserializes !includes expression', () => {
    const result = deserialize("!$user.roles.includes('GUEST')");
    expect(result).not.toBeNull();
    expect(result!.conditions[0].field).toBe('$user.roles');
    expect(result!.conditions[0].operator).toBe('!includes');
    expect(result!.conditions[0].value).toBe('GUEST');
  });

  it('deserializes AND conditions', () => {
    const result = deserialize("status === 'draft' && amount > 1000");
    expect(result).not.toBeNull();
    expect(result!.operator).toBe('and');
    expect(result!.conditions).toHaveLength(2);
  });

  it('deserializes OR conditions', () => {
    const result = deserialize("status === 'draft' || status === 'pending'");
    expect(result).not.toBeNull();
    expect(result!.operator).toBe('or');
    expect(result!.conditions).toHaveLength(2);
  });

  it('returns null for complex expressions', () => {
    const result = deserialize("status === 'draft' && (amount > 1000 || priority === 'high')");
    expect(result).toBeNull();
  });

  it('returns null for empty string', () => {
    const result = deserialize('');
    expect(result).toBeNull();
  });

  it('returns null for function call expressions', () => {
    const result = deserialize("utils.formatDate(form.createdAt) === '2026-01-01'");
    expect(result).toBeNull();
  });

  it('round-trips correctly: serialize(deserialize(expr)) === expr', () => {
    const expressions = [
      "status === 'draft'",
      'amount > 1000',
      'active === true',
      "status === 'draft' && amount > 1000",
      "status === 'draft' || status === 'pending'",
      "$user.roles.includes('ADMIN')",
      "!$user.roles.includes('GUEST')",
    ];
    for (const expr of expressions) {
      const group = deserialize(expr);
      expect(group).not.toBeNull();
      expect(serialize(group!)).toBe(expr);
    }
  });
});
