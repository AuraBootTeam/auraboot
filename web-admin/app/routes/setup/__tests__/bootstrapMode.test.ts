import { describe, expect, it } from 'vitest';
import { COMMUNITY_BRANDING } from '~/config/branding';
import { requiresSchoolSelfServiceMode, resolveBootstrapSystemMode } from '../bootstrapMode';

const schoolBranding = {
  mode: 'commercial' as const,
  tenantOnboarding: { joinChannel: 'wechat_mini' as const },
};

describe('bootstrap system mode', () => {
  it('forces multi for a branded school self-service deployment', () => {
    expect(requiresSchoolSelfServiceMode(schoolBranding)).toBe(true);
    expect(resolveBootstrapSystemMode(schoolBranding, 'single')).toBe('multi');
  });

  it('preserves the generic platform choice', () => {
    expect(requiresSchoolSelfServiceMode(COMMUNITY_BRANDING)).toBe(false);
    expect(resolveBootstrapSystemMode(COMMUNITY_BRANDING, 'single')).toBe('single');
    expect(resolveBootstrapSystemMode(COMMUNITY_BRANDING, 'hybrid')).toBe('hybrid');
  });
});
