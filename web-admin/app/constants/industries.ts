export interface IndustryOption {
  value: string;
  label: string;
  description?: string;
}

// 行业选项配置
export const INDUSTRIES: IndustryOption[] = [
  {
    value: 'food_beverage',
    label: '餐饮',
    description: '餐厅、咖啡厅、酒吧等餐饮服务业',
  },
  {
    value: 'technology',
    label: '科技',
    description: '软件开发、IT服务、互联网等科技行业',
  },
  {
    value: 'finance',
    label: '金融',
    description: '银行、保险、投资等金融服务业',
  },
  {
    value: 'healthcare',
    label: '医疗',
    description: '医院、诊所、医疗器械等医疗健康行业',
  },
  {
    value: 'education',
    label: '教育',
    description: '学校、培训机构、在线教育等教育行业',
  },
  {
    value: 'retail',
    label: '零售',
    description: '商店、超市、电商等零售行业',
  },
  {
    value: 'manufacturing',
    label: '制造业',
    description: '工厂、生产加工等制造业',
  },
  {
    value: 'service',
    label: '服务业',
    description: '咨询、维修、清洁等各类服务业',
  },
  {
    value: 'other',
    label: '其他',
    description: '其他未分类的行业',
  },
];

// 根据值获取标签
export const getIndustryLabel = (value: string): string => {
  const industry = INDUSTRIES.find((item) => item.value === value);
  return industry ? industry.label : value;
};

// 根据标签获取值
export const getIndustryValue = (label: string): string => {
  const industry = INDUSTRIES.find((item) => item.label === label);
  return industry ? industry.value : label;
};

// 行业映射对象（用于快速查找）
export const INDUSTRY_MAP = INDUSTRIES.reduce(
  (acc, industry) => {
    acc[industry.value] = industry.label;
    return acc;
  },
  {} as Record<string, string>,
);

// 反向映射对象（标签到值）
export const INDUSTRY_REVERSE_MAP = INDUSTRIES.reduce(
  (acc, industry) => {
    acc[industry.label] = industry.value;
    return acc;
  },
  {} as Record<string, string>,
);
