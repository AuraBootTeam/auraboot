// web-admin/app/framework/meta/runtime/reference-create/__tests__/ReferenceCreateDialog.test.tsx
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { beforeEach, describe, it, expect, vi } from 'vitest';
import { ReferenceCreateDialog } from '../ReferenceCreateDialog';

const formHook = vi.hoisted(() => ({
  useDslForm: vi.fn((opts: any) => ({
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
  })),
}));

// Stub the DSL form so the test focuses on submit→create→onCreated wiring.
vi.mock('~/framework/meta/hooks/useDslForm', () => ({
  useDslForm: formHook.useDslForm,
}));
vi.mock('~/framework/meta/rendering/DslFormRenderer', () => ({
  DslFormRenderer: ({ form }: any) => (
    <button data-testid="dsl-submit" onClick={() => form.submit()}>
      submit
    </button>
  ),
}));

describe('ReferenceCreateDialog', () => {
  beforeEach(() => {
    formHook.useDslForm.mockClear();
  });

  it('runs the create command and resolves {value,label} from the result pid', async () => {
    const executeCommand = vi.fn().mockResolvedValue({ data: { pid: '01JX', name: 'New Cust' } });
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

  it('resolves nested command data from the real executeCommand result shape', async () => {
    const executeCommand = vi.fn().mockResolvedValue({
      data: {
        commandCode: 'customer:create',
        data: {
          recordPid: '01NESTED',
          name: 'Nested Cust',
        },
      },
    });
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
    expect(onCreated).toHaveBeenCalledWith({ value: '01NESTED', label: 'Nested Cust' });
    expect(onClose).toHaveBeenCalled();
  });

  it('uses the configured create page key when provided', () => {
    render(
      <ReferenceCreateDialog
        open
        targetModel="e2et_customer"
        createPageKey="e2et_customer_form"
        createCommand="e2et:create_customer"
        displayField="e2et_cust_name"
        executeCommand={vi.fn()}
        onCreated={vi.fn()}
        onClose={vi.fn()}
      />,
    );

    expect(formHook.useDslForm).toHaveBeenCalledWith(
      expect.objectContaining({ pageKey: 'e2et_customer_form' }),
    );
  });

  it('passes parent-derived initial values into the DSL create form', () => {
    render(
      <ReferenceCreateDialog
        open
        targetModel="project"
        createCommand="project:create"
        initialValues={{ customer_id: 'customer-pid-1' }}
        executeCommand={vi.fn()}
        onCreated={vi.fn()}
        onClose={vi.fn()}
      />,
    );

    expect(formHook.useDslForm).toHaveBeenCalledWith(
      expect.objectContaining({ initialValues: { customer_id: 'customer-pid-1' } }),
    );
  });

  it('defaults the create page key to targetModel_new when no page key is configured', () => {
    render(
      <ReferenceCreateDialog
        open
        targetModel="customer"
        createCommand="customer:create"
        displayField="name"
        executeCommand={vi.fn()}
        onCreated={vi.fn()}
        onClose={vi.fn()}
      />,
    );

    expect(formHook.useDslForm).toHaveBeenCalledWith(
      expect.objectContaining({ pageKey: 'customer_new' }),
    );
  });
});
