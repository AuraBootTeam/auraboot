import { describe, it, expect, beforeEach } from 'vitest';
import { EdgeRegistry } from '../edges/EdgeRegistry';
import type { FlowEdgeDefinition } from '../edges/types';

const def = (type: string): FlowEdgeDefinition => ({ type });

describe('EdgeRegistry (G1)', () => {
  let reg: EdgeRegistry;
  beforeEach(() => {
    reg = new EdgeRegistry();
  });

  it('registers and gets by type', () => {
    reg.register(def('conditional'));
    expect(reg.get('conditional')?.type).toBe('conditional');
    expect(reg.has('conditional')).toBe(true);
    expect(reg.get('missing')).toBeUndefined();
  });

  it('registerAll + getAll', () => {
    reg.registerAll([def('a'), def('b')]);
    expect(reg.getAll().map((d) => d.type).sort()).toEqual(['a', 'b']);
  });

  it('register overwrites the same type', () => {
    reg.register(def('x'));
    reg.register(def('x'));
    expect(reg.getAll()).toHaveLength(1);
  });

  it('clear empties the registry', () => {
    reg.register(def('a'));
    reg.clear();
    expect(reg.getAll()).toHaveLength(0);
    expect(reg.has('a')).toBe(false);
  });
});
