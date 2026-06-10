import { renderHook } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';

const mockNavigate = vi.fn();
const showSuccessToast = vi.fn();
const showErrorToast = vi.fn();
const showWarningToast = vi.fn();

vi.mock('react-router', () => ({
  useNavigate: () => mockNavigate,
}));

vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({ showSuccessToast, showErrorToast, showWarningToast }),
}));

vi.mock('~/utils/type', () => ({
  ResultHelper: {
    isSuccess: (r: { code: string }) => r.code === '0',
    handleError: (result: any, opts: any) => {
      if (result.code === '10000') opts.onValidationError?.(result);
      else if (result.code === '401') opts.onAuthError?.(result);
      else if (result.code === '40000') opts.onBusinessError?.(result);
      else opts.onSystemError?.(result);
    },
  },
}));

import { useFormSubmit } from '../useFormSubmit';

describe('useFormSubmit', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Reset document.querySelector mock
    vi.restoreAllMocks();
  });

  it('handleSubmitResult shows success toast on success result', () => {
    const { result } = renderHook(() => useFormSubmit());
    const onSuccess = vi.fn();

    result.current.handleSubmitResult(
      { code: '0', data: { id: 1 }, success: true },
      { successMessage: 'Saved!', onSuccess },
    );

    expect(showSuccessToast).toHaveBeenCalledWith('Saved!');
    expect(onSuccess).toHaveBeenCalledWith({ id: 1 });
  });

  it('handleSubmitResult uses default success message', () => {
    const { result } = renderHook(() => useFormSubmit());

    result.current.handleSubmitResult({ code: '0', data: null, success: true });

    expect(showSuccessToast).toHaveBeenCalledWith('操作成功');
  });

  it('handleSubmitResult navigates to redirectPath on success', () => {
    const { result } = renderHook(() => useFormSubmit());

    result.current.handleSubmitResult(
      { code: '0', data: null, success: true },
      { redirectPath: '/dashboard' },
    );

    expect(mockNavigate).toHaveBeenCalledWith('/dashboard');
  });

  it('handleSubmitResult does not call onSuccess when data is null', () => {
    const { result } = renderHook(() => useFormSubmit());
    const onSuccess = vi.fn();

    result.current.handleSubmitResult(
      { code: '0', data: null, success: true },
      { onSuccess },
    );

    expect(onSuccess).not.toHaveBeenCalled();
  });

  it('handleSubmitResult does not show toast when showToast=false', () => {
    const { result } = renderHook(() => useFormSubmit());

    result.current.handleSubmitResult(
      { code: '0', data: 'x', success: true },
      { showToast: false },
    );

    expect(showSuccessToast).not.toHaveBeenCalled();
  });

  it('handleSubmitResult calls onValidationError for validation error code', () => {
    const { result } = renderHook(() => useFormSubmit());
    const onError = vi.fn();

    result.current.handleSubmitResult(
      { code: '10000', data: null, success: false },
      { onError },
    );

    expect(showWarningToast).toHaveBeenCalledWith('请检查输入的信息');
    expect(onError).toHaveBeenCalled();
  });

  it('handleSubmitResult calls onError for auth error code', () => {
    const { result } = renderHook(() => useFormSubmit());
    const onError = vi.fn();

    result.current.handleSubmitResult(
      { code: '401', data: 'Unauthorized', success: false },
      { onError },
    );

    expect(showErrorToast).toHaveBeenCalledWith('Unauthorized');
    expect(onError).toHaveBeenCalled();
  });

  it('handleSubmitResult calls onError for business error code', () => {
    const { result } = renderHook(() => useFormSubmit());
    const onError = vi.fn();

    result.current.handleSubmitResult(
      { code: '40000', data: 'Business rule violated', success: false },
      { onError },
    );

    expect(showErrorToast).toHaveBeenCalledWith('Business rule violated');
    expect(onError).toHaveBeenCalled();
  });

  describe('validateFormAndAuth', () => {
    it('returns isValid=false with auth error when token is null', () => {
      const { result } = renderHook(() => useFormSubmit());

      const out = result.current.validateFormAndAuth({}, null);

      expect(out.isValid).toBe(false);
      expect(out.errors.auth).toBe('未登录');
      expect(showErrorToast).toHaveBeenCalledWith('请先登录');
    });

    it('returns isValid=true when form data is valid and token provided', () => {
      const { result } = renderHook(() => useFormSubmit());

      const out = result.current.validateFormAndAuth({ name: 'test' }, 'valid-token');

      expect(out.isValid).toBe(true);
      expect(out.errors).toEqual({});
    });
  });
});
