import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { createRegistry } from '../createRegistry';

describe('createRegistry', () => {
  let warnSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
  });

  afterEach(() => {
    warnSpy.mockRestore();
  });

  it('register + get round-trips', () => {
    const reg = createRegistry<number>('test');
    reg.register('a', 1);
    expect(reg.get('a')).toBe(1);
  });

  it('has() reflects registration state', () => {
    const reg = createRegistry<string>('test');
    expect(reg.has('x')).toBe(false);
    reg.register('x', 'hello');
    expect(reg.has('x')).toBe(true);
  });

  it('size() counts unique keys', () => {
    const reg = createRegistry<number>('test');
    expect(reg.size()).toBe(0);
    reg.register('a', 1);
    reg.register('b', 2);
    expect(reg.size()).toBe(2);
  });

  it('list() returns insertion-ordered entries', () => {
    const reg = createRegistry<number>('test');
    reg.register('first', 1);
    reg.register('second', 2);
    expect(reg.list()).toEqual([
      ['first', 1],
      ['second', 2],
    ]);
  });

  it('unknown lookup returns undefined', () => {
    const reg = createRegistry<number>('test');
    expect(reg.get('missing')).toBeUndefined();
  });

  it('double-register warns instead of throwing (HMR support)', () => {
    const reg = createRegistry<number>('hmr-test');
    reg.register('k', 1);
    expect(() => reg.register('k', 2)).not.toThrow();
    expect(warnSpy).toHaveBeenCalledWith(
      expect.stringContaining('hmr-test'),
    );
    expect(reg.get('k')).toBe(2);
  });
});
