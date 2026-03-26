import { BuildingStorefrontIcon, ArrowLeftIcon } from '@heroicons/react/24/outline';
import AddressSelector from '~/components/AddressSelector';
import Toast from '~/components/Toast';
import { storeTypes, storeStatuses, type Province, type StoreFormData } from '~/hooks/useStoreForm';

interface StoreFormFieldsProps {
  formData: StoreFormData;
  provinces: Province[];
  errors: Record<string, string>;
  toast: {
    show: boolean;
    message: string;
    type: 'success' | 'error' | 'warning';
  };
  submitting: boolean;
  isEdit: boolean;
  onInputChange: (field: keyof StoreFormData, value: any) => void;
  onExtensionChange: (field: string, value: string) => void;
  onAddressChange: (address: any) => void;
  onCodeBlur: () => void;
  onSubmit: (e: React.FormEvent) => void;
  onCancel: () => void;
  onToastClose: () => void;
}

export default function StoreFormFields({
  formData,
  provinces,
  errors,
  toast,
  submitting,
  isEdit,
  onInputChange,
  onExtensionChange: _onExtensionChange,
  onAddressChange,
  onCodeBlur,
  onSubmit,
  onCancel,
  onToastClose,
}: StoreFormFieldsProps) {
  return (
    <div className="mx-auto max-w-4xl p-6">
      {/* Toast 组件 */}
      <Toast message={toast.message} type={toast.type} show={toast.show} onClose={onToastClose} />

      {/* 页面头部 */}
      <div className="mb-6">
        <div className="mb-4 flex items-center">
          <button
            onClick={onCancel}
            className="mr-4 rounded-lg p-2 text-gray-600 transition-colors hover:bg-gray-100 hover:text-gray-800"
          >
            <ArrowLeftIcon className="h-5 w-5" />
          </button>
          <div className="flex items-center">
            <BuildingStorefrontIcon className="mr-2 h-6 w-6 text-blue-600" />
            <h1 className="text-2xl font-bold text-gray-900">{isEdit ? '编辑门店' : '新建门店'}</h1>
          </div>
        </div>
      </div>

      {/* 表单 */}
      <form onSubmit={onSubmit} className="space-y-6">
        {/* 基本信息 */}
        <div className="rounded-lg bg-white p-6 shadow">
          <h2 className="mb-4 text-lg font-medium text-gray-900">基本信息</h2>
          <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
            <div>
              <label className="mb-1 block text-sm font-medium text-gray-700">
                门店名称 <span className="text-red-500">*</span>
              </label>
              <input
                type="text"
                value={formData.name}
                onChange={(e) => onInputChange('name', e.target.value)}
                className={`w-full rounded-lg border px-3 py-2 focus:border-blue-500 focus:ring-2 focus:ring-blue-500 ${
                  errors.name ? 'border-red-300' : 'border-gray-300'
                }`}
                placeholder="请输入门店名称"
              />
              {errors.name && <p className="mt-1 text-sm text-red-600">{errors.name}</p>}
            </div>

            <div>
              <label className="mb-1 block text-sm font-medium text-gray-700">
                门店编码 <span className="text-red-500">*</span>
              </label>
              <input
                type="text"
                value={formData.code}
                onChange={(e) => onInputChange('code', e.target.value)}
                onBlur={onCodeBlur}
                className={`w-full rounded-lg border px-3 py-2 focus:border-blue-500 focus:ring-2 focus:ring-blue-500 ${
                  errors.code ? 'border-red-300' : 'border-gray-300'
                }`}
                placeholder="请输入门店编码"
              />
              {errors.code && <p className="mt-1 text-sm text-red-600">{errors.code}</p>}
            </div>

            <div>
              <label className="mb-1 block text-sm font-medium text-gray-700">
                门店类型 <span className="text-red-500">*</span>
              </label>
              <select
                value={formData.type}
                onChange={(e) => onInputChange('type', e.target.value)}
                className={`w-full rounded-lg border px-3 py-2 focus:border-blue-500 focus:ring-2 focus:ring-blue-500 ${
                  errors.type ? 'border-red-300' : 'border-gray-300'
                }`}
              >
                {storeTypes.map((type) => (
                  <option key={type.value} value={type.value}>
                    {type.label}
                  </option>
                ))}
              </select>
              {errors.type && <p className="mt-1 text-sm text-red-600">{errors.type}</p>}
            </div>

            <div>
              <label className="mb-1 block text-sm font-medium text-gray-700">
                门店状态 <span className="text-red-500">*</span>
              </label>
              <select
                value={formData.status}
                onChange={(e) => onInputChange('status', e.target.value)}
                className={`w-full rounded-lg border px-3 py-2 focus:border-blue-500 focus:ring-2 focus:ring-blue-500 ${
                  errors.status ? 'border-red-300' : 'border-gray-300'
                }`}
              >
                {storeStatuses.map((status) => (
                  <option key={status.value} value={status.value}>
                    {status.label}
                  </option>
                ))}
              </select>
              {errors.status && <p className="mt-1 text-sm text-red-600">{errors.status}</p>}
            </div>
          </div>
        </div>

        {/* 联系信息 */}
        <div className="rounded-lg bg-white p-6 shadow">
          <h2 className="mb-4 text-lg font-medium text-gray-900">联系信息</h2>
          <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
            <div>
              <label className="mb-1 block text-sm font-medium text-gray-700">联系电话</label>
              <input
                type="tel"
                value={formData.contactPhone}
                onChange={(e) => onInputChange('contactPhone', e.target.value)}
                className={`w-full rounded-lg border px-3 py-2 focus:border-blue-500 focus:ring-2 focus:ring-blue-500 ${
                  errors.contactPhone ? 'border-red-300' : 'border-gray-300'
                }`}
                placeholder="请输入联系电话"
              />
              {errors.contactPhone && (
                <p className="mt-1 text-sm text-red-600">{errors.contactPhone}</p>
              )}
            </div>

            <div>
              <label className="mb-1 block text-sm font-medium text-gray-700">联系邮箱</label>
              <input
                type="email"
                value={formData.contactEmail}
                onChange={(e) => onInputChange('contactEmail', e.target.value)}
                className={`w-full rounded-lg border px-3 py-2 focus:border-blue-500 focus:ring-2 focus:ring-blue-500 ${
                  errors.contactEmail ? 'border-red-300' : 'border-gray-300'
                }`}
                placeholder="请输入联系邮箱"
              />
              {errors.contactEmail && (
                <p className="mt-1 text-sm text-red-600">{errors.contactEmail}</p>
              )}
            </div>
          </div>
        </div>

        {/* 地址信息 */}
        <div className="rounded-lg bg-white p-6 shadow">
          <h2 className="mb-4 text-lg font-medium text-gray-900">地址信息</h2>
          <AddressSelector
            value={{
              provinceCode: formData.provinceCode,
              provinceName: formData.provinceName,
              cityCode: formData.cityCode,
              cityName: formData.cityName,
              districtCode: formData.districtCode,
              districtName: formData.districtName,
              streetCode: formData.streetCode,
              streetName: formData.streetName,
            }}
            onChange={onAddressChange}
            provinces={provinces}
            errors={{
              provinceCode: errors.provinceCode,
              cityCode: errors.cityCode,
              districtCode: errors.districtCode,
            }}
          />

          <div className="mt-4">
            <label className="mb-1 block text-sm font-medium text-gray-700">
              详细地址 <span className="text-red-500">*</span>
            </label>
            <input
              type="text"
              name="detailAddress"
              value={formData.detailAddress}
              onChange={(e) => onInputChange('detailAddress', e.target.value)}
              className={`w-full rounded-lg border px-3 py-2 focus:border-blue-500 focus:ring-2 focus:ring-blue-500 ${
                errors.detailAddress ? 'border-red-300' : 'border-gray-300'
              }`}
              placeholder="请输入详细地址"
            />
            {errors.detailAddress && (
              <p className="mt-1 text-sm text-red-600">{errors.detailAddress}</p>
            )}
          </div>
        </div>

        {/* 其他信息 */}
        <div className="rounded-lg bg-white p-6 shadow">
          <h2 className="mb-4 text-lg font-medium text-gray-900">其他信息</h2>

          <div className="mt-4">
            <label className="mb-1 block text-sm font-medium text-gray-700">门店描述</label>
            <textarea
              value={formData.description}
              onChange={(e) => onInputChange('description', e.target.value)}
              rows={3}
              className="w-full rounded-lg border border-gray-300 px-3 py-2 focus:border-blue-500 focus:ring-2 focus:ring-blue-500"
              placeholder="请输入门店描述"
            />
          </div>
        </div>

        {/* 提交按钮 */}
        <div className="flex justify-end space-x-4">
          <button
            type="button"
            onClick={onCancel}
            className="rounded-lg border border-gray-300 px-4 py-2 text-gray-700 transition-colors hover:bg-gray-50"
          >
            取消
          </button>
          <button
            type="submit"
            disabled={submitting}
            className="rounded-lg bg-blue-600 px-4 py-2 text-white transition-colors hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {submitting ? '提交中...' : isEdit ? '更新' : '创建'}
          </button>
        </div>
      </form>
    </div>
  );
}
