import { renderHook, act } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { useFormSubmission } from '../useFormSubmission';
import { fetchResult } from '~/shared/services/http-client';
vi.mock('~/shared/services/http-client', () => ({ fetchResult: vi.fn() }));
function fixture(value = 'Original') {
  return {
    formData: { name: value },
    initialFormData: { name: 'Original' },
    schema: { modelCode: 'delivery', blocks: [], extension: {} },
    modelFields: { name: { dataType: 'string' } },
    tableName: 'delivery',
    recordPid: 'record-1',
    token: null,
    locale: 'zh-CN',
    schemaFieldNames: new Set(['name']),
    fieldMetaLoaded: true,
    dirtyFieldsRef: { current: new Set(['name']) },
    clearFormDraftRef: { current: vi.fn() },
    validateFormBeforeSubmit: () => ({ fieldErrors: {}, summaryErrors: [] }),
    setFieldErrors: vi.fn(),
    setSummaryErrors: vi.fn(),
    setError: vi.fn(),
    setSubmitting: vi.fn(),
    showInfoToast: vi.fn(),
    showSuccessToast: vi.fn(),
    showErrorToast: vi.fn(),
    notifyValidationFailure: vi.fn(),
    navigate: vi.fn(),
    handleAction: vi.fn(),
    t: (key: string) => key,
  };
}
describe('unchanged dynamic CRUD form submission', () => {
  it('leaves without an empty update request or a false save claim', async () => {
    vi.mocked(fetchResult).mockClear();
    const args = fixture();
    const { result } = renderHook(() => useFormSubmission(args as any));
    await act(async () => {
      await result.current.handleFormAction({ code: 'submit', action: 'save' });
    });
    expect(fetchResult).not.toHaveBeenCalled();
    expect(args.showInfoToast).toHaveBeenCalledWith('内容未修改，无需保存。');
    expect(args.showSuccessToast).not.toHaveBeenCalled();
    expect(args.navigate).toHaveBeenCalled();
    expect(args.setError).toHaveBeenCalledWith(null);
    expect(args.clearFormDraftRef.current).toHaveBeenCalled();
  });
  it('still sends a changed business value to the update route', async () => {
    vi.mocked(fetchResult).mockClear();
    vi.mocked(fetchResult).mockResolvedValue({ code: '0', data: { pid: 'record-1' } } as any);
    const args = fixture('Revised');
    const { result } = renderHook(() => useFormSubmission(args as any));
    await act(async () => {
      await result.current.handleFormAction({ code: 'submit', action: 'save' });
    });
    expect(fetchResult).toHaveBeenCalledWith(
      '/api/dynamic/delivery/record-1',
      expect.objectContaining({ method: 'put', params: { name: 'Revised' } }),
    );
    expect(args.showSuccessToast).toHaveBeenCalled();
    expect(args.showInfoToast).not.toHaveBeenCalled();
  });

  it.each(['dynamic', 'command', 'override'])(
    'does not reenter a pending %s submission',
    async (path) => {
      vi.mocked(fetchResult).mockClear();
      let finish!: (value: any) => void;
      const pending = new Promise<any>((resolve) => {
        finish = resolve;
      });
      vi.mocked(fetchResult).mockReturnValue(pending);
      const override = vi.fn(() => pending);
      const args = {
        ...fixture('Revised'),
        ...(path === 'override' ? { onSubmitOverride: override } : {}),
      };
      const button = {
        code: 'submit',
        action: 'save',
        ...(path === 'command' ? { commandCode: 'delivery:save' } : {}),
      };
      const { result } = renderHook(() => useFormSubmission(args as any));
      let first!: Promise<unknown>;
      let second!: Promise<unknown>;
      await act(async () => {
        first = result.current.handleFormAction(button);
        second = result.current.handleFormAction(button);
      });
      const pendingCalls =
        path === 'override' ? override.mock.calls.length : vi.mocked(fetchResult).mock.calls.length;
      finish({ code: '0', data: { pid: 'record-1' } });
      await act(async () => {
        await Promise.all([first, second]);
      });
      expect(pendingCalls).toBe(1);
      if (path === 'override') {
        expect(override).toHaveBeenCalledTimes(1);
        expect(fetchResult).not.toHaveBeenCalled();
      } else {
        expect(fetchResult).toHaveBeenCalledTimes(1);
      }
      await act(async () => {
        await result.current.handleFormAction(button);
      });
      expect(path === 'override' ? override : fetchResult).toHaveBeenCalledTimes(2);
    },
  );
});
