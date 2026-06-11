import React from 'react';
import { render, waitFor } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';

/**
 * `bindState` on a filter/search field must mirror the field value to a page
 * state key so data sources reading `${state.<bindState>}` (the list query) see
 * it — `updateField` alone only writes form scope, leaving search forms inert.
 */
describe('RuntimeFieldRenderer bindState', () => {
  const buildRuntime = (spies: {
    updateField: ReturnType<typeof vi.fn>;
    updateState: ReturnType<typeof vi.fn>;
    notifyStateChanged: ReturnType<typeof vi.fn>;
  }) => {
    const context = { locale: 'zh-CN', t: (k: string) => k, state: {}, form: {} };
    const stateManager = {
      getFieldMeta: () => undefined,
      getFieldValue: () => undefined,
      updateField: spies.updateField,
      updateState: spies.updateState,
    };
    return {
      getContext: () => context,
      getStateManager: () => stateManager,
      getScopeId: () => 'scope-1',
      getDataSourceManager: () => ({ notifyStateChanged: spies.notifyStateChanged }),
      triggerFieldLinkage: vi.fn(),
    } as any;
  };

  it('writes state.<bindState> and refreshes data sources on change', async () => {
    let captured: any;
    vi.resetModules();
    vi.doMock('~/framework/meta/rendering/components/ComponentLoader', () => ({
      ComponentLoader: (p: any) => {
        captured = p;
        return <div data-testid="cl" />;
      },
    }));
    const updateField = vi.fn();
    const updateState = vi.fn();
    const notifyStateChanged = vi.fn();
    const runtime = buildRuntime({ updateField, updateState, notifyStateChanged });
    const { RuntimeFieldRenderer } = await import('../RuntimeFieldRenderer');

    render(
      <RuntimeFieldRenderer
        field={{ field: 'iot_d_status', component: 'SmartSelect', bindState: 'statusFilter' } as any}
        runtime={runtime}
      />,
    );
    await waitFor(() => expect(captured).toBeTruthy());

    captured.props.onChange(['ONLINE']);

    expect(updateField).toHaveBeenCalledWith('scope-1', 'iot_d_status', ['ONLINE']);
    expect(updateState).toHaveBeenCalledWith('scope-1', 'statusFilter', ['ONLINE']);
    expect(notifyStateChanged).toHaveBeenCalledWith('statusFilter');
  });

  it('does not touch page state when bindState is absent', async () => {
    let captured: any;
    vi.resetModules();
    vi.doMock('~/framework/meta/rendering/components/ComponentLoader', () => ({
      ComponentLoader: (p: any) => {
        captured = p;
        return <div data-testid="cl" />;
      },
    }));
    const updateField = vi.fn();
    const updateState = vi.fn();
    const notifyStateChanged = vi.fn();
    const runtime = buildRuntime({ updateField, updateState, notifyStateChanged });
    const { RuntimeFieldRenderer } = await import('../RuntimeFieldRenderer');

    render(
      <RuntimeFieldRenderer
        field={{ field: 'iot_d_device_code', component: 'SmartInput' } as any}
        runtime={runtime}
      />,
    );
    await waitFor(() => expect(captured).toBeTruthy());

    captured.props.onChange('dev-1');

    expect(updateField).toHaveBeenCalledWith('scope-1', 'iot_d_device_code', 'dev-1');
    expect(updateState).not.toHaveBeenCalled();
    expect(notifyStateChanged).not.toHaveBeenCalled();
  });
});
