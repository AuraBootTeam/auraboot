// web-admin/app/framework/meta/runtime/reference-create/__tests__/ReferenceCreateDialog.test.tsx
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { ReferenceCreateDialog } from '../ReferenceCreateDialog';

// Stub the DSL form so the test focuses on submit→create→onCreated wiring.
vi.mock('~/framework/meta/hooks/useDslForm', () => ({
  useDslForm: (opts: any) => ({
    schema: { modelCode: opts.pageKey },
    loading: false,
    error: null,
    values: { name: 'New Cust' },
    errors: {},
    submitting: false,
    submit: async () => {
      await opts.onSubmit({ values: { name: 'New Cust' }, pageKey: opts.pageKey });
    },
    rendererProps: {},
  }),
}));
vi.mock('~/framework/meta/rendering/DslFormRenderer', () => ({
  DslFormRenderer: ({ form }: any) => (
    <button data-testid="dsl-submit" onClick={() => form.submit()}>submit</button>
  ),
}));

describe('ReferenceCreateDialog', () => {
  it('runs the create command and resolves {value,label} from the result pid', async () => {
    const executeCommand = vi
      .fn()
      .mockResolvedValue({ data: { pid: '01JX', name: 'New Cust' } });
    const onCreated = vi.fn();
    const onClose = vi.fn();

    render(
      <ReferenceCreateDialog
        open
        targetModel="customer"
        createCommand="customer:create"
        displayField="name"
        executeCommand={executeCommand}
        onCreated={onCreated}
        onClose={onClose}
      />,
    );

    fireEvent.click(screen.getByTestId('dsl-submit'));

    await waitFor(() => expect(onCreated).toHaveBeenCalled());
    expect(executeCommand).toHaveBeenCalledWith(
      'customer:create',
      undefined,
      { name: 'New Cust' },
      'create',
    );
    expect(onCreated).toHaveBeenCalledWith({ value: '01JX', label: 'New Cust' });
    expect(onClose).toHaveBeenCalled();
  });

  it('keeps the dialog open and does not select when create fails', async () => {
    const executeCommand = vi.fn().mockRejectedValue(new Error('unique conflict'));
    const onCreated = vi.fn();
    const onClose = vi.fn();
    render(
      <ReferenceCreateDialog
        open
        targetModel="customer"
        createCommand="customer:create"
        displayField="name"
        executeCommand={executeCommand}
        onCreated={onCreated}
        onClose={onClose}
      />,
    );
    fireEvent.click(screen.getByTestId('dsl-submit'));
    await waitFor(() => expect(executeCommand).toHaveBeenCalled());
    expect(onCreated).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
  });
});
