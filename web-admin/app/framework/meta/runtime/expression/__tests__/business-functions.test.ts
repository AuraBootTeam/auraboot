/**
 * Business Functions Library Tests
 *
 * Tests for the fn.* namespace available in DSL expressions.
 * Covers all 5 categories: Logical, Text, Number, Date, Type, Collection.
 */

import { describe, it, expect } from 'vitest';
import { businessFunctions } from '../business-functions';
import { ExpressionParser } from '../parser';
import { createExpressionContext } from '../context';

const fn = businessFunctions;

describe('Business Functions Library', () => {
  // ==================== Logical ====================

  describe('Logical Functions', () => {
    it('IF returns trueValue when condition is truthy', () => {
      expect(fn.IF(true, 'yes', 'no')).toBe('yes');
      expect(fn.IF(1, 'yes', 'no')).toBe('yes');
      expect(fn.IF('non-empty', 'yes', 'no')).toBe('yes');
    });

    it('IF returns falseValue when condition is falsy', () => {
      expect(fn.IF(false, 'yes', 'no')).toBe('no');
      expect(fn.IF(0, 'yes', 'no')).toBe('no');
      expect(fn.IF(null, 'yes', 'no')).toBe('no');
      expect(fn.IF(undefined, 'yes', 'no')).toBe('no');
      expect(fn.IF('', 'yes', 'no')).toBe('no');
    });

    it('IF returns null when falseValue is omitted and condition is false', () => {
      expect(fn.IF(false, 'yes')).toBe(null);
    });

    it('CASE matches value against pairs', () => {
      expect(fn.CASE('A', 'A', 'Alpha', 'B', 'Beta')).toBe('Alpha');
      expect(fn.CASE('B', 'A', 'Alpha', 'B', 'Beta')).toBe('Beta');
      expect(fn.CASE('C', 'A', 'Alpha', 'B', 'Beta', 'Unknown')).toBe('Unknown');
      expect(fn.CASE('C', 'A', 'Alpha', 'B', 'Beta')).toBe(null);
    });

    it('SWITCH is an alias for CASE', () => {
      expect(fn.SWITCH('X', 'X', 100, 'Y', 200)).toBe(100);
    });

    it('AND returns true only if all args are truthy', () => {
      expect(fn.AND(true, true, true)).toBe(true);
      expect(fn.AND(true, false, true)).toBe(false);
      expect(fn.AND(1, 'yes', {})).toBe(true);
    });

    it('OR returns true if any arg is truthy', () => {
      expect(fn.OR(false, false, true)).toBe(true);
      expect(fn.OR(false, 0, '')).toBe(false);
    });

    it('NOT negates', () => {
      expect(fn.NOT(true)).toBe(false);
      expect(fn.NOT(false)).toBe(true);
      expect(fn.NOT(0)).toBe(true);
    });

    it('IFS returns the first matching pair', () => {
      expect(fn.IFS(false, 'A', true, 'B', true, 'C')).toBe('B');
      expect(fn.IFS(false, 'A', false, 'B')).toBe(null);
    });
  });

  // ==================== Text ====================

  describe('Text Functions', () => {
    it('TEXT converts various types', () => {
      expect(fn.TEXT(42)).toBe('42');
      expect(fn.TEXT(null)).toBe('');
      expect(fn.TEXT(undefined)).toBe('');
      expect(fn.TEXT(true)).toBe('true');
    });

    it('CONCAT joins multiple values', () => {
      expect(fn.CONCAT('Hello', ' ', 'World')).toBe('Hello World');
      expect(fn.CONCAT('A', null, 'B')).toBe('AB');
    });

    it('LEFT extracts leftmost characters', () => {
      expect(fn.LEFT('Hello', 3)).toBe('Hel');
      expect(fn.LEFT('Hi', 5)).toBe('Hi');
    });

    it('RIGHT extracts rightmost characters', () => {
      expect(fn.RIGHT('Hello', 3)).toBe('llo');
      expect(fn.RIGHT('Hi', 5)).toBe('Hi');
    });

    it('MID extracts substring', () => {
      expect(fn.MID('Hello World', 6, 5)).toBe('World');
    });

    it('LEN returns length', () => {
      expect(fn.LEN('Hello')).toBe(5);
      expect(fn.LEN(null)).toBe(0);
      expect(fn.LEN([1, 2, 3])).toBe(3);
    });

    it('UPPER/LOWER convert case', () => {
      expect(fn.UPPER('hello')).toBe('HELLO');
      expect(fn.LOWER('hello')).toBe('hello');
    });

    it('TRIM removes whitespace', () => {
      expect(fn.TRIM('  hello  ')).toBe('hello');
    });

    it('REPLACE replaces all occurrences', () => {
      expect(fn.REPLACE('foo bar foo', 'foo', 'baz')).toBe('baz bar baz');
    });

    it('SUBSTITUTE replaces specific occurrence', () => {
      expect(fn.SUBSTITUTE('aaa', 'a', 'b', 2)).toBe('aba');
      expect(fn.SUBSTITUTE('aaa', 'a', 'b')).toBe('bbb');
    });

    it('CONTAINS checks substring', () => {
      expect(fn.CONTAINS('Hello World', 'World')).toBe(true);
      expect(fn.CONTAINS('Hello', 'world')).toBe(false);
    });

    it('SPLIT splits string', () => {
      expect(fn.SPLIT('a,b,c', ',')).toEqual(['a', 'b', 'c']);
    });

    it('PAD pads string', () => {
      expect(fn.PAD('42', 5, '0', 'left')).toBe('00042');
      expect(fn.PAD('hi', 5)).toBe('hi   ');
    });

    it('REPEAT repeats string', () => {
      expect(fn.REPEAT('ab', 3)).toBe('ababab');
    });

    it('STARTSWITH/ENDSWITH check boundaries', () => {
      expect(fn.STARTSWITH('Hello', 'He')).toBe(true);
      expect(fn.ENDSWITH('Hello', 'lo')).toBe(true);
    });
  });

  // ==================== Number ====================

  describe('Number Functions', () => {
    it('ROUND rounds to decimal places', () => {
      expect(fn.ROUND(3.456, 2)).toBe(3.46);
      expect(fn.ROUND(3.456, 0)).toBe(3);
      expect(fn.ROUND(3.456)).toBe(3);
    });

    it('FLOOR floors to decimal places', () => {
      expect(fn.FLOOR(3.789, 1)).toBe(3.7);
    });

    it('CEIL ceils to decimal places', () => {
      expect(fn.CEIL(3.211, 1)).toBe(3.3);
    });

    it('ABS returns absolute value', () => {
      expect(fn.ABS(-5)).toBe(5);
      expect(fn.ABS(5)).toBe(5);
    });

    it('MOD returns modulo', () => {
      expect(fn.MOD(10, 3)).toBe(1);
    });

    it('POWER returns exponent', () => {
      expect(fn.POWER(2, 10)).toBe(1024);
    });

    it('SUM sums values, including nested arrays', () => {
      expect(fn.SUM(1, 2, 3)).toBe(6);
      expect(fn.SUM([1, 2], [3, 4])).toBe(10);
      expect(fn.SUM(1, null, 3)).toBe(4);
    });

    it('AVG averages values', () => {
      expect(fn.AVG(2, 4, 6)).toBe(4);
      expect(fn.AVG()).toBe(0);
    });

    it('MIN/MAX find extremes', () => {
      expect(fn.MIN(5, 2, 8)).toBe(2);
      expect(fn.MAX(5, 2, 8)).toBe(8);
      expect(fn.MIN()).toBe(0);
    });

    it('CLAMP constrains value', () => {
      expect(fn.CLAMP(15, 0, 10)).toBe(10);
      expect(fn.CLAMP(-5, 0, 10)).toBe(0);
      expect(fn.CLAMP(5, 0, 10)).toBe(5);
    });

    it('PERCENT computes percentage', () => {
      expect(fn.PERCENT(25, 100)).toBe(25);
      expect(fn.PERCENT(1, 3, 1)).toBe(33.3);
      expect(fn.PERCENT(0, 0)).toBe(0);
    });
  });

  // ==================== Date ====================

  describe('Date Functions', () => {
    it('NOW returns a Date', () => {
      expect(fn.NOW()).toBeInstanceOf(Date);
    });

    it('TODAY returns YYYY-MM-DD string', () => {
      expect(fn.TODAY()).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    });

    it('DATEADD adds days', () => {
      const result = fn.DATEADD('2026-01-01', 10, 'day');
      expect(result).toContain('2026-01-11');
    });

    it('DATEADD adds months', () => {
      const result = fn.DATEADD('2026-01-15', 2, 'month');
      expect(result).toContain('2026-03-15');
    });

    it('DATEDIFF calculates day difference', () => {
      expect(fn.DATEDIFF('2026-01-01', '2026-01-31', 'day')).toBe(30);
    });

    it('DATEDIFF calculates month difference', () => {
      expect(fn.DATEDIFF('2026-01-01', '2026-04-01', 'month')).toBe(3);
    });

    it('DATEFORMAT formats date', () => {
      expect(fn.DATEFORMAT('2026-03-15', 'YYYY/MM/DD')).toBe('2026/03/15');
    });

    it('YEAR/MONTH/DAY extract components', () => {
      expect(fn.YEAR('2026-03-15')).toBe(2026);
      expect(fn.MONTH('2026-03-15')).toBe(3);
      expect(fn.DAY('2026-03-15')).toBe(15);
    });

    it('WEEKDAY returns day of week', () => {
      // 2026-03-15 is Sunday
      expect(fn.WEEKDAY('2026-03-15')).toBe(0);
    });

    it('STARTOFMONTH/ENDOFMONTH compute boundaries', () => {
      expect(fn.STARTOFMONTH('2026-03-15')).toBe('2026-03-01');
      expect(fn.ENDOFMONTH('2026-02-15')).toBe('2026-02-28');
    });

    it('ISTODAY checks current date', () => {
      expect(fn.ISTODAY(new Date())).toBe(true);
      expect(fn.ISTODAY('2020-01-01')).toBe(false);
    });

    it('ISPAST/ISFUTURE check relative time', () => {
      expect(fn.ISPAST('2020-01-01')).toBe(true);
      expect(fn.ISFUTURE('2099-01-01')).toBe(true);
    });

    it('handles invalid dates gracefully', () => {
      expect(fn.DATEADD('invalid', 1, 'day')).toBe('');
      expect(fn.DATEDIFF('invalid', '2026-01-01', 'day')).toBe(0);
      expect(fn.YEAR('invalid')).toBe(0);
    });
  });

  // ==================== Type Check ====================

  describe('Type Check Functions', () => {
    it('ISBLANK detects blank values', () => {
      expect(fn.ISBLANK(null)).toBe(true);
      expect(fn.ISBLANK(undefined)).toBe(true);
      expect(fn.ISBLANK('')).toBe(true);
      expect(fn.ISBLANK('  ')).toBe(true);
      expect(fn.ISBLANK([])).toBe(true);
      expect(fn.ISBLANK('hello')).toBe(false);
      expect(fn.ISBLANK(0)).toBe(false);
      expect(fn.ISBLANK(false)).toBe(false);
    });

    it('ISNUMBER detects numbers', () => {
      expect(fn.ISNUMBER(42)).toBe(true);
      expect(fn.ISNUMBER('42')).toBe(true);
      expect(fn.ISNUMBER('abc')).toBe(false);
      expect(fn.ISNUMBER(NaN)).toBe(false);
      expect(fn.ISNUMBER('')).toBe(false);
    });

    it('ISTEXT/ISBOOLEAN/ISARRAY check types', () => {
      expect(fn.ISTEXT('hello')).toBe(true);
      expect(fn.ISTEXT(42)).toBe(false);
      expect(fn.ISBOOLEAN(true)).toBe(true);
      expect(fn.ISBOOLEAN('true')).toBe(false);
      expect(fn.ISARRAY([1, 2])).toBe(true);
      expect(fn.ISARRAY('not array')).toBe(false);
    });

    it('TYPEOF returns type string', () => {
      expect(fn.TYPEOF(null)).toBe('null');
      expect(fn.TYPEOF([1])).toBe('array');
      expect(fn.TYPEOF(new Date())).toBe('date');
      expect(fn.TYPEOF(42)).toBe('number');
      expect(fn.TYPEOF('hi')).toBe('string');
    });

    it('COALESCE returns first non-null', () => {
      expect(fn.COALESCE(null, undefined, 'hello', 'world')).toBe('hello');
      expect(fn.COALESCE(null, null)).toBe(null);
      expect(fn.COALESCE(0, 'fallback')).toBe(0);
    });

    it('DEFAULT returns default for blank values', () => {
      expect(fn.DEFAULT(null, 'fallback')).toBe('fallback');
      expect(fn.DEFAULT('', 'fallback')).toBe('fallback');
      expect(fn.DEFAULT('hello', 'fallback')).toBe('hello');
      expect(fn.DEFAULT(0, 'fallback')).toBe(0);
    });

    it('TONUMBER converts to number', () => {
      expect(fn.TONUMBER('42')).toBe(42);
      expect(fn.TONUMBER('abc', -1)).toBe(-1);
    });

    it('TOBOOLEAN converts to boolean', () => {
      expect(fn.TOBOOLEAN('true')).toBe(true);
      expect(fn.TOBOOLEAN('1')).toBe(true);
      expect(fn.TOBOOLEAN('false')).toBe(false);
      expect(fn.TOBOOLEAN(0)).toBe(false);
    });
  });

  // ==================== Collection ====================

  describe('Collection Functions', () => {
    const items = [
      { name: 'Alice', age: 30, dept: 'Engineering' },
      { name: 'Bob', age: 25, dept: 'Engineering' },
      { name: 'Carol', age: 35, dept: 'Marketing' },
    ];

    it('COUNT counts elements', () => {
      expect(fn.COUNT(items)).toBe(3);
      expect(fn.COUNT([])).toBe(0);
      expect(fn.COUNT({ a: 1, b: 2 })).toBe(2);
    });

    it('PLUCK extracts field values', () => {
      expect(fn.PLUCK(items, 'name')).toEqual(['Alice', 'Bob', 'Carol']);
    });

    it('UNIQUE deduplicates', () => {
      expect(fn.UNIQUE([1, 2, 2, 3, 3])).toEqual([1, 2, 3]);
    });

    it('FIRST/LAST return edges', () => {
      expect(fn.FIRST(items)).toEqual(items[0]);
      expect(fn.LAST(items)).toEqual(items[2]);
      expect(fn.FIRST([])).toBe(null);
    });

    it('NTH returns by index (supports negative)', () => {
      expect(fn.NTH(items, 0)).toEqual(items[0]);
      expect(fn.NTH(items, -1)).toEqual(items[2]);
    });

    it('FLATTEN flattens nested arrays', () => {
      expect(fn.FLATTEN([1, [2, [3, [4]]]])).toEqual([1, 2, 3, 4]);
    });

    it('GROUPBY groups by field', () => {
      const result = fn.GROUPBY(items, 'dept');
      expect(Object.keys(result)).toEqual(['Engineering', 'Marketing']);
      expect(result['Engineering']).toHaveLength(2);
      expect(result['Marketing']).toHaveLength(1);
    });

    it('SORTBY sorts by field', () => {
      const sorted = fn.SORTBY(items, 'age', 'desc');
      expect(sorted[0].name).toBe('Carol');
      expect(sorted[2].name).toBe('Bob');
    });

    it('JOIN concatenates array elements', () => {
      expect(fn.JOIN(['a', 'b', 'c'], ' | ')).toBe('a | b | c');
      expect(fn.JOIN([1, null, 3])).toBe('1, , 3');
    });

    it('handles non-array inputs gracefully', () => {
      expect(fn.COUNT(null as any)).toBe(0);
      expect(fn.PLUCK(null as any, 'x')).toEqual([]);
      expect(fn.UNIQUE(null as any)).toEqual([]);
      expect(fn.FIRST(null as any)).toBe(null);
      expect(fn.FLATTEN(null as any)).toEqual([]);
      expect(fn.GROUPBY(null as any, 'x')).toEqual({});
      expect(fn.SORTBY(null as any, 'x')).toEqual([]);
      expect(fn.JOIN(null as any)).toBe('');
    });
  });

  // ==================== Integration with Expression Parser ====================

  describe('Integration with Expression Parser (fn.* namespace)', () => {
    const createTestContext = () =>
      createExpressionContext({
        form: {
          name: 'John',
          status: 'active',
          amount: 1250.567,
          startDate: '2026-01-15',
          tags: ['urgent', 'vip'],
          email: '',
          items: [
            { product: 'A', qty: 10, price: 100 },
            { product: 'B', qty: 5, price: 200 },
          ],
        },
        global: {
          user: {
            id: 'u1',
            name: 'Admin',
            email: 'a@b.com',
            roles: ['admin'],
            permissions: ['read'],
          },
          locale: 'zh-CN',
          theme: 'light',
        },
      });

    it('fn.IF in expression', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(parser.evaluate('${fn.IF(form.status === "active", "Active", "Inactive")}')).toBe(
        'Active',
      );
    });

    it('fn.ISBLANK in expression', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(parser.evaluate('${fn.ISBLANK(form.email)}')).toBe(true);
      expect(parser.evaluate('${fn.ISBLANK(form.name)}')).toBe(false);
    });

    it('fn.DATEADD in expression', () => {
      const parser = new ExpressionParser(createTestContext());
      const result = parser.evaluate('${fn.DATEADD(form.startDate, 30, "day")}');
      expect(result).toContain('2026-02-14');
    });

    it('fn.ROUND in expression', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(parser.evaluate('${fn.ROUND(form.amount, 2)}')).toBe(1250.57);
    });

    it('fn.SUM with array in expression', () => {
      const parser = new ExpressionParser(createTestContext());
      // SUM of an array of numbers (jsep doesn't support arrow functions)
      const result = parser.evaluate('${fn.SUM(10, 20, 30)}');
      expect(result).toBe(60);
    });

    it('fn.COUNT with array in expression', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(parser.evaluate('${fn.COUNT(form.items)}')).toBe(2);
      expect(parser.evaluate('${fn.COUNT(form.tags)}')).toBe(2);
    });

    it('fn.CASE in expression', () => {
      const parser = new ExpressionParser(createTestContext());
      const result = parser.evaluate(
        '${fn.CASE(form.status, "active", "Active", "inactive", "Inactive", "Unknown")}',
      );
      expect(result).toBe('Active');
    });

    it('fn.CONCAT in expression', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(parser.evaluate('${fn.CONCAT("Hello ", form.name, "!")}')).toBe('Hello John!');
    });

    it('fn.DEFAULT in expression', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(parser.evaluate('${fn.DEFAULT(form.email, "no-email@example.com")}')).toBe(
        'no-email@example.com',
      );
    });

    it('fn.CONTAINS in expression', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(parser.evaluate('${fn.CONTAINS(form.name, "oh")}')).toBe(true);
    });

    it('fn.PLUCK in expression', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(parser.evaluate('${fn.PLUCK(form.items, "product")}')).toEqual(['A', 'B']);
    });

    it('fn.DATEDIFF in expression', () => {
      const parser = new ExpressionParser(createTestContext());
      const result = parser.evaluate('${fn.DATEDIFF(form.startDate, "2026-03-15", "month")}');
      expect(result).toBe(2);
    });

    it('fn.COALESCE in expression', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(parser.evaluate('${fn.COALESCE(form.missing, form.alsoMissing, "fallback")}')).toBe(
        'fallback',
      );
    });

    it('nested fn calls in expression', () => {
      const parser = new ExpressionParser(createTestContext());
      // IF amount > 1000 → round to 2 decimals, else → 0
      const result = parser.evaluate('${fn.IF(form.amount > 1000, fn.ROUND(form.amount, 2), 0)}');
      expect(result).toBe(1250.57);
    });
  });
});
