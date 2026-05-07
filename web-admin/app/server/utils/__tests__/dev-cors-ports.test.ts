import { describe, it, expect } from 'vitest';
import { DEFAULT_DEV_CORS_PORTS, parseDevAllowedPorts } from '../dev-cors-ports';

describe('parseDevAllowedPorts', () => {
  it('returns the canonical defaults when env is unset', () => {
    expect([...parseDevAllowedPorts(undefined)].sort()).toEqual(
      [...DEFAULT_DEV_CORS_PORTS].sort(),
    );
  });

  it('returns the canonical defaults when env is empty', () => {
    expect([...parseDevAllowedPorts('')].sort()).toEqual([...DEFAULT_DEV_CORS_PORTS].sort());
  });

  it('appends extra ports on top of defaults', () => {
    const result = parseDevAllowedPorts('5175,6445');
    for (const def of DEFAULT_DEV_CORS_PORTS) {
      expect(result.has(def)).toBe(true);
    }
    expect(result.has('5175')).toBe(true);
    expect(result.has('6445')).toBe(true);
  });

  it('trims whitespace and ignores non-numeric entries', () => {
    const result = parseDevAllowedPorts(' 5175 , abc, 6445 ,, ');
    expect(result.has('5175')).toBe(true);
    expect(result.has('6445')).toBe(true);
    expect(result.has('abc')).toBe(false);
    expect(result.has('')).toBe(false);
  });

  it('keeps defaults intact when env contains only invalid entries', () => {
    const result = parseDevAllowedPorts('abc,xyz, ,');
    expect([...result].sort()).toEqual([...DEFAULT_DEV_CORS_PORTS].sort());
  });

  it('deduplicates ports already present in defaults', () => {
    const result = parseDevAllowedPorts('5173,5175');
    const arr = [...result];
    expect(arr.filter((p) => p === '5173')).toHaveLength(1);
    expect(result.has('5175')).toBe(true);
  });
});
