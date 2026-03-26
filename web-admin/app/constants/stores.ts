export interface StoreOption {
  value: string;
  label: string;
}

export interface StoreStatusConfig {
  label: string;
  className: string;
}

// 门店类型选项
export const STORE_TYPES: StoreOption[] = [
  { value: '', label: '全部类型' },
  { value: 'flagship', label: '旗舰店' },
  { value: 'branch', label: '分店' },
  { value: 'franchise', label: '加盟店' },
];

// 门店状态选项
export const STORE_STATUSES: StoreOption[] = [
  { value: '', label: '全部状态' },
  { value: 'active', label: '营业中' },
  { value: 'inactive', label: '暂停营业' },
  { value: 'maintenance', label: '维护中' },
  { value: 'closed', label: '已关闭' },
];

// 门店状态配置（用于显示徽章）
export const STORE_STATUS_CONFIG: Record<string, StoreStatusConfig> = {
  active: { label: '营业中', className: 'bg-green-100 text-green-800' },
  inactive: { label: '暂停营业', className: 'bg-yellow-100 text-yellow-800' },
  MAINTENANCE: { label: '维护中', className: 'bg-blue-100 text-blue-800' },
  closed: { label: '已关闭', className: 'bg-red-100 text-red-800' },
};

// 门店类型配置
export const STORE_TYPE_CONFIG: Record<string, string> = {
  FLAGSHIP: '旗舰店',
  BRANCH: '分店',
  FRANCHISE: '加盟店',
};

// 工具函数：根据状态值获取标签
export const getStoreStatusLabel = (status: string): string => {
  return STORE_STATUS_CONFIG[status]?.label || status;
};

// 工具函数：根据类型值获取标签
export const getStoreTypeLabel = (type: string): string => {
  return STORE_TYPE_CONFIG[type] || type;
};

// 工具函数：根据状态值获取配置
export const getStoreStatusConfig = (status: string): StoreStatusConfig => {
  return STORE_STATUS_CONFIG[status] || { label: status, className: 'bg-gray-100 text-gray-800' };
};

// 门店类型映射对象（用于快速查找）
export const STORE_TYPE_MAP = STORE_TYPES.reduce(
  (acc, type) => {
    if (type.value) acc[type.value] = type.label;
    return acc;
  },
  {} as Record<string, string>,
);

// 门店状态映射对象（用于快速查找）
export const STORE_STATUS_MAP = STORE_STATUSES.reduce(
  (acc, status) => {
    if (status.value) acc[status.value] = status.label;
    return acc;
  },
  {} as Record<string, string>,
);
