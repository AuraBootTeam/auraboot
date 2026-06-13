import { describe, it, expect, beforeEach } from 'vitest';
import { ExpressionEvaluator } from '../ExpressionEvaluator';
import type { ActionContext } from '../types';

const makeContext = (overrides: Partial<ActionContext> = {}): ActionContext => ({
  componentId: 'comp1',
  pageId: 'page1',
  pageState: {},
  globalState: {},
  env: {},
  utils: {
    formatDate: () => '',
    formatNumber: () => '',
    validateEmail: () => false,
    generateId: () => 'id',
  },
  ...overrides,
});

describe('ExpressionEvaluator', () => {
  let evaluator: ExpressionEvaluator;

  beforeEach(() => {
    // Reset singleton for test isolation
    (ExpressionEvaluator as any).instance = undefined;
    evaluator = ExpressionEvaluator.getInstance();
  });

  describe('getInstance / singleton', () => {
    it('returns the same instance on repeated calls', () => {
      const a = ExpressionEvaluator.getInstance();
      const b = ExpressionEvaluator.getInstance();
      expect(a).toBe(b);
    });
  });

  describe('evaluate – non-string passthrough', () => {
    it('returns numbers unchanged', () => {
      expect(ExpressionEvaluator.evaluate(42, makeContext())).toBe(42);
    });

    it('returns booleans unchanged', () => {
      expect(ExpressionEvaluator.evaluate(true, makeContext())).toBe(true);
    });

    it('returns null unchanged', () => {
      expect(ExpressionEvaluator.evaluate(null, makeContext())).toBeNull();
    });

    it('returns objects unchanged', () => {
      const obj = { a: 1 };
      expect(ExpressionEvaluator.evaluate(obj, makeContext())).toBe(obj);
    });
  });

  describe('evaluate – plain strings (not expressions)', () => {
    it('returns plain string as-is', () => {
      expect(ExpressionEvaluator.evaluate('hello', makeContext())).toBe('hello');
    });

    it('returns empty string as-is', () => {
      expect(ExpressionEvaluator.evaluate('', makeContext())).toBe('');
    });
  });

  describe('evaluate – {{...}} expressions', () => {
    it('evaluates arithmetic', () => {
      expect(ExpressionEvaluator.evaluate('{{1 + 2}}', makeContext())).toBe(3);
    });

    it('evaluates Math.max', () => {
      expect(ExpressionEvaluator.evaluate('{{Math.max(3, 7)}}', makeContext())).toBe(7);
    });

    it('evaluates string concatenation with state', () => {
      const ctx = makeContext({ pageState: { firstName: 'John', lastName: 'Doe' } });
      expect(ExpressionEvaluator.evaluate('{{$state.firstName + " " + $state.lastName}}', ctx)).toBe(
        'John Doe',
      );
    });

    it('evaluates conditional ternary', () => {
      const ctx = makeContext({ pageState: { age: 20 } });
      expect(
        ExpressionEvaluator.evaluate('{{$state.age >= 18 ? "adult" : "minor"}}', ctx),
      ).toBe('adult');
    });

    it('evaluates JSON.stringify', () => {
      const result = ExpressionEvaluator.evaluate('{{JSON.stringify({key:"v"})}}', makeContext());
      expect(result).toBe('{"key":"v"}');
    });

    it('returns original expression on syntax error', () => {
      const expr = '{{this is not valid @@}}';
      const result = ExpressionEvaluator.evaluate(expr, makeContext());
      expect(result).toBe(expr);
    });
  });

  describe('evaluate – ${...} expressions', () => {
    it('evaluates arithmetic with ${...} syntax', () => {
      expect(ExpressionEvaluator.evaluate('${10 * 5}', makeContext())).toBe(50);
    });

    it('accesses $vars', () => {
      const ctx = makeContext({ vars: { x: 99 } });
      expect(ExpressionEvaluator.evaluate('${$vars.x}', ctx)).toBe(99);
    });

    it('accesses $user', () => {
      const ctx = makeContext({ user: { id: 'u1', name: 'Alice', roles: [] } });
      expect(ExpressionEvaluator.evaluate('${$user.name}', ctx)).toBe('Alice');
    });
  });

  describe('evaluate – dangerous code blocked', () => {
    it('blocks eval()', () => {
      // Should return original expression (error caught inside evaluate)
      const expr = '{{eval("1")}}';
      const result = ExpressionEvaluator.evaluate(expr, makeContext());
      expect(result).toBe(expr);
    });

    it('blocks window access', () => {
      const expr = '{{window.location}}';
      const result = ExpressionEvaluator.evaluate(expr, makeContext());
      expect(result).toBe(expr);
    });

    it('blocks process access', () => {
      const expr = '{{process.env}}';
      const result = ExpressionEvaluator.evaluate(expr, makeContext());
      expect(result).toBe(expr);
    });
  });

  describe('validateExpression', () => {
    it('validates valid expression', () => {
      const result = evaluator.validateExpression('{{1 + 2}}');
      expect(result.valid).toBe(true);
      expect(result.error).toBeUndefined();
    });

    it('returns valid:true for plain strings', () => {
      const result = evaluator.validateExpression('hello world');
      expect(result.valid).toBe(true);
    });

    it('returns invalid for malformed expression', () => {
      // Must use `}}` at end so isExpression() recognises it; `{{(unclosed}` ends
      // with single `}` → treated as plain string → valid:true (not an expression).
      const result = evaluator.validateExpression('{{(unclosed}}');
      expect(result.valid).toBe(false);
      expect(result.error).toBeDefined();
    });
  });

  describe('extractVariables', () => {
    it('returns empty for non-expression', () => {
      expect(evaluator.extractVariables('plain text')).toEqual([]);
    });

    it('extracts variable names from expression', () => {
      const vars = evaluator.extractVariables('{{foo + bar}}');
      expect(vars).toContain('foo');
      expect(vars).toContain('bar');
    });

    it('does not include reserved words', () => {
      const vars = evaluator.extractVariables('{{true && false}}');
      expect(vars).not.toContain('true');
      expect(vars).not.toContain('false');
    });
  });

  describe('evaluateBatch', () => {
    it('evaluates multiple expressions in a batch', () => {
      const ctx = makeContext({ pageState: { n: 5 } });
      const results = evaluator.evaluateBatch(
        {
          a: '{{1 + 1}}',
          b: '{{$state.n * 2}}',
          c: 'literal',
        },
        ctx,
      );
      expect(results.a).toBe(2);
      expect(results.b).toBe(10);
      expect(results.c).toBe('literal');
    });
  });

  describe('createTemplate', () => {
    it('interpolates ${...} in a template string', () => {
      const ctx = makeContext({ pageState: { name: 'World' } });
      const result = evaluator.createTemplate('Hello ${$state.name}!', ctx);
      expect(result).toBe('Hello World!');
    });

    it('interpolates {{...}} in a template string', () => {
      const ctx = makeContext({ pageState: { x: 42 } });
      const result = evaluator.createTemplate('Value: {{$state.x}}', ctx);
      expect(result).toBe('Value: 42');
    });

    it('leaves unrecognised ICU plural (multi-rule) unprocessed', () => {
      // processICUFormat uses regex `[^}]+` which stops at the FIRST inner `}`,
      // so multi-rule ICU like `{count, plural, =0 {x} =1 {y} other {z}}` is
      // only partially captured and no rule match is found → original match returned.
      const ctx = makeContext({ pageState: { count: 0 } });
      const icuTemplate = '{count, plural, =0 {no items} =1 {one item} other {# items}}';
      const result = evaluator.createTemplate(icuTemplate, ctx);
      // The regex captures `{count, plural, =0 {no items}` and replaces it with
      // the unmatched original (ruleMatches is null), leaving the template unchanged.
      expect(result).toContain('count');
      expect(result).toContain('plural');
    });

    it('non-ICU text in template passes through unchanged', () => {
      const ctx = makeContext({ pageState: {} });
      const result = evaluator.createTemplate('plain text', ctx);
      expect(result).toBe('plain text');
    });
  });

  describe('getAvailableVariables', () => {
    it('includes standard context keys', () => {
      const vars = evaluator.getAvailableVariables(makeContext());
      expect(vars).toContain('$state');
      expect(vars).toContain('$user');
      expect(vars).toContain('$utils');
      expect(vars).toContain('Math');
      expect(vars).toContain('JSON');
    });
  });

  describe('getAvailableFunctions', () => {
    it('includes utility function names', () => {
      const fns = evaluator.getAvailableFunctions();
      expect(fns).toContain('isEmpty');
      expect(fns).toContain('isNotEmpty');
      expect(fns).toContain('formatDate');
      expect(fns).toContain('toNumber');
      expect(fns).toContain('if');
    });
  });

  describe('utility functions via expression', () => {
    it('$utils.isEmpty returns true for null', () => {
      expect(ExpressionEvaluator.evaluate('{{$utils.isEmpty(null)}}', makeContext())).toBe(true);
    });

    it('$utils.isEmpty returns false for non-empty string', () => {
      expect(ExpressionEvaluator.evaluate('{{$utils.isEmpty("hi")}}', makeContext())).toBe(false);
    });

    it('$utils.length returns array length', () => {
      const ctx = makeContext({ pageState: { arr: [1, 2, 3] } });
      expect(ExpressionEvaluator.evaluate('{{$utils.length($state.arr)}}', ctx)).toBe(3);
    });

    it('$utils.toUpperCase works', () => {
      expect(ExpressionEvaluator.evaluate('{{$utils.toUpperCase("abc")}}', makeContext())).toBe(
        'ABC',
      );
    });

    it('$utils.toNumber converts string to number', () => {
      expect(ExpressionEvaluator.evaluate('{{$utils.toNumber("42")}}', makeContext())).toBe(42);
    });
  });
});
