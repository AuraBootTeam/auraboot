import React from 'react';

interface DisplayProps {
  name?: string;
  value?: any;
  label?: string;
  format?: string;
  valueMap?: Record<string, any>;
  statusColor?: Record<string, string>;
  className?: string;
  readOnly?: boolean;
  disabled?: boolean;
  [key: string]: any;
}

const Display: React.FC<DisplayProps> = ({
  value,
  label,
  format,
  valueMap,
  statusColor,
  className = '',
}) => {
  // 格式化显示值
  const formatValue = (val: any): string => {
    if (val === null || val === undefined) {
      return '-';
    }

    // 如果有值映射，使用映射值
    if (valueMap && valueMap[val]) {
      const mappedValue = valueMap[val];
      if (typeof mappedValue === 'object') {
        return mappedValue['zh-CN'] || mappedValue['en-US'] || val;
      }
      return mappedValue;
    }

    // 如果有格式化规则
    if (format && typeof val === 'string') {
      // 处理日期时间格式化
      if (format.includes('yyyy') || format.includes('MM') || format.includes('DD')) {
        try {
          const date = new Date(val);
          if (!isNaN(date.getTime())) {
            return date.toLocaleString('zh-CN', {
              year: 'numeric',
              month: '2-digit',
              day: '2-digit',
              hour: '2-digit',
              minute: '2-digit',
              second: '2-digit',
            });
          }
        } catch (error) {
          console.warn('Date formatting error:', error);
        }
      }
    }

    return String(val);
  };

  // 获取状态颜色样式
  const getStatusColorClass = (val: any): string => {
    if (!statusColor || !val) return '';

    const colorType = statusColor[val];
    switch (colorType) {
      case 'success':
        return 'text-green-600 bg-green-50 border-green-200';
      case 'error':
        return 'text-red-600 bg-red-50 border-red-200';
      case 'warning':
        return 'text-yellow-600 bg-yellow-50 border-yellow-200';
      case 'processing':
        return 'text-blue-600 bg-blue-50 border-blue-200';
      default:
        return 'text-gray-600 bg-gray-50 border-gray-200';
    }
  };

  const displayValue = formatValue(value);
  const statusClass = getStatusColorClass(value);

  return (
    <div className={`space-y-1 ${className}`}>
      {label && <label className="block text-sm font-medium text-gray-700">{label}</label>}
      <div
        className={`rounded-md border px-3 py-2 text-sm ${
          statusClass || 'border-gray-200 bg-gray-50 text-gray-900'
        }`}
      >
        {displayValue}
      </div>
    </div>
  );
};

export default Display;
