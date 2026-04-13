import { useNavigate, useParams } from 'react-router';
import { fetchResult } from '~/shared/services/http-client';
import { useStoreForm } from '~/hooks/useStoreForm';
import { useFormSubmit } from '~/hooks/useFormSubmit';
import StoreFormFields from '~/ui/StoreFormFields';
import { useAuth } from '~/contexts/AuthContext';

export default function StoreEditForm() {
  const navigate = useNavigate();
  const { storeId } = useParams();
  const { handleSubmitResult, validateFormAndAuth } = useFormSubmit();
  const {
    formData,
    provinces,
    loading,
    submitting,
    setSubmitting,
    errors,
    setErrors,
    toast,
    showToast,
    hideToast,
    checkCodeAvailability,
    handleInputChange,
    handleExtensionChange,
    handleAddressChange,
  } = useStoreForm(storeId);
  const { token } = useAuth();

  // 提交表单
  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    // 使用统一的验证和认证检查
    const validationResult = validateFormAndAuth(formData, token);
    if (!validationResult.isValid) {
      return;
    }

    if (!storeId) {
      showToast('门店ID不存在', 'error');
      return;
    }

    setSubmitting(true);
    try {
      // 准备提交数据
      const submitData = {
        ...formData,
        address: {
          provinceCode: formData.provinceCode,
          provinceName: formData.provinceName,
          cityCode: formData.cityCode,
          cityName: formData.cityName,
          districtCode: formData.districtCode,
          districtName: formData.districtName,
          streetCode: formData.streetCode,
          streetName: formData.streetName,
          detailAddress: formData.detailAddress,
          postalCode: formData.postalCode,
        },
      };

      // API 调用
      const result = await fetchResult(`/api/stores/${storeId}`, {
        method: 'put',
        params: submitData,
        token: token ?? undefined,
      });

      // 使用 useFormSubmit hook 处理结果
      handleSubmitResult(result, {
        successMessage: '门店信息更新成功！',
        redirectPath: '/enterprise/stores',
        onSuccess: () => {},
        onError: (result) => {
          console.error('门店更新失败:', result);
          // 如果是验证错误，设置字段级别的错误信息
          if (result.code === '10000' && result.data && typeof result.data === 'object') {
            const serverErrors = result.data as Record<string, string>;
            setErrors(serverErrors);
          }
        },
      });
    } catch (error) {
      console.error('提交门店数据失败:', error);
      showToast('网络错误，请检查网络连接后重试', 'error');
    } finally {
      setSubmitting(false);
    }
  };

  const handleCancel = () => {
    navigate('/enterprise/stores');
  };

  if (loading) {
    return (
      <div className="flex h-64 items-center justify-center">
        <div className="h-32 w-32 animate-spin rounded-full border-b-2 border-blue-500"></div>
      </div>
    );
  }

  return (
    <StoreFormFields
      formData={formData}
      provinces={provinces}
      errors={errors}
      toast={toast}
      submitting={submitting}
      isEdit={true}
      onInputChange={handleInputChange}
      onExtensionChange={handleExtensionChange}
      onAddressChange={handleAddressChange}
      onCodeBlur={checkCodeAvailability}
      onSubmit={handleSubmit}
      onCancel={handleCancel}
      onToastClose={hideToast}
    />
  );
}
