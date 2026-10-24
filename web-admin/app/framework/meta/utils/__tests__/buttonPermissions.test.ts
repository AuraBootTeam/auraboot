import { describe, expect, it } from 'vitest';
import { hasButtonPermissions, isButtonDisabled } from '../buttonPermissions';

describe('composed action permissions', () => {
  const granted = new Set(['edit', 'publish']);
  const has = (code: string) => granted.has(code);
  it('preserves undeclared and single permission gates', () => {
    expect(hasButtonPermissions({}, has)).toBe(true);
    expect(hasButtonPermissions({ permissionCode: 'edit' }, has)).toBe(true);
    expect(hasButtonPermissions({ permissionCode: 'submit' }, has)).toBe(false);
  });
  it('requires every independent action grant', () => {
    expect(hasButtonPermissions({ permissionCodes: ['edit', 'publish'] }, has)).toBe(true);
    expect(hasButtonPermissions({ permissionCode: 'edit', permissionCodes: ['submit', 'publish'] }, has)).toBe(false);
    expect(hasButtonPermissions({ permissionCode: 'submit', permissionCodes: ['publish'] }, has)).toBe(false);
  });
  it('fails closed on malformed runtime declarations', () => {
    expect(hasButtonPermissions({ permissionCodes: [''] }, has)).toBe(false);
    expect(hasButtonPermissions({ permissionCodes: 'publish' } as any, has)).toBe(false);
  });
});

describe('button disabled state', () => {
  it('keeps pending prerequisites disabled, respects enable precedence and busy/static state', () => {
    const evaluate = (expression: string) => expression === 'ready';
    expect(isButtonDisabled({ disableWhen: 'ready' }, evaluate)).toBe(true);
    expect(isButtonDisabled({ enableWhen: 'waiting' }, evaluate)).toBe(true);
    expect(isButtonDisabled({ enableWhen: 'ready', disableWhen: 'ready' }, evaluate)).toBe(false);
    expect(isButtonDisabled({}, evaluate, true)).toBe(true);
    expect(isButtonDisabled({ disabled: true }, evaluate)).toBe(true);
  });
});
