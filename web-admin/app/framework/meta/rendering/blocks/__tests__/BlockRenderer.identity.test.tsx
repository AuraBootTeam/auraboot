/**
 * BlockRenderer.identity.test.tsx
 *
 * Verifies that BlockRenderer stamps data-aura-element-id (and sibling
 * data-aura-block-id / data-aura-page-id) on the block's outer wrapper so that
 * click-telemetry's deriveUiElement() can derive a stable element identity via
 * el.closest('[data-aura-element-id]').
 *
 * TDD RED → GREEN flow:
 *   1. Test written first (RED — fails because attributes not yet stamped).
 *   2. BlockRenderer.tsx modified to stamp attributes (GREEN).
 *   3. Broader rendering suite confirmed green (no regressions).
 */

import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';

// ── Mocks ─────────────────────────────────────────────────────────────────────
// Must be declared BEFORE the SUT import so the module factory runs first.

// Prevent the profile context from throwing — safe null profile is fine here.
vi.mock('@auraboot/runtime-kernel', async () => {
  const actual = await vi.importActual<typeof import('@auraboot/runtime-kernel')>(
    '@auraboot/runtime-kernel',
  );
  return actual;
});

// Stub the block resolver so BlockRenderer can dispatch to a simple renderer
// without needing the full admin BlockRegistry bootstrap.
vi.mock(
  '/Users/ghj/work/auraboot/auraboot-behavior-sdk-dashboard/web-admin/packages/runtime-kernel/rendering/blockResolver',
  () => ({
    getBlockResolver: () => ({
      get: (_blockType: string) => ({
        component: ({ block }: { block: any }) => (
          <span data-testid="stub-inner">{block.id}</span>
        ),
      }),
    }),
    getCustomBlockComponent: () => null,
    setBlockResolver: vi.fn(),
    setCustomBlockComponent: vi.fn(),
  }),
);

// ── SUT import (after mocks) ──────────────────────────────────────────────────
import { BlockRenderer } from '@auraboot/runtime-kernel';

// ── Runtime stub ──────────────────────────────────────────────────────────────

function makeRuntime(overrides: Record<string, any> = {}) {
  const context: Record<string, any> = {
    locale: 'en-US',
    t: (k: string) => k,
    form: {},
    global: {},
    state: {},
  };
  return {
    getContext: () => context,
    getEvaluator: () => ({
      evaluateCondition: () => true,
      evaluateTemplate: (tpl: string) => tpl,
    }),
    getSchema: () => ({
      id: 'test_schema',
      modelCode: 'test_model',
      pageKey: 'test_page_key',
      ...overrides.schema,
    }),
    getDataSourceManager: () => ({
      getData: () => [],
      has: () => false,
      register: vi.fn(),
    }),
    getStateManager: () => ({ updateState: vi.fn(), getContext: () => context }),
    getScopeId: () => 'scope-1',
    ...overrides,
  };
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('BlockRenderer — telemetry identity attributes', () => {
  it('stamps data-aura-element-id equal to block.id on the block wrapper', () => {
    const runtime = makeRuntime();
    const block = { id: 'blk_kpi_001', blockType: 'chart' };

    const { container } = render(
      <BlockRenderer block={block as any} runtime={runtime as any} />,
    );

    expect(container.querySelector('[data-aura-element-id="blk_kpi_001"]')).not.toBeNull();
  });

  it('stamps data-aura-block-id equal to block.id on the block wrapper', () => {
    const runtime = makeRuntime();
    const block = { id: 'blk_table_002', blockType: 'table' };

    const { container } = render(
      <BlockRenderer block={block as any} runtime={runtime as any} />,
    );

    expect(container.querySelector('[data-aura-block-id="blk_table_002"]')).not.toBeNull();
  });

  it('stamps data-aura-page-id from runtime.getSchema().pageKey when available', () => {
    const runtime = makeRuntime({ schema: { pageKey: 'order_list' } });
    const block = { id: 'blk_toolbar_003', blockType: 'toolbar' };

    const { container } = render(
      <BlockRenderer block={block as any} runtime={runtime as any} />,
    );

    const wrapper = container.querySelector('[data-aura-element-id="blk_toolbar_003"]');
    expect(wrapper).not.toBeNull();
    expect(wrapper?.getAttribute('data-aura-page-id')).toBe('order_list');
  });

  it('omits data-aura-page-id when pageKey is not set on the schema', () => {
    const runtime = makeRuntime({ schema: { pageKey: undefined } });
    const block = { id: 'blk_form_004', blockType: 'form-section' };

    const { container } = render(
      <BlockRenderer block={block as any} runtime={runtime as any} />,
    );

    const wrapper = container.querySelector('[data-aura-element-id="blk_form_004"]');
    expect(wrapper).not.toBeNull();
    // attribute should be absent (not empty string) when pageKey is undefined
    expect(wrapper?.hasAttribute('data-aura-page-id')).toBe(false);
  });
});
