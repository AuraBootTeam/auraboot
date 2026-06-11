import { describe, it, expect } from 'vitest';
import {
  and, or, negate, cmp, group, not, lit, path, evaluatePreview, isMatch,
  serialize, parse, toNaturalLanguage, checkComplexity,
  type ScopedContext, type ConditionNode, type PathOperand,
} from '../conditionAst';

const rec = (data: Record<string, unknown>): ScopedContext => ({ record: { data } });

describe('three-valued logic (mirrors backend Truth)', () => {
  it('and/or/negate tables', () => {
    expect(and('TRUE', 'UNKNOWN')).toBe('UNKNOWN');
    expect(and('FALSE', 'UNKNOWN')).toBe('FALSE');
    expect(or('FALSE', 'UNKNOWN')).toBe('UNKNOWN');
    expect(or('TRUE', 'UNKNOWN')).toBe('TRUE');
    expect(negate('UNKNOWN')).toBe('UNKNOWN');
  });
});

describe('evaluatePreview — happy', () => {
  it('enum equality + numeric GT', () => {
    expect(evaluatePreview(cmp(path('record', 'data.priority', 'enum'), 'EQ', lit('HIGH', 'enum')),
      rec({ priority: 'HIGH' }))).toBe('TRUE');
    expect(evaluatePreview(cmp(path('record', 'data.amount', 'decimal'), 'GT', lit(10000, 'decimal')),
      rec({ amount: 20000 }))).toBe('TRUE');
  });

  it('nested AND/OR (mockup R-101)', () => {
    const node: ConditionNode = group('AND', [
      cmp(path('record', 'data.priority', 'enum'), 'EQ', lit('HIGH', 'enum')),
      group('OR', [
        cmp(path('record', 'data.amount', 'decimal'), 'GT', lit(10000, 'decimal')),
        cmp(path('record', 'data.customerLevel', 'enum'), 'EQ', lit('VIP', 'enum')),
      ]),
    ]);
    expect(evaluatePreview(node, rec({ priority: 'HIGH', amount: 500, customerLevel: 'VIP' }))).toBe('TRUE');
    expect(evaluatePreview(node, rec({ priority: 'NORMAL', amount: 20000, customerLevel: 'VIP' }))).toBe('FALSE');
  });

  it('IN and BETWEEN', () => {
    expect(evaluatePreview(cmp(path('record', 'data.risk', 'enum'), 'IN', lit(['High', 'Critical'], 'enum')),
      rec({ risk: 'Critical' }))).toBe('TRUE');
    expect(evaluatePreview(cmp(path('record', 'data.amount', 'decimal'), 'BETWEEN', lit([1000, 5000], 'decimal')),
      rec({ amount: 3000 }))).toBe('TRUE');
  });

  it('date and datetime comparisons use ISO ordering', () => {
    expect(evaluatePreview(cmp(path('record', 'data.submittedOn', 'date'), 'GTE', lit('2026-06-01', 'date')),
      rec({ submittedOn: '2026-06-15' }))).toBe('TRUE');
    expect(evaluatePreview(cmp(path('record', 'data.submittedOn', 'date'), 'BETWEEN', lit(['2026-06-01', '2026-06-30'], 'date')),
      rec({ submittedOn: '2026-06-15' }))).toBe('TRUE');
    expect(evaluatePreview(cmp(path('record', 'data.submittedAt', 'datetime'), 'LT', lit('2026-06-15T10:30:00Z', 'datetime')),
      rec({ submittedAt: '2026-06-15T09:00:00Z' }))).toBe('TRUE');
  });
});

describe('evaluatePreview — sad / edge / corner', () => {
  it('missing field -> UNKNOWN, not a match', () => {
    const t = evaluatePreview(cmp(path('record', 'data.priority', 'enum'), 'EQ', lit('HIGH', 'enum')),
      rec({ amount: 1 }));
    expect(t).toBe('UNKNOWN');
    expect(isMatch(t)).toBe(false);
  });

  it('present null compared with value -> UNKNOWN', () => {
    expect(evaluatePreview(cmp(path('record', 'data.priority', 'enum'), 'EQ', lit('HIGH', 'enum')),
      rec({ priority: null }))).toBe('UNKNOWN');
  });

  it('numeric compare on non-numeric -> UNKNOWN (no coercion)', () => {
    expect(evaluatePreview(cmp(path('record', 'data.amount', 'decimal'), 'GT', lit(100, 'decimal')),
      rec({ amount: 'NaNish' }))).toBe('UNKNOWN');
  });

  it('IS_NULL / IS_EMPTY semantics', () => {
    expect(evaluatePreview(cmp(path('record', 'data.x', 'enum'), 'IS_NULL'), rec({ x: null }))).toBe('TRUE');
    expect(evaluatePreview(cmp(path('record', 'data.x', 'enum'), 'IS_NULL'), rec({ y: 1 }))).toBe('TRUE'); // missing
    expect(evaluatePreview(cmp(path('record', 'data.t', 'string'), 'IS_EMPTY'), rec({ t: '' }))).toBe('TRUE');
    expect(evaluatePreview(cmp(path('record', 'data.t', 'string'), 'IS_EMPTY'), rec({ t: 'x' }))).toBe('FALSE');
  });

  it('three-valued group: TRUE AND UNKNOWN = UNKNOWN', () => {
    const node = group('AND', [
      cmp(path('record', 'data.priority', 'enum'), 'EQ', lit('HIGH', 'enum')),
      cmp(path('record', 'data.missing', 'enum'), 'EQ', lit('X', 'enum')),
    ]);
    expect(evaluatePreview(node, rec({ priority: 'HIGH' }))).toBe('UNKNOWN');
    expect(evaluatePreview(node, rec({ priority: 'LOW' }))).toBe('FALSE');
  });

  it('string compare is case-sensitive; NOT negates', () => {
    expect(evaluatePreview(cmp(path('record', 'data.t', 'string'), 'EQ', lit('Hello', 'string')),
      rec({ t: 'hello' }))).toBe('FALSE');
    expect(evaluatePreview(not(cmp(path('record', 'data.p', 'enum'), 'EQ', lit('HIGH', 'enum'))),
      rec({ p: 'LOW' }))).toBe('TRUE');
  });

  it('disabled leaf is skipped in its group', () => {
    const disabled = { ...cmp(path('record', 'data.p', 'enum'), 'EQ', lit('NEVER', 'enum')), enabled: false };
    const node = group('AND', [disabled, cmp(path('record', 'data.amount', 'decimal'), 'GT', lit(10, 'decimal'))]);
    expect(evaluatePreview(node, rec({ amount: 100 }))).toBe('TRUE');
  });

  it('CHANGED compares before/after', () => {
    const node = cmp(path('after', 'status', 'enum'), 'CHANGED');
    expect(evaluatePreview(node, { before: { status: 'Draft' }, after: { status: 'Submitted' } })).toBe('TRUE');
    expect(evaluatePreview(node, { before: { status: 'Draft' }, after: { status: 'Draft' } })).toBe('FALSE');
  });
});

describe('serialize round-trip + natural language + complexity', () => {
  it('serialize/parse preserves evaluation', () => {
    const node = cmp(path('record', 'data.amount', 'decimal'), 'GT', lit(10000, 'decimal'));
    const back = parse(serialize(node));
    expect(evaluatePreview(back, rec({ amount: 20000 }))).toBe('TRUE');
  });

  it('natural language with label resolver', () => {
    const labelOf = (o: PathOperand) => (o.path === 'data.priority' ? '优先级' : o.path);
    const node = group('AND', [
      cmp(path('record', 'data.priority', 'enum'), 'EQ', lit('HIGH', 'enum')),
    ]);
    const nl = toNaturalLanguage(node, labelOf);
    expect(nl).toContain('优先级');
    expect(nl).toContain('等于');
  });

  it('complexity limits flag deep AST and large IN', () => {
    let deep: ConditionNode = cmp(path('record', 'data.x', 'integer'), 'GT', lit(1, 'integer'));
    for (let i = 0; i < 10; i += 1) deep = group('AND', [deep]);
    expect(checkComplexity(deep).some((v) => v.includes('depth'))).toBe(true);

    const bigIn = cmp(path('record', 'data.x', 'enum'), 'IN', lit(Array.from({ length: 300 }, (_, i) => i), 'enum'));
    expect(checkComplexity(bigIn).some((v) => v.includes('IN set size'))).toBe(true);
  });
});
