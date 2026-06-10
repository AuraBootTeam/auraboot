/**
 * Unit tests for useDslForm hook and mergePermissions utility.
 *
 * Strategy:
 * - vi.mock useSchemaLoader to isolate the façade from HTTP
 * - Exercise field state machine (set/get/error/clear/reset/dirty)
 * - Verify permission merge logic (merge mode vs override mode)
 * - Verify pageKey computation (explicit vs tableName+recordId)
 * - Verify submit delegates to onSubmit with correct payload
 * - Verify enabled=false suppresses loading/error/schema
 * - Verify rendererProps shape is stable (no deep mutation required)
 */

import { act, renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

// ── Mock useSchemaLoader BEFORE importing useDslForm ─────────────────────────

const mockReload = vi.fn();

vi.mock('~/framework/meta/hooks/useSchemaLoader', () => ({
  useSchemaLoader: vi.fn(() => ({
    schema: null,
    loading: false,
    error: null,
    reload: mockReload,
  })),
}));

import { useDslForm, mergePermissions } from '../useDslForm';
import { useSchemaLoader } from '~/framework/meta/hooks/useSchemaLoader';

const mockUseSchemaLoader = vi.mocked(useSchemaLoader);

// Convenience: return a fake schema from useSchemaLoader
function setupSchemaLoaderWithSchema(schema: any) {
  mockUseSchemaLoader.mockReturnValue({
    schema,
    loading: false,
    error: null,
    reload: mockReload,
  });
}

function setupSchemaLoaderLoading() {
  mockUseSchemaLoader.mockReturnValue({
    schema: null,
    loading: true,
    error: null,
    reload: mockReload,
  });
}

function setupSchemaLoaderError(error: Error) {
  mockUseSchemaLoader.mockReturnValue({
    schema: null,
    loading: false,
    error,
    reload: mockReload,
  });
}

// ── mergePermissions pure utility ─────────────────────────────────────────────

describe('mergePermissions', () => {
  it('merge mode: caller can tighten editable→readonly', () => {
    const result = mergePermissions(
      { name: 'editable' },
      { name: 'readonly' },
      'merge',
    );
    expect(result.name).toBe('readonly');
  });

  it('merge mode: caller cannot loosen readonly→editable', () => {
    const result = mergePermissions(
      { name: 'readonly' },
      { name: 'editable' },
      'merge',
    );
    expect(result.name).toBe('readonly');
  });

  it('merge mode: caller can tighten editable→hidden', () => {
    const result = mergePermissions(
      { name: 'editable' },
      { name: 'hidden' },
      'merge',
    );
    expect(result.name).toBe('hidden');
  });

  it('merge mode: caller cannot loosen hidden→readonly', () => {
    const result = mergePermissions(
      { name: 'hidden' },
      { name: 'readonly' },
      'merge',
    );
    expect(result.name).toBe('hidden');
  });

  it('merge mode: fields absent in schema default to editable and can be overridden', () => {
    const result = mergePermissions({}, { status: 'readonly' }, 'merge');
    expect(result.status).toBe('readonly');
  });

  it('override mode: caller wins regardless of schema', () => {
    const result = mergePermissions(
      { name: 'hidden' },
      { name: 'editable' },
      'override',
    );
    expect(result.name).toBe('editable');
  });

  it('default mode is merge when mode arg omitted', () => {
    const result = mergePermissions({ x: 'readonly' }, { x: 'editable' });
    expect(result.x).toBe('readonly');
  });
});

// ── useDslForm hook ───────────────────────────────────────────────────────────

describe('useDslForm', () => {
  // ── pageKey computation ────────────────────────────────────────────────────

  describe('pageKey computation', () => {
    it('uses explicit pageKey when provided', () => {
      const { result } = renderHook(() =>
        useDslForm({ pageKey: 'order_new' }),
      );
      expect(result.current.pageKey).toBe('order_new');
    });

    it('derives pageKey from tableName+recordId (edit → detail)', () => {
      const { result } = renderHook(() =>
        useDslForm({ tableName: 'order', recordId: 'rec-1' }),
      );
      expect(result.current.pageKey).toBe('order_detail');
    });

    it('derives pageKey from tableName without recordId (create → new)', () => {
      const { result } = renderHook(() =>
        useDslForm({ tableName: 'order' }),
      );
      expect(result.current.pageKey).toBe('order_new');
    });

    it('returns empty string when neither pageKey nor tableName is given', () => {
      const { result } = renderHook(() => useDslForm({}));
      expect(result.current.pageKey).toBe('');
    });
  });

  // ── Schema loading delegation ──────────────────────────────────────────────

  describe('schema loading passthrough', () => {
    it('passes loading=true from schema loader', () => {
      setupSchemaLoaderLoading();
      const { result } = renderHook(() => useDslForm({ pageKey: 'order_new' }));
      expect(result.current.loading).toBe(true);
      expect(result.current.schema).toBeNull();
    });

    it('passes schema from loader when available', () => {
      const fakeSchema = { modelCode: 'order', fields: [] };
      setupSchemaLoaderWithSchema(fakeSchema);
      const { result } = renderHook(() => useDslForm({ pageKey: 'order_new' }));
      expect(result.current.schema).toEqual(fakeSchema);
    });

    it('passes error from loader', () => {
      const err = new Error('not found');
      setupSchemaLoaderError(err);
      const { result } = renderHook(() => useDslForm({ pageKey: 'order_new' }));
      expect(result.current.error).toBe(err);
    });

    it('delegates reload() to useSchemaLoader reload', async () => {
      setupSchemaLoaderWithSchema({ modelCode: 'order', fields: [] });
      const { result } = renderHook(() => useDslForm({ pageKey: 'order_new' }));

      await act(async () => {
        await result.current.reload();
      });

      expect(mockReload).toHaveBeenCalled();
    });
  });

  // ── enabled=false ──────────────────────────────────────────────────────────

  describe('enabled=false', () => {
    it('disables loading, schema, and error when enabled is false', () => {
      // Even if loader returns loading=true, enabled=false overrides
      setupSchemaLoaderLoading();
      const { result } = renderHook(() =>
        useDslForm({ pageKey: 'order_new', enabled: false }),
      );
      expect(result.current.loading).toBe(false);
      expect(result.current.schema).toBeNull();
      expect(result.current.error).toBeNull();
      expect(result.current.enabled).toBe(false);
    });

    it('passes __disabled__ sentinel to schemaLoader when enabled=false', () => {
      setupSchemaLoaderWithSchema(null);
      renderHook(() => useDslForm({ pageKey: 'order_new', enabled: false }));

      const callOpts = mockUseSchemaLoader.mock.calls.at(-1)![0];
      expect(callOpts.pageKey).toBe('__disabled__');
    });
  });

  // ── Initial values and form state ──────────────────────────────────────────

  describe('form state', () => {
    it('initialises values from initialValues', () => {
      setupSchemaLoaderWithSchema(null);
      const { result } = renderHook(() =>
        useDslForm({ initialValues: { status: 'draft', amount: 100 } }),
      );
      expect(result.current.values).toEqual({ status: 'draft', amount: 100 });
    });

    it('starts with dirty=false', () => {
      setupSchemaLoaderWithSchema(null);
      const { result } = renderHook(() => useDslForm({}));
      expect(result.current.dirty).toBe(false);
    });

    it('setFieldValue updates values and sets dirty=true', () => {
      setupSchemaLoaderWithSchema(null);
      const { result } = renderHook(() =>
        useDslForm({ initialValues: { status: 'draft' } }),
      );

      act(() => result.current.setFieldValue('status', 'active'));

      expect(result.current.values.status).toBe('active');
      expect(result.current.dirty).toBe(true);
    });

    it('getFieldValue returns current value', () => {
      setupSchemaLoaderWithSchema(null);
      const { result } = renderHook(() =>
        useDslForm({ initialValues: { count: 42 } }),
      );
      expect(result.current.getFieldValue('count')).toBe(42);
    });

    it('setFieldValue clears existing error on the same field', () => {
      setupSchemaLoaderWithSchema(null);
      const { result } = renderHook(() => useDslForm({}));

      act(() => result.current.setFieldError('name', 'required'));
      expect(result.current.errors.name).toBe('required');

      act(() => result.current.setFieldValue('name', 'Alice'));
      expect(result.current.errors.name).toBeUndefined();
    });
  });

  // ── Error management ───────────────────────────────────────────────────────

  describe('field errors', () => {
    it('setFieldError sets an error for a field', () => {
      setupSchemaLoaderWithSchema(null);
      const { result } = renderHook(() => useDslForm({}));

      act(() => result.current.setFieldError('email', 'invalid email'));
      expect(result.current.errors.email).toBe('invalid email');
    });

    it('clearFieldError removes the error', () => {
      setupSchemaLoaderWithSchema(null);
      const { result } = renderHook(() => useDslForm({}));

      act(() => result.current.setFieldError('email', 'invalid email'));
      act(() => result.current.clearFieldError('email'));
      expect(result.current.errors.email).toBeUndefined();
    });

    it('clearFieldError on non-existent field is a no-op', () => {
      setupSchemaLoaderWithSchema(null);
      const { result } = renderHook(() => useDslForm({}));
      // should not throw
      act(() => result.current.clearFieldError('nonexistent'));
      expect(result.current.errors).toEqual({});
    });
  });

  // ── Reset ──────────────────────────────────────────────────────────────────

  describe('reset', () => {
    it('restores initial values, clears errors and dirty', () => {
      setupSchemaLoaderWithSchema(null);
      const { result } = renderHook(() =>
        useDslForm({ initialValues: { status: 'draft' } }),
      );

      act(() => {
        result.current.setFieldValue('status', 'active');
        result.current.setFieldError('status', 'some error');
      });

      act(() => result.current.reset());

      expect(result.current.values.status).toBe('draft');
      expect(result.current.errors).toEqual({});
      expect(result.current.dirty).toBe(false);
      expect(result.current.submitting).toBe(false);
    });
  });

  // ── Submit ─────────────────────────────────────────────────────────────────

  describe('submit', () => {
    it('calls onSubmit with current values, recordId, schema, pageKey', async () => {
      const fakeSchema = { modelCode: 'order', fields: [] };
      setupSchemaLoaderWithSchema(fakeSchema);
      const onSubmit = vi.fn().mockResolvedValue(undefined);

      const { result } = renderHook(() =>
        useDslForm({
          pageKey: 'order_new',
          initialValues: { status: 'draft' },
          recordId: 'rec-1',
          onSubmit,
        }),
      );

      await act(async () => {
        await result.current.submit();
      });

      expect(onSubmit).toHaveBeenCalledWith({
        values: { status: 'draft' },
        recordId: 'rec-1',
        schema: fakeSchema,
        pageKey: 'order_new',
      });
    });

    it('sets submitting=true during submit and false after', async () => {
      setupSchemaLoaderWithSchema(null);
      let resolveFn!: () => void;
      const onSubmit = vi.fn(
        () => new Promise<void>((resolve) => { resolveFn = resolve; }),
      );

      const { result } = renderHook(() =>
        useDslForm({ pageKey: 'order_new', onSubmit }),
      );

      // Start submit without awaiting
      let submitDone = false;
      act(() => {
        result.current.submit().then(() => { submitDone = true; });
      });

      // submitting should become true
      expect(result.current.submitting).toBe(true);

      // Resolve the promise
      await act(async () => {
        resolveFn();
        await Promise.resolve();
      });

      expect(result.current.submitting).toBe(false);
    });

    it('does nothing when no onSubmit handler is provided', async () => {
      setupSchemaLoaderWithSchema(null);
      const { result } = renderHook(() =>
        useDslForm({ pageKey: 'order_new' }),
      );

      // Should not throw
      await act(async () => {
        await result.current.submit();
      });

      expect(result.current.submitting).toBe(false);
    });

    it('sets submitting=false even when onSubmit throws', async () => {
      setupSchemaLoaderWithSchema(null);
      const onSubmit = vi.fn().mockRejectedValue(new Error('submit failed'));

      const { result } = renderHook(() =>
        useDslForm({ pageKey: 'order_new', onSubmit }),
      );

      await act(async () => {
        try {
          await result.current.submit();
        } catch {
          // expected
        }
      });

      expect(result.current.submitting).toBe(false);
    });
  });

  // ── Effective permissions ──────────────────────────────────────────────────

  describe('effectivePermissions', () => {
    it('applies schema-level readonly to fields', () => {
      const schema = {
        modelCode: 'order',
        fields: [{ fieldCode: 'createdAt', readonly: true }],
      };
      setupSchemaLoaderWithSchema(schema);
      const { result } = renderHook(() =>
        useDslForm({ pageKey: 'order_detail' }),
      );
      expect(result.current.effectivePermissions.createdAt).toBe('readonly');
    });

    it('caller can tighten editable field to readonly via merge mode', () => {
      const schema = {
        modelCode: 'order',
        fields: [{ fieldCode: 'status', readonly: false }],
      };
      setupSchemaLoaderWithSchema(schema);
      const { result } = renderHook(() =>
        useDslForm({
          pageKey: 'order_detail',
          fieldPermissions: { status: 'readonly' },
          permissionMode: 'merge',
        }),
      );
      expect(result.current.effectivePermissions.status).toBe('readonly');
    });

    it('override mode allows caller to loosen a schema-readonly field', () => {
      const schema = {
        modelCode: 'order',
        fields: [{ fieldCode: 'status', readonly: true }],
      };
      setupSchemaLoaderWithSchema(schema);
      const { result } = renderHook(() =>
        useDslForm({
          pageKey: 'order_detail',
          fieldPermissions: { status: 'editable' },
          permissionMode: 'override',
        }),
      );
      expect(result.current.effectivePermissions.status).toBe('editable');
    });

    it('schema hidden field: hidden wins in merge mode', () => {
      const schema = {
        modelCode: 'order',
        fields: [{ fieldCode: 'internalNote', hidden: true }],
      };
      setupSchemaLoaderWithSchema(schema);
      const { result } = renderHook(() =>
        useDslForm({
          pageKey: 'order_detail',
          fieldPermissions: { internalNote: 'editable' },
          permissionMode: 'merge',
        }),
      );
      expect(result.current.effectivePermissions.internalNote).toBe('hidden');
    });
  });

  // ── rendererProps shape ────────────────────────────────────────────────────

  describe('rendererProps', () => {
    it('contains schema, tableName, recordId, token', () => {
      const fakeSchema = { modelCode: 'order' };
      setupSchemaLoaderWithSchema(fakeSchema);
      const { result } = renderHook(() =>
        useDslForm({
          pageKey: 'order_detail',
          tableName: 'order',
          recordId: 'rec-42',
          token: 'tok-abc',
        }),
      );

      const rp = result.current.rendererProps;
      expect(rp.schema).toEqual(fakeSchema);
      expect(rp.tableName).toBe('order');
      expect(rp.recordId).toBe('rec-42');
      expect(rp.token).toBe('tok-abc');
    });

    it('omits initialValues from rendererProps when initialValues is empty', () => {
      setupSchemaLoaderWithSchema(null);
      const { result } = renderHook(() =>
        useDslForm({ pageKey: 'order_new' }),
      );
      expect(result.current.rendererProps.initialValues).toBeUndefined();
    });

    it('includes initialValues in rendererProps when non-empty', () => {
      setupSchemaLoaderWithSchema(null);
      const { result } = renderHook(() =>
        useDslForm({
          pageKey: 'order_new',
          initialValues: { status: 'draft' },
        }),
      );
      expect(result.current.rendererProps.initialValues).toEqual({ status: 'draft' });
    });

    it('includes onSubmitOverride in rendererProps when onSubmit is provided', () => {
      setupSchemaLoaderWithSchema(null);
      const onSubmit = vi.fn().mockResolvedValue(undefined);
      const { result } = renderHook(() =>
        useDslForm({ pageKey: 'order_new', onSubmit }),
      );
      expect(typeof result.current.rendererProps.onSubmitOverride).toBe('function');
    });

    it('omits onSubmitOverride from rendererProps when no onSubmit', () => {
      setupSchemaLoaderWithSchema(null);
      const { result } = renderHook(() =>
        useDslForm({ pageKey: 'order_new' }),
      );
      expect(result.current.rendererProps.onSubmitOverride).toBeUndefined();
    });
  });
});
