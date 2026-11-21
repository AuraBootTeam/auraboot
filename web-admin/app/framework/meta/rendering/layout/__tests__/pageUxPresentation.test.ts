import { describe, expect, it } from 'vitest';
import { resolveDetailBodyGroups, resolvePageUxProfile } from '../pageUxPresentation';
import type { BlockConfig } from '../../../schemas/types';

const blocks = ['notice', 'material', 'quotes', 'actions'].map(id => ({id, blockType:'rich-text'} as BlockConfig));
describe('Opt-in page presentation', () => {
  it('preserves pages without a supported profile', () => {
    expect(resolvePageUxProfile(undefined)).toBeUndefined();
    expect(resolvePageUxProfile({uxProfile:'unknown'})).toBeUndefined();
    expect(resolvePageUxProfile({uxProfile:'work-list'})).toBe('work-list');
  });
  it('groups existing block identities and preserves unassigned blocks', () => {
    const before = JSON.stringify(blocks);
    const units = resolveDetailBodyGroups([{id:'comparison',blockIds:['material','quotes'],layout:'rows'}],blocks);
    expect(units.map(unit=>unit.key)).toEqual(['block:notice','group:comparison','block:actions']);
    expect(units[1].blocks[0]).toBe(blocks[1]);
    expect(units[1].blocks[1]).toBe(blocks[2]);
    expect(JSON.stringify(blocks)).toBe(before);
  });
  it('rejects missing, repeated or conflicting block identities', () => {
    expect(()=>resolveDetailBodyGroups([{id:'a',blockIds:['missing']}],blocks)).toThrow(/Invalid/);
    expect(()=>resolveDetailBodyGroups([{id:'a',blockIds:['quotes','quotes']}],blocks)).toThrow(/repeated/);
    expect(()=>resolveDetailBodyGroups([{id:'a',blockIds:['quotes']},{id:'b',blockIds:['quotes']}],blocks)).toThrow(/repeated/);
    expect(()=>resolveDetailBodyGroups([{id:'a',blockIds:[]}],blocks)).toThrow(/Invalid/);
  });
});
