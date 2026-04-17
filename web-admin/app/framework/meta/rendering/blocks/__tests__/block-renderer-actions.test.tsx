/**
 * block-renderer-actions.test.tsx
 *
 * Verifies that FormButtonsBlockRenderer, ToolbarBlockRenderer and TableBlockRenderer
 * route button clicks through the unified `useActionHandler.handleAction` dispatcher
 * (refactor from direct `runtime.executeHandler` calls).
 *
 * Coverage per renderer:
 * 1. New-format button (`button.action: { type: 'command', command }`) reaches handleAction.
 * 2. Legacy button (`button.events.onClick.handler` for FormButtons, bare `button.handler`
 *    for Toolbar/Table) also reaches handleAction.
 */

import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, fireEvent } from '@testing-library/react';

// ---------------------------------------------------------------------------
// Mocks — must be declared BEFORE importing the SUT so the hook mock is in place
// when the renderer module is evaluated.
// ---------------------------------------------------------------------------

const handleActionSpy = vi.fn();

vi.mock('~/framework/meta/hooks/useActionHandler', () => ({
  useActionHandler: () => ({
    handleAction: handleActionSpy,
    loading: false,
    error: null,
    setError: vi.fn(),
  }),
}));

vi.mock('~/contexts/AuthContext', () => ({
  useAuth: () => ({ token: 'test-token' }),
}));

// Avoid loading heavy tree-data hook internals.
vi.mock('~/framework/meta/hooks/useTreeData', () => ({
  useTreeData: (rows: any[]) => ({ visibleRows: rows, toggleExpand: vi.fn() }),
}));

// ---------------------------------------------------------------------------
// SUT imports (after mocks).
// ---------------------------------------------------------------------------

import { FormButtonsBlockRenderer } from '../FormButtonsBlockRenderer';
import { ToolbarBlockRenderer } from '../ToolbarBlockRenderer';
import { TableBlockRenderer } from '../TableBlockRenderer';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';

// ---------------------------------------------------------------------------
// Runtime stub — only the methods the block renderers call.
// ---------------------------------------------------------------------------

function makeRuntime(overrides: Partial<any> = {}): SchemaRuntime {
  const context: Record<string, any> = {
    locale: 'en-US',
    t: (k: string) => k,
    form: {},
    global: {},
    state: {},
  };
  const stub = {
    getContext: () => context,
    getEvaluator: () => ({
      evaluateCondition: () => true,
      evaluateTemplate: (tpl: string) => tpl,
    }),
    getSchema: () => ({ id: 'test_schema', modelCode: 'test_model' }),
    getDataSourceManager: () => ({
      getData: () => [],
      has: () => false,
      register: vi.fn(),
    }),
    ...overrides,
  };
  return stub as unknown as SchemaRuntime;
}

beforeEach(() => {
  handleActionSpy.mockReset();
});

// ---------------------------------------------------------------------------
// FormButtonsBlockRenderer
// ---------------------------------------------------------------------------

describe('FormButtonsBlockRenderer', () => {
  it('dispatches new-format action through useActionHandler', () => {
    const runtime = makeRuntime();
    const block = {
      type: 'form-buttons',
      buttons: [
        {
          code: 'save',
          label: 'Save',
          action: { type: 'command', command: 'saveOrder' },
        },
      ],
    };

    const { getByTestId } = render(
      <FormButtonsBlockRenderer block={block as any} runtime={runtime} />,
    );
    fireEvent.click(getByTestId('form-btn-save'));

    expect(handleActionSpy).toHaveBeenCalledTimes(1);
    const [passedButton] = handleActionSpy.mock.calls[0];
    expect(passedButton.code).toBe('save');
    expect(passedButton.action).toEqual({ type: 'command', command: 'saveOrder' });
  });

  it('dispatches legacy events.onClick.handler through useActionHandler', () => {
    const runtime = makeRuntime();
    const block = {
      type: 'form-buttons',
      buttons: [
        {
          code: 'submit',
          label: 'Submit',
          events: { onClick: { handler: 'submitForm' } },
        },
      ],
    };

    const { getByTestId } = render(
      <FormButtonsBlockRenderer block={block as any} runtime={runtime} />,
    );
    fireEvent.click(getByTestId('form-btn-submit'));

    expect(handleActionSpy).toHaveBeenCalledTimes(1);
    const [passedButton] = handleActionSpy.mock.calls[0];
    expect(passedButton.code).toBe('submit');
    expect(passedButton.events?.onClick?.handler).toBe('submitForm');
  });
});

// ---------------------------------------------------------------------------
// ToolbarBlockRenderer
// ---------------------------------------------------------------------------

describe('ToolbarBlockRenderer', () => {
  it('dispatches new-format action with record=undefined (toolbar has no row)', () => {
    const runtime = makeRuntime();
    const block = {
      type: 'toolbar',
      buttons: [
        {
          code: 'create',
          label: 'Create',
          action: { type: 'navigate', to: 'test_model_form' },
        },
      ],
    };

    const { getByTestId } = render(<ToolbarBlockRenderer block={block as any} runtime={runtime} />);
    fireEvent.click(getByTestId('toolbar-btn-create'));

    expect(handleActionSpy).toHaveBeenCalledTimes(1);
    const [passedButton, passedRecord] = handleActionSpy.mock.calls[0];
    expect(passedButton.code).toBe('create');
    expect(passedButton.action).toEqual({ type: 'navigate', to: 'test_model_form' });
    // Toolbar buttons have no row context.
    expect(passedRecord).toBeUndefined();
  });

  it('dispatches legacy bare button.handler through useActionHandler (normalized to events.onClick.handler)', () => {
    const runtime = makeRuntime();
    const block = {
      type: 'toolbar',
      buttons: [
        {
          code: 'refresh',
          label: 'Refresh',
          handler: 'reloadList',
        },
      ],
    };

    const { getByTestId } = render(<ToolbarBlockRenderer block={block as any} runtime={runtime} />);
    fireEvent.click(getByTestId('toolbar-btn-refresh'));

    expect(handleActionSpy).toHaveBeenCalledTimes(1);
    const [passedButton] = handleActionSpy.mock.calls[0];
    expect(passedButton.code).toBe('refresh');
    // The renderer synthesises events.onClick.handler from legacy bare button.handler
    // so that useActionHandler's normalizeAction recognises it.
    expect(passedButton.events?.onClick?.handler).toBe('reloadList');
  });
});

// ---------------------------------------------------------------------------
// TableBlockRenderer
// ---------------------------------------------------------------------------

describe('TableBlockRenderer', () => {
  const baseColumns = [{ field: 'name', label: 'Name' }];
  const baseRow = { id: 'row-1', pid: 'row-1', name: 'Alpha' };

  function makeRuntimeWithData() {
    return makeRuntime({
      getDataSourceManager: () => ({
        getData: () => [baseRow],
        has: () => true,
        register: vi.fn(),
      }),
    });
  }

  it('dispatches new-format row action with the row as record', () => {
    const runtime = makeRuntimeWithData();
    const block = {
      type: 'table',
      dataSource: 'list',
      columns: baseColumns,
      rowActions: [
        {
          code: 'edit',
          label: 'Edit',
          action: { type: 'navigate', to: 'test_model_form' },
        },
      ],
    };

    const { getByTestId } = render(<TableBlockRenderer block={block as any} runtime={runtime} />);
    fireEvent.click(getByTestId('row-action-edit'));

    expect(handleActionSpy).toHaveBeenCalledTimes(1);
    const [passedButton, passedRecord] = handleActionSpy.mock.calls[0];
    expect(passedButton.code).toBe('edit');
    expect(passedButton.action).toEqual({ type: 'navigate', to: 'test_model_form' });
    expect(passedRecord).toEqual(baseRow);
  });

  it('dispatches legacy bare button.handler through useActionHandler (normalized to events.onClick.handler)', () => {
    const runtime = makeRuntimeWithData();
    const block = {
      type: 'table',
      dataSource: 'list',
      columns: baseColumns,
      rowActions: [
        {
          code: 'delete',
          label: 'Delete',
          handler: 'deleteRow',
        },
      ],
    };

    const { getByTestId } = render(<TableBlockRenderer block={block as any} runtime={runtime} />);
    fireEvent.click(getByTestId('row-action-delete'));

    expect(handleActionSpy).toHaveBeenCalledTimes(1);
    const [passedButton, passedRecord] = handleActionSpy.mock.calls[0];
    expect(passedButton.code).toBe('delete');
    expect(passedButton.events?.onClick?.handler).toBe('deleteRow');
    expect(passedRecord).toEqual(baseRow);
  });
});
