import { useNavigate } from 'react-router';
import { useToastContext } from '~/contexts/ToastContext';
import { ResultHelper, type Result } from '~/utils/type';

export function useFormSubmit() {
  const navigate = useNavigate();
  const { showSuccessToast, showErrorToast, showWarningToast } = useToastContext();

  const handleSubmitResult = <T>(
    result: Result<T>,
    options: {
      successMessage?: string;
      redirectPath?: string;
      showToast?: boolean;
      onSuccess?: (data: T) => void;
      onError?: (error: any) => void;
    } = {},
  ) => {
    const {
      successMessage = '操作成功',
      redirectPath,
      showToast: shouldShowToast = true,
      onSuccess,
      onError,
    } = options;

    if (ResultHelper.isSuccess(result)) {
      if (shouldShowToast) {
        showSuccessToast(successMessage);
      }
      if (result.data !== null && result.data !== undefined) {
        onSuccess?.(result.data);
      }
      if (redirectPath) {
        navigate(redirectPath);
      }
    } else {
      ResultHelper.handleError(result, {
        onValidationError: (result) => {
          // 验证错误不显示toast，而是通过onError回调传递给表单组件处理
          // 表单组件应该在对应字段旁边显示红色错误信息
          showWarningToast('请检查输入的信息');
          onError?.(result);
        },
        onAuthError: (result) => {
          showErrorToast(result.data as string);
          onError?.(result);
        },
        onBusinessError: (result) => {
          showErrorToast(result.data as string);
          onError?.(result);
        },
        onSystemError: (result) => {
          showErrorToast(result.data as string);
          onError?.(result);
        },
      });
    }
  };

  const validateFormAndAuth = (formData: any, token: any) => {
    // 表单验证
    const errors: Record<string, string> = {};

    // 这里添加具体的验证逻辑
    // 例如：
    // if (!formData.name) errors.name = '店铺名称不能为空';
    // if (!formData.address) errors.address = '地址不能为空';

    if (Object.keys(errors).length > 0) {
      // 滚动到第一个错误字段
      const firstErrorField = Object.keys(errors)[0];
      const element = document.querySelector(`[name="${firstErrorField}"]`);
      if (element) {
        element.scrollIntoView({ behavior: 'smooth', block: 'center' });
      }
      return { isValid: false, errors };
    }

    // 认证检查
    if (!token) {
      showErrorToast('请先登录');
      return { isValid: false, errors: { auth: '未登录' } };
    }

    return { isValid: true, errors: {} };
  };

  return {
    handleSubmitResult,
    validateFormAndAuth,
  };
}
