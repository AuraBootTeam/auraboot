import { describe, expect, it } from 'vitest';
import {
  isPluginEnabledForRuntime,
  isSlotEnabledForRuntime,
} from '../FederationManager';
import type { PluginManifest, SlotContribution } from '../types';

describe('Federation runtime filtering', () => {
  it('keeps legacy plugins admin-only by default', () => {
    const manifest: PluginManifest = {
      pluginId: 'legacy',
      namespace: 'legacy',
      version: '1.0.0',
      displayName: 'Legacy',
      clientConfig: {
        remoteEntry: '/remoteEntry.js',
      },
    };

    expect(isPluginEnabledForRuntime(manifest, 'admin')).toBe(true);
    expect(isPluginEnabledForRuntime(manifest, 'storefront')).toBe(false);
  });

  it('honors explicit plugin runtime profiles', () => {
    const manifest: PluginManifest = {
      pluginId: 'commerce-theme',
      namespace: 'commerce-theme',
      version: '1.0.0',
      displayName: 'Commerce Theme',
      clientConfig: {
        remoteEntry: '/remoteEntry.js',
        runtimeProfiles: ['storefront', 'theme-preview'],
      },
    };

    expect(isPluginEnabledForRuntime(manifest, 'storefront')).toBe(true);
    expect(isPluginEnabledForRuntime(manifest, 'theme-preview')).toBe(true);
    expect(isPluginEnabledForRuntime(manifest, 'checkout')).toBe(false);
  });

  it('treats slots without runtime profiles as enabled for their loaded plugin runtime', () => {
    const slot: SlotContribution = {
      slotId: 'page:header:actions',
      componentName: 'HeaderActions',
    };

    expect(isSlotEnabledForRuntime(slot, 'merchant')).toBe(true);
  });

  it('honors explicit slot runtime profiles', () => {
    const slot: SlotContribution = {
      slotId: 'storefront:product:media',
      componentName: 'ProductMedia',
      runtimeProfiles: ['storefront'],
    };

    expect(isSlotEnabledForRuntime(slot, 'storefront')).toBe(true);
    expect(isSlotEnabledForRuntime(slot, 'admin')).toBe(false);
  });
});
