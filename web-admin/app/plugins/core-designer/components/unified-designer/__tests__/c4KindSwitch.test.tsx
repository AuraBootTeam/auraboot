import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import {
  canSwitchToKind,
  getIncompatibleBlocksForKind,
  getKindPolicy,
  KIND_SWITCH_TARGETS,
} from '../registry/kindPolicy';
import { UnifiedDesignerWorkbench } from '../workbench/UnifiedDesignerWorkbench';
import type { DslBlockV3, PageSchemaV3 } from '../types';

/**
 * C4 — page-kind switching. Owner design decision (2026-06-18): the switch is
 * BLOCKED when any descendant block is incompatible with the target kind (no
 * silent data loss); the toolbar disables such target kinds with a reason. On a
 * valid switch, document.kind changes and the single root container's blockType
 * swaps to the target kind's root, as one undoable step.
 *
 * `divider` is allowed on every concrete kind; `chart` only on detail+dashboard;
 * `field` only on form+detail — so a detail page with [divider, chart] can switch
 * to dashboard but NOT to form/list (chart is incompatible there).
 */

function detailPage(children: DslBlockV3[]): PageSchemaV3 {
  return {
    schemaVersion: 3,
    kind: 'detail',
    id: 'c4_detail',
    blocks: [{ id: 'root', blockType: 'detail', blocks: children } as DslBlockV3],
  };
}

const DIVIDER: DslBlockV3 = { id: 'b_div', blockType: 'divider' } as DslBlockV3;
const CHART: DslBlockV3 = { id: 'b_chart', blockType: 'chart' } as DslBlockV3;
const DETAIL_SECTION: DslBlockV3 = { id: 'b_ds', blockType: 'detail-section' } as DslBlockV3;

describe('C4 kindPolicy — getIncompatibleBlocksForKind', () => {
  it('returns descendants invalid under the target kind (root excluded)', () => {
    const page = detailPage([DIVIDER, CHART]);
    // chart is not allowed on form/list; divider is allowed everywhere.
    expect(getIncompatibleBlocksForKind(page.blocks, 'form').map((b) => b.blockType)).toEqual(['chart']);
    expect(getIncompatibleBlocksForKind(page.blocks, 'list').map((b) => b.blockType)).toEqual(['chart']);
    // both chart + divider are valid on dashboard.
    expect(getIncompatibleBlocksForKind(page.blocks, 'dashboard')).toEqual([]);
  });

  it('walks nested descendants but never the root container itself', () => {
    const page = detailPage([
      { id: 'cols', blockType: 'columns', blocks: [CHART] } as DslBlockV3,
    ]);
    // chart nested under columns is still found for form (incompatible there).
    expect(getIncompatibleBlocksForKind(page.blocks, 'form').map((b) => b.id)).toContain('b_chart');
    // the root 'detail' block is never reported (it is swapped on switch).
    expect(getIncompatibleBlocksForKind(page.blocks, 'form').map((b) => b.blockType)).not.toContain('detail');
  });

  it('returns [] for composite (allows everything)', () => {
    expect(getIncompatibleBlocksForKind(detailPage([CHART, DETAIL_SECTION]).blocks, 'composite')).toEqual([]);
  });
});

describe('C4 kindPolicy — canSwitchToKind', () => {
  it('allows a switch only when all descendants are compatible', () => {
    const page = detailPage([DIVIDER, CHART]);
    expect(canSwitchToKind(page.blocks, 'dashboard')).toBe(true);
    expect(canSwitchToKind(page.blocks, 'form')).toBe(false); // chart incompatible
    expect(canSwitchToKind(page.blocks, 'list')).toBe(false); // chart incompatible
  });

  it('blocks the switch when an incompatible block is present', () => {
    // detail-section is detail-only — blocks switching to dashboard/list/form.
    const page = detailPage([DETAIL_SECTION]);
    for (const k of ['form', 'list', 'dashboard'] as const) {
      expect(canSwitchToKind(page.blocks, k), `detail-section blocks ${k}`).toBe(false);
    }
  });

  it('requires the standard single-root structure', () => {
    expect(canSwitchToKind([], 'form')).toBe(false);
    expect(
      canSwitchToKind(
        [{ id: 'a', blockType: 'detail' } as DslBlockV3, { id: 'b', blockType: 'detail' } as DslBlockV3],
        'form',
      ),
    ).toBe(false);
  });

  it('KIND_SWITCH_TARGETS excludes the internal composite kind', () => {
    expect(KIND_SWITCH_TARGETS).toEqual(['form', 'list', 'detail', 'dashboard']);
    expect(KIND_SWITCH_TARGETS).not.toContain('composite');
    // each target has a concrete root container to swap to.
    for (const k of KIND_SWITCH_TARGETS) {
      expect(getKindPolicy(k).rootBlockType).toBeTruthy();
    }
  });
});

describe('C4 workbench — kind-switch control', () => {
  it('renders the kind selector with incompatible targets disabled', () => {
    render(<UnifiedDesignerWorkbench initialDocument={detailPage([DIVIDER, CHART])} />);
    const select = screen.getByTestId('designer-kind-switch') as HTMLSelectElement;
    expect(select).toBeInTheDocument();
    expect(select.value).toBe('detail');
    // chart is detail+dashboard only → form/list disabled, dashboard enabled.
    expect(screen.getByTestId('designer-kind-option-dashboard')).not.toBeDisabled();
    expect(screen.getByTestId('designer-kind-option-form')).toBeDisabled();
    expect(screen.getByTestId('designer-kind-option-list')).toBeDisabled();
    // the disabled option carries the incompatible count in its label.
    expect(screen.getByTestId('designer-kind-option-form')).toHaveTextContent('(1)');
  });

  it('switches kind to a compatible target (and reflects it in the selector)', () => {
    render(<UnifiedDesignerWorkbench initialDocument={detailPage([DIVIDER, CHART])} />);
    const select = screen.getByTestId('designer-kind-switch') as HTMLSelectElement;
    fireEvent.change(select, { target: { value: 'dashboard' } });
    // document.kind switched → the controlled select now reads dashboard.
    expect((screen.getByTestId('designer-kind-switch') as HTMLSelectElement).value).toBe('dashboard');
    // becomes dirty (an undoable mutation happened).
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('未保存');
  });

  it('disables every other target when an incompatible block blocks all switches', () => {
    render(<UnifiedDesignerWorkbench initialDocument={detailPage([DETAIL_SECTION])} />);
    expect(screen.getByTestId('designer-kind-option-form')).toBeDisabled();
    expect(screen.getByTestId('designer-kind-option-list')).toBeDisabled();
    expect(screen.getByTestId('designer-kind-option-dashboard')).toBeDisabled();
    // detail (the current kind) stays selectable as the active value.
    expect((screen.getByTestId('designer-kind-switch') as HTMLSelectElement).value).toBe('detail');
  });
});
