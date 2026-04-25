/**
 * Expression Security Tests
 *
 * Tests for expression sandbox security:
 * - Forbidden global identifiers are blocked
 * - Dangerous properties are blocked
 * - Unsafe function calls are blocked
 * - Safe functions work correctly
 */

import { describe, it, expect } from 'vitest';
import {
  ExpressionParser,
  ExpressionSecurityError,
  SAFE_FUNCTIONS,
  FORBIDDEN_GLOBALS,
} from '../parser';
import { createExpressionContext } from '../context';

describe('Expression Security', () => {
  const createTestContext = () =>
    createExpressionContext({
      form: {
        name: 'Test',
        price: 100,
        tags: ['a', 'b', 'c'],
        createdAt: new Date('2026-01-01'),
      },
      global: {
        user: {
          id: 'user-1',
          name: 'Test User',
          email: 'test@example.com',
          roles: ['user'],
          permissions: ['read', 'write'],
        },
        tenant: { id: 'tenant-1', name: 'Test Tenant' },
        locale: 'zh-CN',
        theme: 'light',
      },
    });

  describe('FORBIDDEN_GLOBALS - Blocked Identifiers', () => {
    it('should block window access', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(() => parser.evaluate('${window}')).toThrow(/禁止访问危险标识符: window/);
    });

    it('should block document access', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(() => parser.evaluate('${document}')).toThrow(/禁止访问危险标识符: document/);
    });

    it('should block eval', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(() => parser.evaluate('${eval}')).toThrow(/禁止访问危险标识符: eval/);
    });

    it('should block Function constructor', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(() => parser.evaluate('${Function}')).toThrow(/禁止访问危险标识符: Function/);
    });

    it('should block fetch', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(() => parser.evaluate('${fetch}')).toThrow(/禁止访问危险标识符: fetch/);
    });

    it('should block localStorage', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(() => parser.evaluate('${localStorage}')).toThrow(/禁止访问危险标识符: localStorage/);
    });

    it('should block sessionStorage', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(() => parser.evaluate('${sessionStorage}')).toThrow(
        /禁止访问危险标识符: sessionStorage/,
      );
    });

    it('should block process (Node.js)', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(() => parser.evaluate('${process}')).toThrow(/禁止访问危险标识符: process/);
    });

    it('should block require (Node.js)', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(() => parser.evaluate('${require}')).toThrow(/禁止访问危险标识符: require/);
    });

    it('should block globalThis', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(() => parser.evaluate('${globalThis}')).toThrow(/禁止访问危险标识符: globalThis/);
    });

    it('should block setTimeout', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(() => parser.evaluate('${setTimeout}')).toThrow(/禁止访问危险标识符: setTimeout/);
    });

    it('should block XMLHttpRequest', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(() => parser.evaluate('${XMLHttpRequest}')).toThrow(
        /禁止访问危险标识符: XMLHttpRequest/,
      );
    });

    it('should block WebSocket', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(() => parser.evaluate('${WebSocket}')).toThrow(/禁止访问危险标识符: WebSocket/);
    });

    it('should have all expected forbidden globals defined', () => {
      const criticalForbiddenGlobals = [
        'window',
        'document',
        'eval',
        'Function',
        'fetch',
        'localStorage',
        'sessionStorage',
        'setTimeout',
        'setInterval',
        'process',
        'require',
      ];

      for (const global of criticalForbiddenGlobals) {
        expect(FORBIDDEN_GLOBALS.has(global)).toBe(true);
      }
    });
  });

  describe('DANGEROUS_PROPERTIES - Blocked Property Access', () => {
    it('should block constructor access', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(() => parser.evaluate('${form.constructor}')).toThrow(/禁止访问危险属性: constructor/);
    });

    it('should block __proto__ access', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(() => parser.evaluate('${form.__proto__}')).toThrow(/禁止访问危险属性: __proto__/);
    });

    it('should block prototype access', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(() => parser.evaluate('${form.prototype}')).toThrow(/禁止访问危险属性: prototype/);
    });

    it('should block caller access', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(() => parser.evaluate('${form.caller}')).toThrow(/禁止访问危险属性: caller/);
    });

    it('should block arguments access', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(() => parser.evaluate('${form.arguments}')).toThrow(/禁止访问危险属性: arguments/);
    });
  });

  describe('Unsafe Function Calls', () => {
    it('should block eval() call', () => {
      const parser = new ExpressionParser(createTestContext());
      // Note: This throws when trying to call the forbidden function
      expect(() => parser.evaluate('${eval("1+1")}')).toThrow(/禁止.*eval/);
    });

    it('should block Function() call', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(() => parser.evaluate('${Function("return 1")}')).toThrow(/禁止.*Function/);
    });
  });

  describe('Safe Context Access', () => {
    it('should allow form data access', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(parser.evaluate('${form.name}')).toBe('Test');
      expect(parser.evaluate('${form.price}')).toBe(100);
    });

    it('should allow global user access', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(parser.evaluate('${global.user.name}')).toBe('Test User');
      expect(parser.evaluate('${global.user.id}')).toBe('user-1');
    });

    it('should allow nested property access', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(parser.evaluate('${global.tenant.name}')).toBe('Test Tenant');
    });

    it('should allow array access', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(parser.evaluate('${form.tags[0]}')).toBe('a');
      expect(parser.evaluate('${form.tags.length}')).toBe(3);
    });

    it('should allow boolean literals', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(parser.evaluate('${true}')).toBe(true);
      expect(parser.evaluate('${false}')).toBe(false);
    });

    it('should allow null and undefined', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(parser.evaluate('${null}')).toBe(null);
      expect(parser.evaluate('${undefined}')).toBe(undefined);
    });
  });

  describe('Safe Built-in Functions', () => {
    it('should allow hasPermission()', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(parser.evaluate('${hasPermission("read")}')).toBe(true);
      expect(parser.evaluate('${hasPermission("admin")}')).toBe(false);
    });

    it('should allow formatDate()', () => {
      const parser = new ExpressionParser(createTestContext());
      const result = parser.evaluate('${formatDate(form.createdAt, "YYYY-MM-DD")}');
      expect(result).toBe('2026-01-01');
    });

    it('should allow formatCurrency()', () => {
      const parser = new ExpressionParser(createTestContext());
      const result = parser.evaluate('${formatCurrency(form.price)}');
      expect(result).toContain('100');
    });

    it('should have all expected safe functions defined', () => {
      const criticalSafeFunctions = [
        'hasPermission',
        'formatDate',
        'formatCurrency',
        't',
        'includes',
        'startsWith',
        'endsWith',
        'trim',
        'filter',
        'map',
        'find',
        'some',
        'every',
        'abs',
        'round',
        'floor',
        'ceil',
        'min',
        'max',
      ];

      for (const func of criticalSafeFunctions) {
        expect(SAFE_FUNCTIONS.has(func)).toBe(true);
      }
    });
  });

  describe('Safe Math Proxy', () => {
    it('should allow Math.abs()', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(parser.evaluate('${Math.abs(-5)}')).toBe(5);
    });

    it('should allow Math.round()', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(parser.evaluate('${Math.round(3.7)}')).toBe(4);
    });

    it('should allow Math.floor()', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(parser.evaluate('${Math.floor(3.7)}')).toBe(3);
    });

    it('should allow Math.ceil()', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(parser.evaluate('${Math.ceil(3.2)}')).toBe(4);
    });

    it('should allow Math.min()', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(parser.evaluate('${Math.min(1, 2, 3)}')).toBe(1);
    });

    it('should allow Math.max()', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(parser.evaluate('${Math.max(1, 2, 3)}')).toBe(3);
    });

    it('should allow Math.pow()', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(parser.evaluate('${Math.pow(2, 3)}')).toBe(8);
    });

    it('should allow Math.sqrt()', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(parser.evaluate('${Math.sqrt(16)}')).toBe(4);
    });
  });

  describe('Safe JSON Proxy', () => {
    it('should allow JSON.stringify()', () => {
      const parser = new ExpressionParser(createTestContext());
      const result = parser.evaluate('${JSON.stringify(form.tags)}');
      expect(result).toBe('["a","b","c"]');
    });

    it('should allow JSON.parse()', () => {
      const context = createExpressionContext({
        data: '{"key": "value"}',
      });
      const parser = new ExpressionParser(context);
      const result = parser.evaluate('${JSON.parse(data)}');
      expect(result).toEqual({ key: 'value' });
    });
  });

  describe('Safe String Operations', () => {
    it('should allow string methods', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(parser.evaluate('${form.name.toUpperCase()}')).toBe('TEST');
      expect(parser.evaluate('${form.name.toLowerCase()}')).toBe('test');
      expect(parser.evaluate('${form.name.includes("es")}')).toBe(true);
      expect(parser.evaluate('${form.name.startsWith("Te")}')).toBe(true);
    });
  });

  describe('Safe Array Operations', () => {
    it('should allow array methods', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(parser.evaluate('${form.tags.includes("a")}')).toBe(true);
      expect(parser.evaluate('${form.tags.indexOf("b")}')).toBe(1);
    });
  });

  describe('Comparison and Logical Operators', () => {
    it('should allow comparison operators', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(parser.evaluate('${form.price > 50}')).toBe(true);
      expect(parser.evaluate('${form.price < 50}')).toBe(false);
      expect(parser.evaluate('${form.price === 100}')).toBe(true);
      expect(parser.evaluate('${form.price !== 200}')).toBe(true);
    });

    it('should allow logical operators', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(parser.evaluate('${form.price > 50 && form.price < 150}')).toBe(true);
      expect(parser.evaluate('${form.price < 50 || form.price > 90}')).toBe(true);
      expect(parser.evaluate('${!false}')).toBe(true);
    });

    it('should allow ternary operator', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(parser.evaluate('${form.price > 50 ? "expensive" : "cheap"}')).toBe('expensive');
    });

    it('should allow nullish coalescing', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(parser.evaluate('${form.missing ?? "default"}')).toBe('default');
      expect(parser.evaluate('${form.name ?? "default"}')).toBe('Test');
    });
  });

  describe('Optional Chaining', () => {
    it('should support optional chaining on undefined', () => {
      const context = createExpressionContext({
        data: undefined,
      });
      const parser = new ExpressionParser(context);
      // Optional chaining should return undefined without throwing
      expect(parser.evaluate('${data?.nested?.value}')).toBeUndefined();
    });

    it('should work with valid values', () => {
      const context = createExpressionContext({
        data: { nested: { value: 'found' } },
      });
      const parser = new ExpressionParser(context);
      expect(parser.evaluate('${data?.nested?.value}')).toBe('found');
    });
  });

  describe('Security Error Details', () => {
    it('should include violation type in error', () => {
      const parser = new ExpressionParser(createTestContext());
      try {
        parser.evaluate('${window}');
        expect.fail('Should have thrown');
      } catch (error) {
        expect(error).toBeInstanceOf(ExpressionSecurityError);
        expect((error as ExpressionSecurityError).violationType).toBe('forbidden_global');
      }
    });

    it('should include expression in error', () => {
      const parser = new ExpressionParser(createTestContext());
      try {
        parser.evaluate('${eval}');
        expect.fail('Should have thrown');
      } catch (error) {
        expect(error).toBeInstanceOf(ExpressionSecurityError);
        expect((error as ExpressionSecurityError).expression).toBe('eval');
      }
    });
  });

  describe('Complex Attack Vectors', () => {
    it('should block accessing constructor on form object', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(() => parser.evaluate('${form.constructor}')).toThrow(/禁止访问危险属性: constructor/);
    });

    it('should block accessing constructor on array', () => {
      const parser = new ExpressionParser(createTestContext());
      expect(() => parser.evaluate('${form.tags.constructor}')).toThrow(
        /禁止访问危险属性: constructor/,
      );
    });

    it('should not allow breaking out via string manipulation', () => {
      const context = createExpressionContext({
        evil: 'eval',
      });
      const parser = new ExpressionParser(context);
      // Even if we have "eval" as a string, we can't call it
      const result = parser.evaluate('${evil}');
      expect(result).toBe('eval'); // Just returns the string, can't execute
    });
  });
});
