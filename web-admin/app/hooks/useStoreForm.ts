import { useState, useEffect } from 'react';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';

// 共享的类型定义
export interface StoreAddress {
  provinceCode?: string;
  provinceName?: string;
  cityCode?: string;
  cityName?: string;
  districtCode?: string;
  districtName?: string;
  streetCode?: string;
  streetName?: string;
  detailAddress?: string;
  postalCode?: string;
}

export interface Store {
  id?: string;
  name: string;
  code: string;
  type: string;
  status: string;
  contactPhone?: string;
  contactEmail?: string;
  openDate?: string;
  closeDate?: string;
  description?: string;
  businessHours?: string;
  address?: StoreAddress;
}

export interface StoreFormData {
  name: string;
  code: string;
  type: string;
  status: string;
  contactPhone: string;
  contactEmail: string;
  openDate: string;
  closeDate: string;
  description: string;
  businessHours: string;
  provinceCode: string;
  provinceName: string;
  cityCode: string;
  cityName: string;
  districtCode: string;
  districtName: string;
  streetCode: string;
  streetName: string;
  detailAddress: string;
  postalCode: string;
  extension?: Record<string, any>;
}

export interface Province {
  code: string;
  name: string;
}

export const initialFormData: StoreFormData = {
  name: '',
  code: '',
  type: 'branch',
  status: 'active',
  contactPhone: '',
  contactEmail: '',
  openDate: '',
  closeDate: '',
  description: '',
  businessHours: '',
  provinceCode: '',
  provinceName: '',
  cityCode: '',
  cityName: '',
  districtCode: '',
  districtName: '',
  streetCode: '',
  streetName: '',
  detailAddress: '',
  postalCode: '',
  extension: {},
};

export const storeTypes = [
  { value: 'flagship', label: '旗舰店' },
  { value: 'branch', label: '分店' },
  { value: 'franchise', label: '加盟店' },
];

export const storeStatuses = [
  { value: 'active', label: '营业中' },
  { value: 'inactive', label: '暂停营业' },
  { value: 'maintenance', label: '维护中' },
  { value: 'closed', label: '已关闭' },
];

// 共享的业务逻辑 hook
export function useStoreForm(storeId?: string) {
  const [formData, setFormData] = useState<StoreFormData>(initialFormData);
  const [provinces, setProvinces] = useState<Province[]>([]);
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [provincesLoading, setProvincesLoading] = useState(true);
  const [toast, setToast] = useState<{
    show: boolean;
    message: string;
    type: 'success' | 'error' | 'warning';
  }>({ show: false, message: '', type: 'error' });

  // 获取省份数据
  const fetchProvinces = async () => {
    try {
      const result = await fetchResult('/api/stores/address/provinces', {
        method: 'get',
      });
      if (ResultHelper.isSuccess(result) && Array.isArray(result.data)) {
        setProvinces(result.data);
      } else {
        setProvinces([]);
      }
    } catch (error) {
      console.error('获取省份数据失败:', error);
      setProvinces([]);
    } finally {
      setProvincesLoading(false);
    }
  };

  // 加载门店数据（编辑模式）
  const fetchStoreData = async (id: string) => {
    setLoading(true);
    try {
      const result = await fetchResult(`/api/stores/${id}`, {
        method: 'get',
      });

      if (ResultHelper.isSuccess(result) && result.data) {
        const store = result.data as Store;
        setFormData({
          name: store.name || '',
          code: store.code || '',
          type: store.type || 'branch',
          status: store.status || 'active',
          contactPhone: store.contactPhone || '',
          contactEmail: store.contactEmail || '',
          openDate: store.openDate || '',
          closeDate: store.closeDate || '',
          description: store.description || '',
          businessHours: store.businessHours || '',
          provinceCode: store.address?.provinceCode || '',
          provinceName: store.address?.provinceName || '',
          cityCode: store.address?.cityCode || '',
          cityName: store.address?.cityName || '',
          districtCode: store.address?.districtCode || '',
          districtName: store.address?.districtName || '',
          streetCode: store.address?.streetCode || '',
          streetName: store.address?.streetName || '',
          detailAddress: store.address?.detailAddress || '',
          postalCode: store.address?.postalCode || '',
        });
      } else {
        console.error('加载门店数据失败:', result);
        showToast(`加载门店数据失败：${result.code || '未知错误'}`, 'error');
      }
    } catch (error) {
      console.error('加载门店数据失败:', error);
      showToast('加载门店数据失败，请检查网络连接后重试', 'error');
    } finally {
      setLoading(false);
    }
  };

  // 检查门店编码可用性
  const checkCodeAvailability = async () => {
    if (!formData.code.trim()) return;

    try {
      const result = await fetchResult('/api/stores/validate-code', {
        method: 'get',
        params: { code: formData.code, excludeId: storeId },
      });

      if (ResultHelper.isSuccess(result)) {
        if (!result.data) {
          setErrors((prev) => ({ ...prev, code: '门店编码已存在' }));
        } else {
          setErrors((prev) => ({ ...prev, code: '' }));
        }
      }
    } catch (error) {
      console.error('检查门店编码失败:', error);
    }
  };

  // 显示Toast的辅助函数
  const showToast = (message: string, type: 'success' | 'error' | 'warning' = 'error') => {
    setToast({ show: true, message, type });
  };

  const hideToast = () => {
    setToast((prev) => ({ ...prev, show: false }));
  };

  // 处理输入变化
  const handleInputChange = (field: keyof StoreFormData, value: any) => {
    setFormData((prev) => ({ ...prev, [field]: value }));
    if (errors[field]) {
      setErrors((prev) => ({ ...prev, [field]: '' }));
    }
  };

  // 处理扩展字段变化
  const handleExtensionChange = (field: string, value: string) => {
    setFormData((prev) => ({
      ...prev,
      extension: {
        ...prev.extension,
        [field]: value,
      },
    }));
  };

  // 处理地址变化
  const handleAddressChange = (address: any) => {
    setFormData((prev) => ({
      ...prev,
      provinceCode: address.provinceCode || '',
      provinceName: address.provinceName || '',
      cityCode: address.cityCode || '',
      cityName: address.cityName || '',
      districtCode: address.districtCode || '',
      districtName: address.districtName || '',
      streetCode: address.streetCode || '',
      streetName: address.streetName || '',
    }));

    setErrors((prev) => ({
      ...prev,
      provinceCode: '',
      cityCode: '',
      districtCode: '',
    }));
  };

  // 初始化数据
  useEffect(() => {
    fetchProvinces();
    if (storeId) {
      fetchStoreData(storeId);
    }
  }, [storeId]);

  return {
    formData,
    setFormData,
    provinces,
    loading,
    submitting,
    setSubmitting,
    errors,
    setErrors,
    provincesLoading,
    toast,
    showToast,
    hideToast,
    checkCodeAvailability,
    handleInputChange,
    handleExtensionChange,
    handleAddressChange,
  };
}
