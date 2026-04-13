import React, { useEffect } from 'react';
import { useNavigate } from 'react-router';
import { useTheme } from '~/contexts/ThemeContext';
import { useFormSubmit } from '~/hooks/useFormSubmit';
import { useTenantForm, type TenantInfo } from '~/hooks/useTenantForm';
import { fetchResult } from '~/shared/services/http-client';
import TenantFormFields from '~/ui/TenantFormFields';
import { useAuth } from '~/contexts/AuthContext';

export default function TenantEditForm() {
  const navigate = useNavigate();
  const { isDark } = useTheme();
  const { handleSubmitResult, validateFormAndAuth } = useFormSubmit();
  const {
    formData,
    loading,
    setLoading,
    submitting,
    setSubmitting,
    errors,
    setErrors,
    tenantPid,
    handleInputChange,
    validateForm,
    setTenantFormData,
  } = useTenantForm();
  const { token } = useAuth();

  // 获取当前租户信息
  const fetchTenantInfo = async () => {
    if (!token) {
      return;
    }

    setLoading(true);
    try {
      const result = await fetchResult<TenantInfo>('/api/tenant/info', {
        method: 'get',
        token: token ?? undefined,
      });

      handleSubmitResult(result, {
        showToast: false,
        onSuccess: (tenant: TenantInfo) => {
          setTenantFormData(tenant);
        },
        onError: (error) => {
          console.error('获取租户信息失败:', error);
        },
      });
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchTenantInfo();
  }, [token]);

  // 提交表单
  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    // 使用统一的验证和认证检查
    const validationResult = validateFormAndAuth(formData, token);
    if (!validationResult.isValid) {
      return;
    }

    if (!validateForm()) {
      return;
    }

    if (!tenantPid) {
      return;
    }

    setSubmitting(true);
    try {
      const result = await fetchResult(`/api/tenant/${tenantPid}`, {
        method: 'put',
        params: formData,
        token: token ?? undefined,
      });

      // 使用 useFormSubmit hook 处理结果
      handleSubmitResult(result, {
        successMessage: '企业信息更新成功！',
        redirectPath: '/enterprise/info',
        onSuccess: () => {},
        onError: (result) => {
          console.error('企业信息更新失败:', result);
          // 如果是验证错误，设置字段级别的错误信息
          if (result.code === '10000' && result.data && typeof result.data === 'object') {
            const serverErrors = result.data as Record<string, string>;
            setErrors(serverErrors);
          }
        },
      });
    } catch (error) {
      console.error('提交企业数据失败:', error);
    } finally {
      setSubmitting(false);
    }
  };

  const handleCancel = () => {
    navigate('/enterprise/info');
  };

  if (loading) {
    return (
      <div className="flex h-64 items-center justify-center">
        <div className="h-32 w-32 animate-spin rounded-full border-b-2 border-blue-500 dark:border-blue-400"></div>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-6xl p-6">
      {/* 页面标题 */}
      <div className="mb-8">
        <h1 className="text-3xl font-bold text-gray-900 dark:text-white">编辑企业信息</h1>
        <p className="mt-1 text-gray-600 dark:text-gray-300">更新您的企业基本信息</p>
      </div>

      {/* 表单 */}
      <form onSubmit={handleSubmit} className="rounded-lg bg-white p-6 shadow-lg dark:bg-gray-800">
        <TenantFormFields
          formData={formData}
          errors={errors}
          onChange={handleInputChange}
          disabled={submitting}
          showLogo={true}
          showWebsite={true}
          variant="edit"
        />

        {/* 提交按钮 */}
        <div className="mt-8 flex justify-end space-x-4">
          <button
            type="button"
            onClick={handleCancel}
            className="rounded-md border border-gray-300 px-6 py-2 text-gray-700 hover:bg-gray-50 focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 focus:outline-none dark:border-gray-600 dark:text-gray-300 dark:hover:bg-gray-700 dark:focus:ring-offset-gray-800"
          >
            取消
          </button>
          <button
            type="submit"
            disabled={submitting}
            className="rounded-md bg-blue-600 px-6 py-2 text-white hover:bg-blue-700 focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 focus:outline-none disabled:cursor-not-allowed disabled:opacity-50 dark:bg-blue-500 dark:hover:bg-blue-600 dark:focus:ring-offset-gray-800"
          >
            {submitting ? '保存中...' : '保存'}
          </button>
        </div>
      </form>
    </div>
  );
}
