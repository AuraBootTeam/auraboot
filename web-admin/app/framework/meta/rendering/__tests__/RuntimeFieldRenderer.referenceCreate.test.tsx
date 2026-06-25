// web-admin/app/framework/meta/rendering/__tests__/RuntimeFieldRenderer.referenceCreate.test.tsx
import { render, screen, fireEvent, act } from '@testing-library/react';
import { beforeEach, describe, it, expect, vi } from 'vitest';
import { RuntimeFieldRenderer } from '../RuntimeFieldRenderer';

const permits = new Set<string>();
vi.mock('~/contexts/AuthContext', () => ({
  usePermission: (code: string) => permits.has(code),
}));
const executeCommand = vi.fn().mockResolvedValue({ data: { pid: '01JX', name: 'Acme' } });
vi.mock('~/framework/meta/hooks/useActionHandler', () => ({
  useActionHandler: () => ({ executeCommand }),
}));
// Capture the props handed to the loaded component (SmartSelect).
const loaded: any = {};
const dialogProps: any = {};
vi.mock('~/framework/meta/rendering/components/ComponentLoader', () => ({
  ComponentLoader: (props: any) => {
    Object.assign(loaded, props.props ?? props);
    return <div data-testid="loaded-select" />;
  },
}));
// Light stub of the create dialog: invoke onCreated immediately when open.
vi.mock('~/framework/meta/runtime/reference-create/ReferenceCreateDialog', () => ({
  ReferenceCreateDialog: (props: any) => {
    Object.assign(dialogProps, props);
    return props.open ? (
      <button
        data-testid="fire-created"
        onClick={() => props.onCreated({ value: '01JX', label: 'Acme' })}
      >
        x
      </button>
    ) : null;
  },
}));

function makeRuntime(overrides: Record<string, any> = {}) {
  const updateField = vi.fn();
  const reload = overrides.reload ?? vi.fn();
  const getDataSourceIdsByModel =
    overrides.getDataSourceIdsByModel ?? vi.fn(() => ['ds_customer_options']);
  const getState =
    overrides.getState ??
    vi.fn(() => ({
      data: [{ value: 'OLD', label: 'Old Customer' }],
      loading: false,
      error: null,
      lastFetch: null,
    }));
  const setData = overrides.setData ?? vi.fn();
  const runtime: any = {
    getContext: () => ({ locale: 'zh-CN', t: (k: string) => k }),
    getStateManager: () => ({
      getFieldMeta: () => undefined,
      getFieldValue: () => overrides.fieldValue,
      updateField,
      updateState: vi.fn(),
    }),
    getScopeId: () => 'scope1',
    getDataSourceManager: () => ({ getDataSourceIdsByModel, reload, getState, setData }),
    triggerFieldLinkage: vi.fn(),
  };
  return { runtime, updateField, reload, getDataSourceIdsByModel, getState, setData };
}

const refField: any = {
  field: 'customer_id',
  dataType: 'reference',
  allowCreate: true,
  refTarget: { targetModel: 'customer', displayField: 'name' },
};

describe('RuntimeFieldRenderer reference inline-create', () => {
  beforeEach(() => {
    permits.clear();
    executeCommand.mockClear();
    for (const key of Object.keys(loaded)) delete loaded[key];
    for (const key of Object.keys(dialogProps)) delete dialogProps[key];
  });

  it('does NOT enable create when the user lacks the create permission', () => {
    const { runtime } = makeRuntime();
    render(<RuntimeFieldRenderer field={refField} runtime={runtime} />);
    expect(loaded.canCreateNew).toBeFalsy();
  });

  it('does NOT enable create when allowCreate is not declared', () => {
    permits.add('customer:create');
    const { runtime } = makeRuntime();
    render(<RuntimeFieldRenderer field={{ ...refField, allowCreate: false }} runtime={runtime} />);
    expect(loaded.canCreateNew).toBeFalsy();
  });

  it('enables create for reference fields backed by an explicit dataSource when refTarget is declared', () => {
    permits.add('customer:create');
    const { runtime } = makeRuntime();
    render(
      <RuntimeFieldRenderer
        field={{
          ...refField,
          dataSource: {
            type: 'api',
            endpoint: '/api/external/customer-options',
            method: 'get',
          },
        }}
        runtime={runtime}
      />,
    );
    expect(loaded.canCreateNew).toBe(true);
    expect(dialogProps.targetModel).toBe('customer');
  });

  it('enables create and writes the new pid on creation when permitted', () => {
    permits.add('customer:create');
    const { runtime, updateField } = makeRuntime();
    render(<RuntimeFieldRenderer field={refField} runtime={runtime} />);
    expect(loaded.canCreateNew).toBe(true);
    act(() => {
      loaded.onCreateNew();
    }); // open dialog (flushes state update)
    fireEvent.click(screen.getByTestId('fire-created'));
    expect(updateField).toHaveBeenCalledWith('scope1', 'customer_id', '01JX');
  });

  it('appends the new pid for multi-value reference fields instead of replacing existing values', () => {
    permits.add('customer:create');
    const { runtime, updateField } = makeRuntime({ fieldValue: ['OLD-1'] });
    render(<RuntimeFieldRenderer field={refField} runtime={runtime} />);
    expect(loaded.canCreateNew).toBe(true);
    act(() => {
      loaded.onCreateNew();
    });
    fireEvent.click(screen.getByTestId('fire-created'));
    expect(updateField).toHaveBeenCalledWith('scope1', 'customer_id', ['OLD-1', '01JX']);
  });

  it('does not duplicate an already-selected pid for multi-value reference fields', () => {
    permits.add('customer:create');
    const { runtime, updateField } = makeRuntime({ fieldValue: ['01JX'] });
    render(<RuntimeFieldRenderer field={refField} runtime={runtime} />);
    act(() => {
      loaded.onCreateNew();
    });
    fireEvent.click(screen.getByTestId('fire-created'));
    expect(updateField).toHaveBeenCalledWith('scope1', 'customer_id', ['01JX']);
  });

  it('passes the shared action command executor to the create dialog', () => {
    permits.add('customer:create');
    const { runtime } = makeRuntime();
    render(<RuntimeFieldRenderer field={refField} runtime={runtime} />);
    expect(dialogProps.executeCommand).toBe(executeCommand);
  });

  it('reloads target-model option data sources after creation', () => {
    permits.add('customer:create');
    const { runtime, reload, getDataSourceIdsByModel } = makeRuntime();
    render(<RuntimeFieldRenderer field={refField} runtime={runtime} />);
    act(() => {
      loaded.onCreateNew();
    });
    fireEvent.click(screen.getByTestId('fire-created'));
    expect(getDataSourceIdsByModel).toHaveBeenCalledWith('customer');
    expect(reload).toHaveBeenCalledWith(['ds_customer_options']);
  });

  it('pins the created option into target-model data sources before reload completes', () => {
    permits.add('customer:create');
    const { runtime, setData } = makeRuntime();
    render(<RuntimeFieldRenderer field={refField} runtime={runtime} />);
    act(() => {
      loaded.onCreateNew();
    });
    fireEvent.click(screen.getByTestId('fire-created'));

    expect(setData).toHaveBeenCalledWith('ds_customer_options', [
      { value: '01JX', label: 'Acme' },
      { value: 'OLD', label: 'Old Customer' },
    ]);
  });

  it('keeps the created option pinned when reload returns a page without it', async () => {
    permits.add('customer:create');
    const getState = vi
      .fn()
      .mockReturnValueOnce({
        data: [{ value: 'OLD', label: 'Old Customer' }],
        loading: false,
        error: null,
        lastFetch: null,
      })
      .mockReturnValue({
        data: [{ value: 'OTHER', label: 'Other Customer' }],
        loading: false,
        error: null,
        lastFetch: null,
      });
    const reload = vi.fn().mockResolvedValue(undefined);
    const setData = vi.fn();
    const { runtime } = makeRuntime({ reload, getState, setData });

    render(<RuntimeFieldRenderer field={refField} runtime={runtime} />);
    act(() => {
      loaded.onCreateNew();
    });
    fireEvent.click(screen.getByTestId('fire-created'));

    await act(async () => {
      await Promise.resolve();
    });

    expect(setData).toHaveBeenLastCalledWith('ds_customer_options', [
      { value: '01JX', label: 'Acme' },
      { value: 'OTHER', label: 'Other Customer' },
    ]);
  });

  it('treats FieldConfig.type=reference as a reference field', () => {
    permits.add('customer:create');
    const { runtime } = makeRuntime();
    render(
      <RuntimeFieldRenderer
        field={{ ...refField, dataType: undefined, type: 'reference' }}
        runtime={runtime}
      />,
    );
    expect(loaded.canCreateNew).toBe(true);
  });

  it('gates create affordance by createPermission while executing createCommand', () => {
    permits.add('customer.manage');
    const { runtime } = makeRuntime();
    render(
      <RuntimeFieldRenderer
        field={{
          ...refField,
          createCommand: 'customer:create',
          createPermission: 'customer.manage',
        }}
        runtime={runtime}
      />,
    );
    expect(loaded.canCreateNew).toBe(true);
    expect(dialogProps.createCommand).toBe('customer:create');
  });

  it('passes the configured create page key to the create dialog', () => {
    permits.add('customer:create');
    const { runtime } = makeRuntime();
    render(
      <RuntimeFieldRenderer
        field={{ ...refField, createPageKey: 'customer_quick_create' }}
        runtime={runtime}
      />,
    );

    expect(dialogProps.createPageKey).toBe('customer_quick_create');
  });
});
