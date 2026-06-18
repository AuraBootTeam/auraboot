import { render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

// D2 — rich property controls. The inspector resolves dict / named-query /
// command / permission fields from their live registry endpoints. Mock the
// http-client `get` so each source returns a known option; assert the field
// renders a <select> with the fetched option AND the manual-entry fallback, and
// that a failed fetch degrades gracefully to manual entry (never blocks authoring).

const { mockGet } = vi.hoisted(() => ({ mockGet: vi.fn() }));
vi.mock('~/shared/services/http-client', () => ({ get: mockGet }));

import { SchemaInspector } from '../inspector/SchemaInspector';
import type { DslBlockV3 } from '../types';

function dictResponse() {
  return { data: { records: [{ code: 'gender', name: 'Gender' }] } };
}
function permissionResponse() {
  // /api/permissions/tree returns a module → resource → action tree; the selector
  // flattens children so leaf permission codes are selectable.
  return {
    data: [
      { code: 'order', name: 'Order', children: [{ code: 'order.read', name: 'Read order' }] },
    ],
  };
}
function namedQueryResponse() {
  return { data: { records: [{ code: 'q_active_orders', name: 'Active orders' }] } };
}
function commandResponse() {
  return { data: [{ code: 'order:approve', name: 'Approve order' }] };
}

beforeEach(() => {
  mockGet.mockReset();
  mockGet.mockImplementation((url: string) => {
    if (url === '/api/meta/dict') return Promise.resolve(dictResponse());
    if (url === '/api/permissions/tree') return Promise.resolve(permissionResponse());
    if (url === '/api/meta/named-queries') return Promise.resolve(namedQueryResponse());
    if (url === '/api/meta/commands') return Promise.resolve(commandResponse());
    // useModelOptions + anything else → empty list.
    return Promise.resolve({ data: { records: [] } });
  });
});

describe('D2 remote selectors — inspector controls', () => {
  it('dict + permission fields render selects with fetched options + manual fallback (field block)', async () => {
    const block = { id: 'f1', blockType: 'field', props: { component: 'input' } } as unknown as DslBlockV3;
    render(<SchemaInspector block={block} onChange={() => {}} />);

    // dict-select on props.dictCode
    const dictSelect = screen.getByTestId('inspector-field-props.dictCode');
    expect(dictSelect.tagName).toBe('SELECT');
    await waitFor(() =>
      expect(dictSelect).toHaveTextContent(/Gender \(gender\)/),
    );
    expect(screen.getByTestId('inspector-field-props.dictCode-manual')).toBeInTheDocument();

    // permission-select on props.permissionCode
    const permSelect = screen.getByTestId('inspector-field-props.permissionCode');
    expect(permSelect.tagName).toBe('SELECT');
    await waitFor(() =>
      expect(permSelect).toHaveTextContent(/Read order \(order.read\)/),
    );
    expect(screen.getByTestId('inspector-field-props.permissionCode-manual')).toBeInTheDocument();

    // it fetched dict + permissions (not the field's own model only).
    expect(mockGet).toHaveBeenCalledWith('/api/meta/dict', expect.anything());
    expect(mockGet).toHaveBeenCalledWith('/api/permissions/tree', expect.anything());
  });

  it('named-query field resolves from /api/meta/named-queries (widget block)', async () => {
    const block = { id: 'w1', blockType: 'widget', props: {} } as unknown as DslBlockV3;
    render(<SchemaInspector block={block} onChange={() => {}} />);

    const querySelect = screen.getByTestId('inspector-field-dataSource.queryCode');
    expect(querySelect.tagName).toBe('SELECT');
    await waitFor(() =>
      expect(querySelect).toHaveTextContent(/Active orders \(q_active_orders\)/),
    );
    expect(screen.getByTestId('inspector-field-dataSource.queryCode-manual')).toBeInTheDocument();
  });

  it('command field resolves from /api/meta/commands (action block)', async () => {
    const block = {
      id: 'a1',
      blockType: 'action',
      actionType: 'command',
      props: {},
    } as unknown as DslBlockV3;
    render(<SchemaInspector block={block} onChange={() => {}} />);

    const commandSelect = screen.getByTestId('inspector-field-props.command');
    expect(commandSelect.tagName).toBe('SELECT');
    await waitFor(() =>
      expect(commandSelect).toHaveTextContent(/Approve order \(order:approve\)/),
    );
    expect(screen.getByTestId('inspector-field-props.command-manual')).toBeInTheDocument();
  });

  it('degrades to manual entry when the source fetch fails (never blocks authoring)', async () => {
    mockGet.mockImplementation((url: string) => {
      if (url === '/api/meta/dict') return Promise.reject(new Error('403 no DICT_READ'));
      return Promise.resolve({ data: { records: [] } });
    });
    const block = {
      id: 'f2',
      blockType: 'field',
      props: { component: 'input', dictCode: 'pre_existing_code' },
    } as unknown as DslBlockV3;
    render(<SchemaInspector block={block} onChange={() => {}} />);

    // The select still renders, and the manual fallback preserves the bound code.
    const dictSelect = screen.getByTestId('inspector-field-props.dictCode');
    expect(dictSelect.tagName).toBe('SELECT');
    const manual = screen.getByTestId('inspector-field-props.dictCode-manual') as HTMLInputElement;
    expect(manual.value).toBe('pre_existing_code');
    // the current value survives as a leading option even with an empty list.
    await waitFor(() =>
      expect(dictSelect).toHaveTextContent('pre_existing_code'),
    );
  });
});
