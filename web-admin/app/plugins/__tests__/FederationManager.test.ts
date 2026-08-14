import { describe, expect, it } from 'vitest';
import * as React from 'react';
import {
  createHostFederationShareScope,
  initializeFederationContainer,
  isPluginEnabledForRuntime,
  isSlotEnabledForRuntime,
  type FederationContainer,
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

describe('Federation ESM runtime', () => {
  it('shares the exact React instance used by the host renderer', async () => {
    const shareScope = createHostFederationShareScope();
    const sharedReactFactory = await shareScope.react[React.version].get();

    expect(sharedReactFactory()).toBe(React);
  });

  it('initializes an ESM remote with the host React share scope', async () => {
    let receivedShareScope: Parameters<FederationContainer['init']>[0] | null =
      null;
    const container: FederationContainer = {
      init: async (shareScope) => {
        receivedShareScope = shareScope;
      },
      get: async () => () => ({ default: () => null }),
    };

    await expect(initializeFederationContainer(container)).resolves.toBe(
      container,
    );
    expect(receivedShareScope).not.toBeNull();
    const sharedReactFactory = await receivedShareScope!.react[
      React.version
    ].get();
    expect(sharedReactFactory()).toBe(React);
  });
});
