import React from 'react';

interface DisplayProps {
  name?: string;
  value?: any;
  label?: string;
  format?: string;
  valueMap?: Record<string, any>;
  statusColor?: Record<string, string>;
  className?: string;
  appearance?: 'boxed' | 'plain';
  size?: 'small' | 'medium' | 'large' | 'xlarge';
  weight?: 'normal' | 'bold' | 'light';
  align?: 'left' | 'center' | 'right';
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
  appearance = 'boxed',
  size,
  weight,
  align,
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
        return 'text-status-green bg-status-green-bg border-status-green';
      case 'error':
        return 'text-status-red bg-status-red-bg border-status-red';
      case 'warning':
        return 'text-status-amber bg-status-amber-bg border-status-amber';
      case 'processing':
        return 'text-accent bg-accent-weak border-accent';
      default:
        return 'text-text-2 bg-subtle border-border';
    }
  };

  const displayValue = formatValue(value);
  const statusClass = getStatusColorClass(value);

  const valueSize = { small: 'text-xs leading-5', medium: 'text-sm leading-5', large: 'text-xl leading-7', xlarge: 'text-2xl leading-8' }[size || 'medium'];
  const valueWeight = { normal: 'font-normal', bold: 'font-semibold', light: 'font-light' }[weight || 'normal'];
  const textAlign = { left: 'text-left', center: 'text-center', right: 'text-right' }[align || 'left'];

  return (
    <div className={`min-w-0 space-y-1 ${align ? textAlign : ''} ${className}`}>
      {label && <label className={`text-text-2 block font-medium ${size ? 'text-xs' : 'text-sm'}`}>{label}</label>}
      <div
        className={appearance === 'plain'
          ? `text-text break-words whitespace-pre-wrap ${valueSize} ${weight ? valueWeight : ''}`
          : `rounded-control border px-3 py-2 ${valueSize} ${weight ? valueWeight : ''} ${statusClass || 'border-border bg-subtle text-text'}`}
      >
        {displayValue}
      </div>
    </div>
  );
};

export { Display };
export default Display;
