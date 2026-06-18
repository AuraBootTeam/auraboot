import React from 'react';
import { render, waitFor } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';

/**
 * A read-only field is never user-required: it is system-managed / auto-generated
 * (e.g. an auto-numbered `sc_code` like `SC-20260618-013`) and the user cannot
 * type into it. The required marker (`*`) and the "此字段为必填项" error must be
 * suppressed so the display matches the submit gate, which already excludes
 * read-only fields from required validation
 * (`FormPageContent`: `!rawField.readOnly && (rawField.required ?? meta.required)`).
 *
 * Regression: showcase create form rendered 编号 (sc_code) read-only yet with a
 * required `*`, misleading the user into thinking an auto-generated field was a
 * mandatory input.
 */
describe('RuntimeFieldRenderer required vs read-only', () => {
  const buildRuntime = (fieldMeta: Record<string, unknown> | undefined) => {
    const context = { locale: 'zh-CN', t: (k: string) => k, state: {}, form: {} };
    const stateManager = {
      getFieldMeta: () => fieldMeta,
      getFieldValue: () => undefined,
      updateField: vi.fn(),
      updateState: vi.fn(),
    };
    return {
      getContext: () => context,
      getStateManager: () => stateManager,
      getScopeId: () => 'scope-1',
      getDataSourceManager: () => ({ notifyStateChanged: vi.fn() }),
      triggerFieldLinkage: vi.fn(),
    } as any;
  };

  const renderField = async (field: any, fieldMeta: Record<string, unknown> | undefined) => {
    let captured: any;
    vi.resetModules();
    vi.doMock('~/framework/meta/rendering/components/ComponentLoader', () => ({
      ComponentLoader: (p: any) => {
        captured = p;
        return <div data-testid="cl" />;
      },
    }));
    const { RuntimeFieldRenderer } = await import('../RuntimeFieldRenderer');
    render(<RuntimeFieldRenderer field={field} runtime={buildRuntime(fieldMeta)} />);
    await waitFor(() => expect(captured).toBeTruthy());
    return captured;
  };

  it('suppresses required on a read-only field even when fieldMeta.required is true', async () => {
    const captured = await renderField(
      { field: 'sc_code', component: 'SmartInput', readOnly: true } as any,
      { required: true },
    );
    expect(captured.props.readOnly).toBe(true);
    expect(captured.props.required).toBe(false);
  });

  it('keeps required on an editable field when fieldMeta.required is true', async () => {
    const captured = await renderField(
      { field: 'sc_name', component: 'SmartInput' } as any,
      { required: true },
    );
    expect(captured.props.readOnly).toBeFalsy();
    expect(captured.props.required).toBe(true);
  });
});
