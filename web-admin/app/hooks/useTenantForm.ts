import { useState } from 'react';
import { fetchResult } from '~/shared/services/http-client';

// 租户相关类型定义
export interface TenantInfo {
  id: number;
  pid: string;
  name: string;
  displayName: string;
  logo?: string;
  industry?: string;
  contactEmail?: string;
  contactPhone?: string;
  website?: string;
  status: string;
  description?: string;
  createdAt: string;
  updatedAt: string;
}

export interface TenantFormData {
  name: string;
  displayName: string;
  logo: string;
  industry: string;
  contactEmail: string;
  contactPhone: string;
  website: string;
  description: string;
}

export interface FormErrors {
  [key: string]: string;
}

export const initialTenantFormData: TenantFormData = {
  name: '',
  displayName: '',
  logo: '',
  industry: '',
  contactEmail: '',
  contactPhone: '',
  website: '',
  description: '',
};

// 行业选项
export const industryOptions = [
  { value: '', label: '请选择行业' },
  { value: 'retail', label: '零售业' },
  { value: 'catering', label: '餐饮业' },
  { value: 'education', label: '教育培训' },
  { value: 'healthcare', label: '医疗健康' },
  { value: 'finance', label: '金融服务' },
  { value: 'technology', label: '科技互联网' },
  { value: 'manufacturing', label: '制造业' },
  { value: 'logistics', label: '物流运输' },
  { value: 'real_estate', label: '房地产' },
  { value: 'entertainment', label: '文化娱乐' },
  { value: 'other', label: '其他' },
];

// 获取行业显示值
export const getIndustryLabel = (value: string): string => {
  const option = industryOptions.find((opt) => opt.value === value);
  return option ? option.label : value;
};

// 获取行业标准值
export const getIndustryValue = (label: string): string => {
  const option = industryOptions.find((opt) => opt.label === label);
  return option ? option.value : label;
};

// 租户表单状态管理 hook
export function useTenantForm() {
  const [formData, setFormData] = useState<TenantFormData>(initialTenantFormData);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [errors, setErrors] = useState<FormErrors>({});
  const [tenantPid, setTenantPid] = useState<string>('');

  // 处理输入变化
  const handleInputChange = (
    e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>,
  ) => {
    const { name, value } = e.target;
    setFormData((prev) => ({ ...prev, [name]: value }));

    // 清除对应字段的错误
    if (errors[name]) {
      setErrors((prev) => ({ ...prev, [name]: '' }));
    }
  };

  // 表单验证
  const validateForm = (): boolean => {
    const newErrors: FormErrors = {};

    if (formData.website && !/^https?:\/\/.+/.test(formData.website)) {
      newErrors.website = '请输入有效的网站地址（以http://或https://开头）';
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  // 重置表单
  const resetForm = () => {
    setFormData(initialTenantFormData);
    setErrors({});
    setTenantPid('');
  };

  // 设置表单数据
  const setTenantFormData = (tenant: TenantInfo) => {
    setTenantPid(tenant.pid);
    setFormData({
      name: tenant.name || '',
      displayName: tenant.displayName || '',
      logo: tenant.logo || '',
      industry: getIndustryValue(tenant.industry || ''),
      contactEmail: tenant.contactEmail || '',
      contactPhone: tenant.contactPhone || '',
      website: tenant.website || '',
      description: tenant.description || '',
    });
  };

  return {
    formData,
    setFormData,
    loading,
    setLoading,
    submitting,
    setSubmitting,
    errors,
    setErrors,
    tenantPid,
    setTenantPid,
    handleInputChange,
    validateForm,
    resetForm,
    setTenantFormData,
  };
}
