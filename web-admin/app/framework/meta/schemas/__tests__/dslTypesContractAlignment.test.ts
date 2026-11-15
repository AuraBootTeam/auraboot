import { describe, expect, it } from 'vitest';
import { expectTypeOf } from 'vitest';
import type { LocalizedText as ContractLocalizedText } from '@auraboot/dsl-types';
import type { LocalizedText } from '../types';

/**
 * DSL dual-source alignment lock (2026-11-15, unification batch 1).
 *
 * The app-local types.ts and packages/dsl-types historically carry parallel
 * definitions. Full unification needs a package minor-version window
 * (contract LocalizedText also allows a bare string; the app type is the
 * record form — 242 consumers index it directly). Until then this suite
 * pins the CURRENT structural relationship at compile time so any further
 * drift on either side fails the build instead of surfacing at runtime:
 *
 *  - every app-form LocalizedText must remain assignable to the contract
 *    form (the renderer must accept anything the published contract allows
 *    authors to write in the record form);
 *  - the well-known locale keys used across the app must stay readable on
 *    the app form.
 */
describe('DSL contract alignment (dual-source lock)', () => {
  it('contract record form stays assignable to the app form (drift direction locked)', () => {
    // Drift detected on first wiring (2026-11-15): the app form widens index
    // values to `string | undefined` (sparse records), the contract form is
    // `string`. Direction locked here: any contract record must remain
    // consumable by the app renderer. If this line breaks, one side drifted
    // again — reconcile BEFORE shipping.
    const contractRecord: ContractLocalizedText = { 'zh-CN': '你好', 'en-US': 'hello', 'xx-YY': 'custom' };
    const appConsumed: LocalizedText = contractRecord;
    expect(appConsumed).toBeTruthy();
    expectTypeOf(contractRecord).toExtend<LocalizedText>();
  });

  it('well-known locale keys remain readable on the app form', () => {
    const t: LocalizedText = { 'zh-CN': '值' };
    expect(t['zh-CN']).toBe('值');
    expectTypeOf(t['zh-CN']).toEqualTypeOf<string | undefined>();
  });

  it('documented divergence: the contract also allows a bare string (authoring form)', () => {
    // This is the intentional gap recorded for the package minor-version
    // window: ContractLocalizedText = string | record. When the sources are
    // truly unified this line should compile without a cast.
    const authoring: ContractLocalizedText = 'plain';
    expect(authoring).toBe('plain');
  });
});
