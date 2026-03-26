import React from 'react';
import { InputField, SelectField, TextareaField } from '~/components/FormField';
import { industryOptions, type TenantFormData, type FormErrors } from '~/hooks/useTenantForm';

interface TenantFormFieldsProps {
  formData: TenantFormData;
  errors: FormErrors;
  onChange: (
    e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>,
  ) => void;
  disabled?: boolean;
  showLogo?: boolean;
  showWebsite?: boolean;
  variant?: 'create' | 'edit' | 'selection';
}

export default function TenantFormFields({
  formData,
  errors,
  onChange,
  disabled = false,
  showLogo = true,
  showWebsite = true,
  variant = 'edit',
}: TenantFormFieldsProps) {
  // 根据不同变体调整样式
  const getFieldClassName = () => {
    switch (variant) {
      case 'selection':
        return 'space-y-2';
      case 'create':
        return '';
      default:
        return '';
    }
  };

  const getGridClassName = () => {
    switch (variant) {
      case 'selection':
        return 'grid md:grid-cols-2 gap-6';
      default:
        return 'grid grid-cols-1 md:grid-cols-2 gap-6';
    }
  };

  const getLabelText = (field: string) => {
    const labels = {
      name: variant === 'selection' ? '租户名称' : '企业名称',
      displayName: '显示名称',
      logo: '企业Logo',
      industry: variant === 'selection' ? '行业' : '所属行业',
      contactEmail: '联系邮箱',
      contactPhone: '联系电话',
      website: '官方网站',
      description: variant === 'selection' ? '描述' : '企业描述',
    };
    return labels[field as keyof typeof labels] || field;
  };

  const getPlaceholder = (field: string) => {
    const placeholders = {
      name: variant === 'selection' ? '输入租户名称' : '请输入企业名称',
      displayName: variant === 'selection' ? '输入显示名称' : '请输入显示名称',
      logo: '请输入Logo图片地址',
      contactEmail: variant === 'selection' ? '输入联系邮箱' : '请输入联系邮箱',
      contactPhone: variant === 'selection' ? '输入联系电话' : '请输入联系电话',
      website: '请输入官方网站地址',
      description: variant === 'selection' ? '输入租户描述' : '请输入企业描述',
    };
    return placeholders[field as keyof typeof placeholders] || '';
  };

  return (
    <>
      {/* 为selection变体添加隐藏字段以保持API兼容性 */}
      {variant === 'selection' && (
        <>
          <input type="hidden" name="tenantName" value={formData.name} />
          <input type="hidden" name="displayName" value={formData.displayName} />
          <input type="hidden" name="industry" value={formData.industry} />
          <input type="hidden" name="contactEmail" value={formData.contactEmail} />
          <input type="hidden" name="contactPhone" value={formData.contactPhone} />
          <input type="hidden" name="description" value={formData.description} />
        </>
      )}

      <div className={getGridClassName()}>
        {/* 企业/租户名称 */}
        <InputField
          label={getLabelText('name')}
          name="name"
          type="text"
          value={formData.name}
          onChange={onChange}
          placeholder={getPlaceholder('name')}
          required
          error={errors.name}
          disabled={disabled}
          className={getFieldClassName()}
        />

        {/* 显示名称 */}
        <InputField
          label={getLabelText('displayName')}
          name="displayName"
          type="text"
          value={formData.displayName}
          onChange={onChange}
          placeholder={getPlaceholder('displayName')}
          error={errors.displayName}
          disabled={disabled}
          className={getFieldClassName()}
        />

        {/* Logo - 可选显示 */}
        {showLogo && (
          <InputField
            label={getLabelText('logo')}
            name="logo"
            type="text"
            value={formData.logo}
            onChange={onChange}
            placeholder={getPlaceholder('logo')}
            error={errors.logo}
            disabled={disabled}
            className={getFieldClassName()}
          />
        )}

        {/* 所属行业 */}
        <SelectField
          label={getLabelText('industry')}
          name="industry"
          value={formData.industry}
          onChange={onChange}
          options={industryOptions}
          // placeholder={variant === 'selection' ? '选择行业' : undefined}
          error={errors.industry}
          disabled={disabled}
          className={getFieldClassName()}
        />

        {/* 联系邮箱 */}
        <InputField
          label={getLabelText('contactEmail')}
          name="contactEmail"
          type="email"
          value={formData.contactEmail}
          onChange={onChange}
          placeholder={getPlaceholder('contactEmail')}
          error={errors.contactEmail}
          disabled={disabled}
          className={getFieldClassName()}
        />

        {/* 联系电话 */}
        <InputField
          label={getLabelText('contactPhone')}
          name="contactPhone"
          type="tel"
          value={formData.contactPhone}
          onChange={onChange}
          placeholder={getPlaceholder('contactPhone')}
          error={errors.contactPhone}
          disabled={disabled}
          className={getFieldClassName()}
        />
      </div>

      {/* 官方网站 - 可选显示，单独一行 */}
      {showWebsite && (
        <InputField
          label={getLabelText('website')}
          name="website"
          type="url"
          value={formData.website}
          onChange={onChange}
          placeholder={getPlaceholder('website')}
          error={errors.website}
          disabled={disabled}
          className={variant === 'selection' ? 'space-y-2' : 'mt-6'}
        />
      )}

      {/* 企业描述 - 单独一行 */}
      <TextareaField
        label={getLabelText('description')}
        name="description"
        value={formData.description}
        onChange={onChange}
        placeholder={getPlaceholder('description')}
        rows={4}
        error={errors.description}
        disabled={disabled}
        className={variant === 'selection' ? 'space-y-2' : 'mt-6'}
      />
    </>
  );
}
