import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { FiltersBlockRenderer } from '../FiltersBlockRenderer';
import { ScopedStateManager } from '../../../runtime/state/scoped-state';
import Input from '~/ui/smart/form/Input';

vi.mock('react-router', async () => ({
  ...(await vi.importActual<any>('react-router')),
  useActionData: () => null,
}));
vi.mock('~/contexts/AuthContext', () => ({ usePermission: () => true }));
vi.mock('~/framework/meta/hooks/useActionHandler', () => ({
  useActionHandler: () => ({ executeCommand: vi.fn() }),
}));
vi.mock('~/framework/meta/rendering/components/ComponentLoader', () => ({
  ComponentLoader: ({ props }: any) => <Input {...props} />,
}));

describe('Native filter reset with actual scoped state and input', () => {
  it('clears a typed input and reloads with cleared form while preserving other scopes', async () => {
    const manager = new ScopedStateManager({ locale: 'en', theme: 'light' } as any);
    manager.createScope('filters');
    manager.createScope('other', { form: { note: 'Keep this draft' } });
    const reload = vi.fn(async () => {
      expect(manager.getScope('filters')?.form).toEqual({});
    });
    const runtime = {
      getContext: () => ({ ...manager.getContext('filters'), t: (key: string) => key }),
      getStateManager: () => manager,
      getScopeId: () => 'filters',
      getDataSourceManager: () => ({ reload }),
      triggerFieldLinkage: vi.fn(),
    } as any;
    render(
      <FiltersBlockRenderer
        runtime={runtime}
        block={
          {
            id: 'filters',
            blockType: 'filters',
            fields: [{ field: 'keyword', type: 'text', label: 'Keyword' }],
            onReset: { action: 'form.reset', args: { ids: ['events'] } },
          } as any
        }
      />,
    );
    const input = screen.getByRole('textbox', { name: 'Keyword' });
    fireEvent.change(input, { target: { value: 'Selected vessel' } });
    expect(input).toHaveValue('Selected vessel');
    expect(manager.getFieldValue('filters', 'keyword')).toBe('Selected vessel');
    fireEvent.click(screen.getByTestId('filter-btn-reset'));
    await waitFor(() => expect(reload).toHaveBeenCalledWith(['events']));
    await waitFor(() => expect(input).toHaveValue(''));
    expect(manager.getScope('other')?.form).toEqual({ note: 'Keep this draft' });
  });
});
