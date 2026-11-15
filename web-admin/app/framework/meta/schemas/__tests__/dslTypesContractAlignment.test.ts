import { describe, expect, it } from 'vitest';
import { expectTypeOf } from 'vitest';
import type { LocalizedText as ContractLocalizedText } from '@auraboot/dsl-types';
import type { LocalizedText, ResolvedLocalizedText } from '../types';

/**
 * DSL single-source identity lock (2026-11-15, unification batch 2).
 *
 * The dual source is gone: app `LocalizedText` is a re-export of the
 * published contract (`@auraboot/dsl-types` >= 0.0.2, whose index signature
 * now truthfully yields `string | undefined`), and the runtime page union
 * member is renamed `UnifiedPageSchema` so the contract's `PageSchema`
 * (V2 storage document) is the only type with that name in the workspace.
 *
 * This suite pins both facts. If either assertion breaks, someone recreated
 * a second source of truth — reconcile before shipping.
 */
describe('DSL contract identity (single-source lock)', () => {
  it('app LocalizedText IS the published contract type (no second definition)', () => {
    expectTypeOf<LocalizedText>().toEqualTypeOf<ContractLocalizedText>();
  });

  it('contract index access truthfully yields string | undefined', () => {
    const t: LocalizedText = { 'zh-CN': '值' };
    expect(t['fr-FR']).toBeUndefined();
    expectTypeOf(t['fr-FR']).toEqualTypeOf<string | undefined>();
  });

  it('authoring form (bare string) remains legal per contract', () => {
    const authoring: LocalizedText = 'plain';
    expect(authoring).toBe('plain');
  });

  it('ResolvedLocalizedText is the record-form derived view (not a second source)', () => {
    const record: ResolvedLocalizedText = { 'zh-CN': '你好', 'en-US': 'hello' };
    expect(record['zh-CN']).toBe('你好');
    // @ts-expect-error -- a bare string is the contract's authoring form, not the resolved view
    const bad: ResolvedLocalizedText = 'nope';
    expect(bad).toBe('nope');
  });
});
