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
  density?: 'default' | 'compact';
  emphasis?: 'default' | 'primary';
  labelLayout?: 'stacked' | 'inline' | 'hidden';
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
  density = 'default',
  emphasis = 'default',
  labelLayout = 'stacked',
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

  if (format === 'timeline') {
    if (!Array.isArray(value)) throw new Error('Timeline display requires structured events');
    return <section className={className}>{label && <h3 className="text-text text-sm font-medium">{label}</h3>}<ol className="mt-5 space-y-5">
      {value.map((event, index) => {
        if (!event || typeof event !== 'object' || typeof event.context !== 'string') throw new Error('Invalid timeline event');
        return <li key={index} className="grid grid-cols-[105px_minmax(0,1fr)] gap-5 text-xs"><time className="text-text-3 text-right leading-5">{String(event.time || '')}</time><div className={`border-border relative border-l pl-5 leading-5 before:absolute before:-left-1 before:top-1.5 before:h-2 before:w-2 before:rounded-full ${index === 0 ? 'text-accent font-medium before:bg-accent' : 'text-text-2 before:bg-gray-400'}`}>{event.context}</div></li>;
      })}
    </ol></section>;
  }
  const displayValue = formatValue(value);
  const statusClass = getStatusColorClass(value);

  return (
    <div className={`${labelLayout === 'inline' ? 'flex items-baseline justify-between gap-3' : 'space-y-1'} ${className}`}>
      {label && labelLayout !== 'hidden' && <label className={`text-text-3 block ${density === 'compact' ? 'text-xs font-normal' : 'text-sm font-medium'}`}>{label}</label>}
      <div
        className={appearance === 'plain'
          ? `text-text break-words whitespace-pre-wrap ${emphasis === 'primary' ? 'text-[26px] font-semibold leading-8 tracking-tight tabular-nums' : density === 'compact' ? 'text-xs leading-5' : 'text-sm leading-5'}`
          : `rounded-control border px-3 py-2 text-sm ${statusClass || 'border-border bg-subtle text-text'}`}
      >
        {displayValue}
      </div>
    </div>
  );
};

export { Display };
export default Display;
