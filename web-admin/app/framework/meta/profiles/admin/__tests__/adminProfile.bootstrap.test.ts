import { describe, expect, it, vi } from 'vitest';

describe('admin render profile bootstrap', () => {
  it('wires the custom block loader before the first dynamic page render', async () => {
    vi.resetModules();

    const runtimeKernel = await import('@auraboot/runtime-kernel');
    expect(runtimeKernel.getCustomBlockComponent()).toBeNull();

    await import('../index');

    expect(runtimeKernel.getCustomBlockComponent()).toBeDefined();
  });

  it('keeps a profile-level custom block renderer fallback for DSL custom pages', async () => {
    vi.resetModules();

    const runtimeKernel = await import('@auraboot/runtime-kernel');
    await import('../index');

    const adminProfile = runtimeKernel.profileRegistry.get('admin');
    expect(adminProfile?.blockRenderers.get('custom')).toBeDefined();
  });
});
