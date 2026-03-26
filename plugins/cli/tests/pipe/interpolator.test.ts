import { describe, it, expect } from 'vitest';
import { interpolate, interpolateString, resolveVariable } from '../../src/pipe/interpolator.js';

describe('interpolator', () => {
  describe('resolveVariable', () => {
    it('should resolve top-level key', () => {
      expect(resolveVariable('name', { name: 'Alice' })).toBe('Alice');
    });

    it('should resolve nested path', () => {
      expect(resolveVariable('user.name', { user: { name: 'Bob' } })).toBe('Bob');
    });

    it('should resolve array index', () => {
      expect(resolveVariable('items.0', { items: ['first', 'second'] })).toBe('first');
    });

    it('should resolve deep nested path', () => {
      const ctx = { a: { b: { c: 42 } } };
      expect(resolveVariable('a.b.c', ctx)).toBe(42);
    });

    it('should return undefined for missing key', () => {
      expect(resolveVariable('missing', { name: 'x' })).toBeUndefined();
    });

    it('should return undefined for partial path', () => {
      expect(resolveVariable('a.b.c', { a: { b: null } })).toBeUndefined();
    });
  });

  describe('interpolateString', () => {
    it('should replace simple variable', () => {
      expect(interpolateString('Hello {{name}}', { name: 'World' })).toBe('Hello World');
    });

    it('should replace multiple variables', () => {
      expect(interpolateString('{{a}} + {{b}}', { a: '1', b: '2' })).toBe('1 + 2');
    });

    it('should return raw value for full-match', () => {
      const arr = [1, 2, 3];
      expect(interpolateString('{{data}}', { data: arr })).toBe(arr);
    });

    it('should return raw object for full-match', () => {
      const obj = { key: 'val' };
      expect(interpolateString('{{result}}', { result: obj })).toBe(obj);
    });

    it('should stringify non-string values in partial match', () => {
      expect(interpolateString('Count: {{n}}', { n: 42 })).toBe('Count: 42');
    });

    it('should leave unresolved variables as-is', () => {
      expect(interpolateString('{{missing}}', {})).toBe('{{missing}}');
    });

    it('should handle nested variable paths', () => {
      expect(interpolateString('{{summary.title}}', { summary: { title: 'Report' } }))
        .toBe('Report');
    });

    it('should handle whitespace in variable names', () => {
      expect(interpolateString('{{ name }}', { name: 'trimmed' })).toBe('trimmed');
    });
  });

  describe('interpolate', () => {
    it('should interpolate strings in objects', () => {
      const result = interpolate(
        { title: '{{name}} Report', count: 42 },
        { name: 'Sales' },
      );
      expect(result).toEqual({ title: 'Sales Report', count: 42 });
    });

    it('should interpolate strings in arrays', () => {
      const result = interpolate(
        ['{{a}}', '{{b}}', 'literal'],
        { a: 'x', b: 'y' },
      );
      expect(result).toEqual(['x', 'y', 'literal']);
    });

    it('should handle nested objects', () => {
      const result = interpolate(
        { outer: { inner: '{{val}}' } },
        { val: 'deep' },
      );
      expect(result).toEqual({ outer: { inner: 'deep' } });
    });

    it('should pass through non-string/object/array values', () => {
      expect(interpolate(42, {})).toBe(42);
      expect(interpolate(null, {})).toBeNull();
      expect(interpolate(true, {})).toBe(true);
    });
  });
});
