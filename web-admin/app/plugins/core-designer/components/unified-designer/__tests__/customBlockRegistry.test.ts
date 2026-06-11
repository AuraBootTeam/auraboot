import { afterEach, describe, expect, it } from 'vitest';
import type { BlockDefinitionV3 } from '../types';
import {
  clearCustomDesignerBlocks,
  isCustomBlockAllowedForKind,
  registerCustomDesignerBlock,
} from '../registry/customBlockRegistry';
import { createDefaultBlockRegistryV3 } from '../registry/BlockRegistry';
import { isBlockTypeAllowedForKind } from '../registry/kindPolicy';

const assetCard: BlockDefinitionV3 = {
  blockType: 'asset-card',
  label: { 'en-US': 'Asset card', 'zh-CN': '资产卡片' },
  icon: 'id-card',
  category: 'qr',
  layoutCapability: 'span',
};

describe('custom designer block registry (pluggable extension point)', () => {
  afterEach(() => clearCustomDesignerBlocks());

  it('keeps all built-in blocks and adds the registered custom block to the factory', () => {
    const before = createDefaultBlockRegistryV3();
    expect(before.get('asset-card')).toBeUndefined();
    const builtinCount = before.getAll().length;
    expect(before.get('form')).toBeDefined(); // sanity: built-ins present

    registerCustomDesignerBlock(assetCard, { allowedParents: ['form', 'form-section'] });

    const after = createDefaultBlockRegistryV3();
    expect(after.get('asset-card')).toMatchObject({ blockType: 'asset-card', category: 'qr' });
    expect(after.getAll().length).toBe(builtinCount + 1);
    // built-ins still intact
    expect(after.get('field')).toBeDefined();
    expect(after.get('form')).toBeDefined();
  });

  it('wires the custom block into its allowedParents so canContain() permits nesting', () => {
    registerCustomDesignerBlock(assetCard, { allowedParents: ['form', 'form-section'] });
    const registry = createDefaultBlockRegistryV3();

    expect(registry.canContain('form', 'asset-card')).toBe(true);
    expect(registry.canContain('form-section', 'asset-card')).toBe(true);
    // a parent NOT listed is unaffected
    expect(registry.canContain('table', 'asset-card')).toBe(false);
    // existing allowed children are preserved (not clobbered)
    expect(registry.canContain('form', 'form-section')).toBe(true);
  });

  it('respects allowedKinds in the per-kind policy without loosening built-ins', () => {
    registerCustomDesignerBlock(assetCard, { allowedKinds: ['form', 'composite'] });

    // custom block offered for its declared kinds...
    expect(isBlockTypeAllowedForKind('form', 'asset-card')).toBe(true);
    // ...but not for kinds it didn't opt into
    expect(isBlockTypeAllowedForKind('list', 'asset-card')).toBe(false);
    // composite still allows everything
    expect(isBlockTypeAllowedForKind('composite', 'asset-card')).toBe(true);
    // built-in policy is unchanged
    expect(isBlockTypeAllowedForKind('form', 'field')).toBe(true);
    expect(isBlockTypeAllowedForKind('list', 'field')).toBe(false);
  });

  it('defaults to all concrete kinds when allowedKinds is omitted', () => {
    registerCustomDesignerBlock(assetCard);
    expect(isCustomBlockAllowedForKind('form', 'asset-card')).toBe(true);
    expect(isCustomBlockAllowedForKind('list', 'asset-card')).toBe(true);
    expect(isCustomBlockAllowedForKind('detail', 'asset-card')).toBe(true);
    expect(isCustomBlockAllowedForKind('form', 'unknown-block')).toBe(false);
  });

  it('clearCustomDesignerBlocks resets registrations', () => {
    registerCustomDesignerBlock(assetCard);
    expect(createDefaultBlockRegistryV3().get('asset-card')).toBeDefined();
    clearCustomDesignerBlocks();
    expect(createDefaultBlockRegistryV3().get('asset-card')).toBeUndefined();
  });
});
