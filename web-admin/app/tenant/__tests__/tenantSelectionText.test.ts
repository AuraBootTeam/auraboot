import { describe, expect, it } from 'vitest';
import TEXT from '../tenantSelectionText.i18n.json';
import { tenantSelectionText } from '../tenantSelectionText';

describe('tenant selection bilingual defaults', () => {
  it('provides matching nonempty locale and interpolation contracts for every key', () => {
    const slots = (text: string) => [...text.matchAll(/\{([^}]+)\}/g)].map(match => match[1]).sort();
    for (const pair of Object.values(TEXT)) {
      expect(pair.en.trim()).not.toBe('');
      expect(pair['zh-CN'].trim()).not.toBe('');
      expect(slots(pair.en)).toEqual(slots(pair['zh-CN']));
    }
  });

  it('resolves English variants, Chinese defaults and literal entity interpolation', () => {
    expect(tenantSelectionText('tenant.select.retry', 'en-US')).toBe('Reload');
    expect(tenantSelectionText('tenant.select.retry', 'en-GB')).toBe('Reload');
    expect(tenantSelectionText('tenant.select.retry')).toBe(TEXT['tenant.select.retry']['zh-CN']);
    expect(tenantSelectionText('tenant.select.existing.entityTitle', 'en-US', { entityLabel: 'school' })).toBe('Choose a school');
    expect(tenantSelectionText('tenant.select.create.creatorAdmin', 'en-US', { entityLabel: 'team $&' })).toBe('After creation, you will become an administrator of the team $&.');
  });

  it('keeps policy unavailability and administrator denial distinct in both locales', () => {
    for (const locale of ['zh-CN', 'en-US']) {
      const unavailable = tenantSelectionText('tenant.select.policy.unavailable', locale);
      const managed = tenantSelectionText('tenant.select.policy.managed', locale);
      expect(unavailable).not.toBe(managed);
      expect(unavailable).not.toContain('tenant.select.');
      expect(managed).not.toContain('tenant.select.');
    }
  });
});
