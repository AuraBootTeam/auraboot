import { BuildingStorefrontIcon, ArrowLeftIcon } from '@heroicons/react/24/outline';
import AddressSelector from '~/ui/AddressSelector';
import Toast from '~/ui/Toast';
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
            className="rounded-card text-text-2 hover:bg-hover hover:text-text mr-4 p-2 transition-colors"
          >
            <ArrowLeftIcon className="h-5 w-5" />
          </button>
          <div className="flex items-center">
            <BuildingStorefrontIcon className="text-accent mr-2 h-6 w-6" />
            <h1 className="text-text text-2xl font-bold">{isEdit ? '编辑门店' : '新建门店'}</h1>
          </div>
        </div>
      </div>

      {/* 表单 */}
      <form onSubmit={onSubmit} className="space-y-6">
        {/* 基本信息 */}
        <div className="rounded-card bg-panel p-6 shadow">
          <h2 className="text-text mb-4 text-lg font-medium">基本信息</h2>
          <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
            <div>
              <label className="text-text-2 mb-1 block text-sm font-medium">
                门店名称 <span className="text-status-red">*</span>
              </label>
              <input
                type="text"
                value={formData.name}
                onChange={(e) => onInputChange('name', e.target.value)}
                className={`rounded-card focus:border-accent focus-visible:shadow-focus w-full border px-3 py-2 focus:outline-none ${
                  errors.name ? 'border-status-red' : 'border-border-strong'
                }`}
                placeholder="请输入门店名称"
              />
              {errors.name && <p className="text-status-red mt-1 text-sm">{errors.name}</p>}
            </div>

            <div>
              <label className="text-text-2 mb-1 block text-sm font-medium">
                门店编码 <span className="text-status-red">*</span>
              </label>
              <input
                type="text"
                value={formData.code}
                onChange={(e) => onInputChange('code', e.target.value)}
                onBlur={onCodeBlur}
                className={`rounded-card focus:border-accent focus-visible:shadow-focus w-full border px-3 py-2 focus:outline-none ${
                  errors.code ? 'border-status-red' : 'border-border-strong'
                }`}
                placeholder="请输入门店编码"
              />
              {errors.code && <p className="text-status-red mt-1 text-sm">{errors.code}</p>}
            </div>

            <div>
              <label className="text-text-2 mb-1 block text-sm font-medium">
                门店类型 <span className="text-status-red">*</span>
              </label>
              <select
                value={formData.type}
                onChange={(e) => onInputChange('type', e.target.value)}
                className={`rounded-card focus:border-accent focus-visible:shadow-focus w-full border px-3 py-2 focus:outline-none ${
                  errors.type ? 'border-status-red' : 'border-border-strong'
                }`}
              >
                {storeTypes.map((type) => (
                  <option key={type.value} value={type.value}>
                    {type.label}
                  </option>
                ))}
              </select>
              {errors.type && <p className="text-status-red mt-1 text-sm">{errors.type}</p>}
            </div>

            <div>
              <label className="text-text-2 mb-1 block text-sm font-medium">
                门店状态 <span className="text-status-red">*</span>
              </label>
              <select
                value={formData.status}
                onChange={(e) => onInputChange('status', e.target.value)}
                className={`rounded-card focus:border-accent focus-visible:shadow-focus w-full border px-3 py-2 focus:outline-none ${
                  errors.status ? 'border-status-red' : 'border-border-strong'
                }`}
              >
                {storeStatuses.map((status) => (
                  <option key={status.value} value={status.value}>
                    {status.label}
                  </option>
                ))}
              </select>
              {errors.status && <p className="text-status-red mt-1 text-sm">{errors.status}</p>}
            </div>
          </div>
        </div>

        {/* 联系信息 */}
        <div className="rounded-card bg-panel p-6 shadow">
          <h2 className="text-text mb-4 text-lg font-medium">联系信息</h2>
          <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
            <div>
              <label className="text-text-2 mb-1 block text-sm font-medium">联系电话</label>
              <input
                type="tel"
                value={formData.contactPhone}
                onChange={(e) => onInputChange('contactPhone', e.target.value)}
                className={`rounded-card focus:border-accent focus-visible:shadow-focus w-full border px-3 py-2 focus:outline-none ${
                  errors.contactPhone ? 'border-status-red' : 'border-border-strong'
                }`}
                placeholder="请输入联系电话"
              />
              {errors.contactPhone && (
                <p className="text-status-red mt-1 text-sm">{errors.contactPhone}</p>
              )}
            </div>

            <div>
              <label className="text-text-2 mb-1 block text-sm font-medium">联系邮箱</label>
              <input
                type="email"
                value={formData.contactEmail}
                onChange={(e) => onInputChange('contactEmail', e.target.value)}
                className={`rounded-card focus:border-accent focus-visible:shadow-focus w-full border px-3 py-2 focus:outline-none ${
                  errors.contactEmail ? 'border-status-red' : 'border-border-strong'
                }`}
                placeholder="请输入联系邮箱"
              />
              {errors.contactEmail && (
                <p className="text-status-red mt-1 text-sm">{errors.contactEmail}</p>
              )}
            </div>
          </div>
        </div>

        {/* 地址信息 */}
        <div className="rounded-card bg-panel p-6 shadow">
          <h2 className="text-text mb-4 text-lg font-medium">地址信息</h2>
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
            <label className="text-text-2 mb-1 block text-sm font-medium">
              详细地址 <span className="text-status-red">*</span>
            </label>
            <input
              type="text"
              name="detailAddress"
              value={formData.detailAddress}
              onChange={(e) => onInputChange('detailAddress', e.target.value)}
              className={`rounded-card focus:border-accent focus-visible:shadow-focus w-full border px-3 py-2 focus:outline-none ${
                errors.detailAddress ? 'border-status-red' : 'border-border-strong'
              }`}
              placeholder="请输入详细地址"
            />
            {errors.detailAddress && (
              <p className="text-status-red mt-1 text-sm">{errors.detailAddress}</p>
            )}
          </div>
        </div>

        {/* 其他信息 */}
        <div className="rounded-card bg-panel p-6 shadow">
          <h2 className="text-text mb-4 text-lg font-medium">其他信息</h2>

          <div className="mt-4">
            <label className="text-text-2 mb-1 block text-sm font-medium">门店描述</label>
            <textarea
              value={formData.description}
              onChange={(e) => onInputChange('description', e.target.value)}
              rows={3}
              className="rounded-card border-border-strong focus:border-accent focus-visible:shadow-focus w-full border px-3 py-2 focus:outline-none"
              placeholder="请输入门店描述"
            />
          </div>
        </div>

        {/* 提交按钮 */}
        <div className="flex justify-end space-x-4">
          <button
            type="button"
            onClick={onCancel}
            className="rounded-card border-border-strong text-text-2 hover:bg-subtle border px-4 py-2 transition-colors"
          >
            取消
          </button>
          <button
            type="submit"
            disabled={submitting}
            className="rounded-card bg-accent hover:bg-accent-hover px-4 py-2 text-white transition-colors disabled:cursor-not-allowed disabled:opacity-50"
          >
            {submitting ? '提交中...' : isEdit ? '更新' : '创建'}
          </button>
        </div>
      </form>
    </div>
  );
}
