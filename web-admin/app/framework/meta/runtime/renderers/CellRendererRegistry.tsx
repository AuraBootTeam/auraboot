/**
 * CellRendererRegistry - 单元格渲染器注册中心
 *
 * 提供可扩展的单元格渲染器注册和执行机制，替代硬编码的 switch-case
 *
 * 设计原则：
 * 1. 开闭原则 - 新增渲染器只需注册，无需修改现有代码
 * 2. 单一职责 - 每个渲染器只负责一种 valueType 的渲染
 * 3. 依赖注入 - 通过 CellRendererContext 注入所有依赖
 *
 * 与 ActionRegistry 的关系：
 * - ActionRegistry 处理用户交互（按钮点击、事件）
 * - CellRendererRegistry 处理数据展示（表格单元格渲染）
 * - 两者共同组成完整的 DSL 运行时
 */

import React from 'react';
import { resolveStatusTone, StatusDot } from '~/framework/meta/runtime/renderers/statusTone';
import { OwnerCell } from '~/framework/meta/runtime/renderers/OwnerCell';
import { getLocalizedText } from '~/framework/meta/runtime/expression/i18n-renderer';
import type { ExpressionContext } from '~/framework/meta/runtime/expression/context';
import { formatInTimezone } from '~/shared/services/dateTimeFormatService';

/**
 * 单元格渲染上下文
 * 包含渲染所需的所有数据和依赖
 */
export interface CellRendererContext {
  // 单元格值
  value: any;

  // 当前行数据
  record: any;

  // 列定义
  column: {
    field: string;
    label?: string;
    valueType?: string;
    render?: any; // 自定义渲染配置
    [key: string]: any;
  };

  // 表达式上下文（包含全局状态、函数等）
  expressionContext?: ExpressionContext;

  // 国际化
  locale?: string;
  t?: (key: string) => string;

  // 行索引
  rowIndex?: number;
}

/**
 * 单元格渲染器类型
 * 返回 ReactNode 用于渲染
 */
export type CellRenderer = (context: CellRendererContext) => React.ReactNode;

/**
 * 单元格渲染器注册表
 */
class CellRendererRegistry {
  private renderers = new Map<string, CellRenderer>();

  /**
   * 注册渲染器
   * @param valueType 值类型（如 'text', 'tag', 'date', 'image'）
   * @param renderer 渲染器函数
   */
  register(valueType: string, renderer: CellRenderer): void {
    if (this.renderers.has(valueType)) {
      console.warn(`[CellRendererRegistry] Overwriting existing renderer: ${valueType}`);
    }
    this.renderers.set(valueType, renderer);
  }

  /**
   * 批量注册渲染器
   */
  registerBatch(renderers: Record<string, CellRenderer>): void {
    Object.entries(renderers).forEach(([valueType, renderer]) => {
      this.register(valueType, renderer);
    });
  }

  /**
   * 渲染单元格
   * @param valueType 值类型
   * @param context 渲染上下文
   */
  render(valueType: string | undefined, context: CellRendererContext): React.ReactNode {
    // 默认使用 'text' 渲染器
    const type = valueType || 'text';
    const renderer = this.renderers.get(type);

    if (!renderer) {
      console.warn(`[CellRendererRegistry] Renderer not found: ${type}, using default`);
      const defaultRenderer = this.renderers.get('default');
      return defaultRenderer ? defaultRenderer(context) : String(context.value ?? '');
    }

    try {
      return renderer(context);
    } catch (error) {
      console.error(`[CellRendererRegistry] Error rendering ${type}:`, error);
      return <span className="text-status-red">渲染错误</span>;
    }
  }

  /**
   * 检查渲染器是否已注册
   */
  has(valueType: string): boolean {
    return this.renderers.has(valueType);
  }

  /**
   * 获取所有已注册的渲染器类型
   */
  getRegisteredTypes(): string[] {
    return Array.from(this.renderers.keys());
  }

  /**
   * 取消注册渲染器
   */
  unregister(valueType: string): void {
    this.renderers.delete(valueType);
  }

  /**
   * 清空所有渲染器
   */
  clear(): void {
    this.renderers.clear();
  }
}

// 导出单例
export const cellRendererRegistry = new CellRendererRegistry();

const DEFAULT_TEMPORAL_FORMATS = {
  date: 'YYYY-MM-DD',
  datetime: 'YYYY-MM-DD HH:mm:ss',
  time: 'HH:mm:ss',
} as const;

function resolveTemporalFormat(
  type: 'date' | 'datetime' | 'time',
  column: Record<string, any> | undefined,
): string {
  const explicitFormat = typeof column?.format === 'string' ? column.format : null;
  if (explicitFormat && /Y{2,4}|M{1,4}|D{1,4}|H{1,2}|m{1,2}|s{1,2}/.test(explicitFormat)) {
    return explicitFormat;
  }
  const preferred = column?.dateTimeFormats?.[type];
  if (typeof preferred === 'string' && preferred.trim()) {
    return preferred.trim();
  }
  return DEFAULT_TEMPORAL_FORMATS[type];
}

function formatTemporalValue(
  value: any,
  type: 'date' | 'datetime' | 'time',
  locale?: string,
  column?: Record<string, any>,
): string {
  const format = resolveTemporalFormat(type, column);
  // Backend emits UTC; convert to the effective display timezone carried on the
  // column (injected by the page renderer from TimezoneContext). Falls back to
  // UTC when no timezone is provided.
  const timeZone = typeof column?.timezone === 'string' ? column.timezone : undefined;
  return formatInTimezone(value, format, timeZone);
}

// ============================================
// 注册内置渲染器
// ============================================

// Shared auto-detection for URL/email in text values
function renderSmartText(value: any): React.ReactNode {
  const str = String(value ?? '');
  if (!str) return <span>{str}</span>;

  // URL pattern
  if (/^https?:\/\/.+/i.test(str)) {
    return (
      <a
        href={str}
        target="_blank"
        rel="noopener noreferrer"
        className="text-accent hover:text-accent-hover hover:underline dark:text-blue-400"
        onClick={(e) => e.stopPropagation()}
      >
        {str}
      </a>
    );
  }

  // Email pattern
  if (/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(str)) {
    return (
      <a
        href={`mailto:${str}`}
        className="text-accent hover:text-accent-hover hover:underline dark:text-blue-400"
        onClick={(e) => e.stopPropagation()}
      >
        {str}
      </a>
    );
  }

  return <span>{str}</span>;
}

/**
 * 默认渲染器 - 带 URL/email 自动检测
 */
cellRendererRegistry.register('default', ({ value }) => {
  return renderSmartText(value);
});

/**
 * 文本渲染器 - 带 URL/email 自动检测
 */
cellRendererRegistry.register('text', ({ value }) => {
  return renderSmartText(value);
});

/**
 * Reference/Lookup renderer - shows display name from _display suffix (GAP-124)
 */
cellRendererRegistry.register('reference', ({ value, record, column }) => {
  if (!value) return <span className="text-text-3">-</span>;
  // Try _display suffix for resolved display name
  const displayKey = column?.field ? `${column.field}_display` : null;
  const displayValue = displayKey && record?.[displayKey];
  if (displayValue) {
    return (
      <span className="text-accent" title={String(value)}>
        {String(displayValue)}
      </span>
    );
  }
  return <span>{String(value)}</span>;
});

/**
 * 标签渲染器 - 色点 + 文字 (semantic dot + label; see statusTone).
 */

cellRendererRegistry.register('tag', ({ value, column, locale, t }) => {
  if (!value) return null;

  // Support tagMap format: { "value": { "label": "Display", "color": "green" } }
  const tagMap = (column as any).tagMap;
  const tagConfig = column.render?.tagConfig || {};
  const colorMap = tagConfig.colorMap || {};

  const resolveTag = (v: any): { label: string; color: string } => {
    if (tagMap) {
      const key = String(v);
      const entry = tagMap[key] ?? tagMap[key.toUpperCase()] ?? tagMap[key.toLowerCase()];
      if (entry) {
        return {
          label:
            typeof entry.label === 'string'
              ? entry.label
              : getLocalizedText(entry.label, locale, t),
          color: entry.color || 'gray',
        };
      }
    }
    return { label: String(v), color: colorMap[v] || 'gray' };
  };

  // §3 / §1.3: tags render as 色点 + 文字 (semantic dot + label), not pills.
  if (Array.isArray(value)) {
    return (
      <div className="flex flex-wrap gap-x-3 gap-y-1">
        {value.map((v, idx) => {
          const { label, color } = resolveTag(v);
          return <StatusDot key={idx} tone={resolveStatusTone(color)} label={label} />;
        })}
      </div>
    );
  }

  const { label, color } = resolveTag(value);
  return <StatusDot tone={resolveStatusTone(color)} label={label} />;
});

/**
 * 归属渲染器 - polymorphic owner (owner_type + owner_id) → 👤 user / 👥 team + name.
 * Reads the sibling owner_type column from the row; resolves the pid to a name.
 */
cellRendererRegistry.register('owner', ({ value, record, column }) => {
  const typeField = (column.render as any)?.ownerTypeField || 'owner_type';
  return <OwnerCell ownerType={record?.[typeField]} ownerId={value} />;
});

/**
 * 日期渲染器
 */
cellRendererRegistry.register('date', ({ value, locale, column }) => {
  if (!value) return null;

  try {
    return <span>{formatTemporalValue(value, 'date', locale, column)}</span>;
  } catch (error) {
    return <span>{String(value)}</span>;
  }
});

/**
 * 日期时间渲染器
 */
cellRendererRegistry.register('datetime', ({ value, locale, column }) => {
  if (!value) return null;

  try {
    return <span>{formatTemporalValue(value, 'datetime', locale, column)}</span>;
  } catch (error) {
    return <span>{String(value)}</span>;
  }
});

/**
 * 时间渲染器
 */
cellRendererRegistry.register('time', ({ value, locale, column }) => {
  if (!value) return null;

  try {
    return <span>{formatTemporalValue(value, 'time', locale, column)}</span>;
  } catch (error) {
    return <span>{String(value)}</span>;
  }
});

/**
 * 布尔值渲染器
 */
cellRendererRegistry.register('boolean', ({ value, t }) => {
  // Normalize: handle native boolean, string "true"/"false", and numeric 1/0
  const boolVal =
    typeof value === 'boolean'
      ? value
      : typeof value === 'string'
        ? value.toLowerCase() === 'true'
        : Boolean(value);
  const displayValue = boolVal ? t?.('common.yes') || 'Yes' : t?.('common.no') || 'No';
  const colorClass = boolVal
    ? 'text-status-green dark:text-green-400'
    : 'text-text-2 dark:text-gray-400';

  return <span className={colorClass}>{displayValue}</span>;
});

/**
 * 状态渲染器 - 带图标和颜色的状态
 */
cellRendererRegistry.register('status', ({ value, column }) => {
  if (!value) return null;

  const statusConfig = column.render?.statusConfig || {};
  const config = statusConfig[value] || { color: 'gray', label: value };

  // §3 / §1.3: status renders as 色点 + 文字 (semantic dot + label), not a pill.
  return (
    <StatusDot
      tone={resolveStatusTone(config.color)}
      label={
        <>
          {config.icon && <span className="mr-1">{config.icon}</span>}
          {config.label || String(value)}
        </>
      }
    />
  );
});

/**
 * 进度条渲染器
 */
const PROGRESS_TONE_BAR: Record<string, string> = {
  gray: 'bg-status-gray',
  blue: 'bg-status-blue',
  amber: 'bg-status-amber',
  green: 'bg-status-green',
  red: 'bg-status-red',
};

cellRendererRegistry.register('progress', ({ value, column }) => {
  const numValue = typeof value === 'number' ? value : parseFloat(value) || 0;
  const percentage = Math.min(100, Math.max(0, numValue));

  const showText = column.render?.showProgressText !== false;

  // Optional threshold-tone coloring (e.g. lead score cold/warm/hot). Thresholds
  // are ordered ascending by `max`; the first band whose `max` exceeds the value
  // wins, and a band without `max` is the default/top band. Tones resolve to the
  // canonical bg-status-* tokens (semantic, and never purged by Tailwind JIT —
  // unlike the legacy dynamic `bg-<color>-600`). No thresholds → legacy behavior.
  const thresholds = column.render?.thresholds as Array<{ max?: number; tone: string }> | undefined;
  let barClass = `bg-${column.render?.progressColor || 'blue'}-600`;
  if (Array.isArray(thresholds) && thresholds.length > 0) {
    const match =
      thresholds.find((t) => typeof t.max === 'number' && percentage < t.max) ||
      thresholds.find((t) => t.max == null) ||
      thresholds[thresholds.length - 1];
    barClass = PROGRESS_TONE_BAR[match?.tone] || PROGRESS_TONE_BAR.blue;
  }

  return (
    <div className="flex items-center gap-2">
      <div className="bg-border h-2 flex-1 rounded-full dark:bg-gray-700">
        <div
          className={`${barClass} h-2 rounded-full transition-all`}
          style={{ width: `${percentage}%` }}
        ></div>
      </div>
      {showText && <span className="text-text-2 text-sm dark:text-gray-400">{percentage}%</span>}
    </div>
  );
});

/**
 * 图片渲染器
 */
cellRendererRegistry.register('image', ({ value, column }) => {
  if (!value) return null;

  const size = column.render?.imageSize || 32;
  const rounded = column.render?.rounded !== false;

  // 支持数组图片
  if (Array.isArray(value)) {
    return (
      <div className="flex gap-1">
        {value.slice(0, 3).map((url, idx) => (
          <img
            key={idx}
            src={url}
            alt=""
            className={`object-cover ${rounded ? 'rounded' : ''}`}
            style={{ width: size, height: size }}
          />
        ))}
        {value.length > 3 && <span className="text-text-2 text-xs">+{value.length - 3}</span>}
      </div>
    );
  }

  return (
    <img
      src={value}
      alt=""
      className={`object-cover ${rounded ? 'rounded' : ''}`}
      style={{ width: size, height: size }}
    />
  );
});

/**
 * 头像渲染器
 */
cellRendererRegistry.register('avatar', ({ value, record, column }) => {
  const name = record[column.render?.nameField || 'name'] || '';
  const size = column.render?.size || 32;

  if (!value) {
    // 显示首字母
    const initial = name ? name.charAt(0).toUpperCase() : '?';
    return (
      <div
        className="bg-border-strong text-text-2 flex items-center justify-center rounded-full font-medium"
        style={{ width: size, height: size }}
      >
        {initial}
      </div>
    );
  }

  return (
    <img
      src={value}
      alt={name}
      className="rounded-full object-cover"
      style={{ width: size, height: size }}
    />
  );
});

/**
 * User identity renderer (avatar + name + secondary ID/username)
 */
cellRendererRegistry.register('user_identity', ({ value, record, column }) => {
  const renderConfig = column.render || {};
  const nameField = renderConfig.nameField || 'user_name';
  const usernameField = renderConfig.usernameField || 'user_username';
  const avatarField = renderConfig.avatarField || 'user_avatar_url';
  const idField = renderConfig.idField || column.field;
  const size = renderConfig.size || 32;

  // Fallback: try _display suffix from generic reference enrichment
  const displaySuffix = column.field ? `${column.field}_display` : null;
  const displayName =
    record?.[nameField] ||
    record?.[usernameField] ||
    (displaySuffix && record?.[displaySuffix]) ||
    value ||
    '-';
  const username = record?.[usernameField];
  const userId = record?.[idField] ?? value;
  const avatarUrl = record?.[avatarField];
  const subtitle = username
    ? `@${String(username)}`
    : displayName !== value
      ? ''
      : `ID: ${String(userId)}`;
  const initial = String(displayName || '?')
    .charAt(0)
    .toUpperCase();

  return (
    <div className="flex min-w-0 items-center gap-2">
      {avatarUrl ? (
        <img
          src={String(avatarUrl)}
          alt={String(displayName)}
          className="flex-shrink-0 rounded-full object-cover"
          style={{ width: size, height: size }}
        />
      ) : (
        <div
          className="bg-border-strong text-text-2 flex flex-shrink-0 items-center justify-center rounded-full font-medium"
          style={{ width: size, height: size }}
        >
          {initial || '?'}
        </div>
      )}
      <div className="min-w-0">
        <div className="truncate">{String(displayName)}</div>
        <div className="text-text-2 truncate text-xs">{subtitle}</div>
      </div>
    </div>
  );
});

/**
 * 链接渲染器
 */
cellRendererRegistry.register('link', ({ value, column }) => {
  if (!value) return null;

  const href = column.render?.hrefTemplate
    ? column.render.hrefTemplate.replace('{value}', value)
    : value;

  const target = column.render?.target || '_blank';
  const text = column.render?.text || value;

  return (
    <a
      href={href}
      target={target}
      rel={target === '_blank' ? 'noopener noreferrer' : undefined}
      className="text-accent hover:text-accent-hover underline dark:text-blue-400 dark:hover:text-blue-300"
    >
      {text}
    </a>
  );
});

/**
 * URL 渲染器 - 显式 URL 链接
 */
cellRendererRegistry.register('url', ({ value, column }) => {
  if (!value) return null;
  const target = column.render?.target || '_blank';
  return (
    <a
      href={String(value)}
      target={target}
      rel={target === '_blank' ? 'noopener noreferrer' : undefined}
      className="text-accent hover:text-accent-hover hover:underline dark:text-blue-400"
      onClick={(e) => e.stopPropagation()}
    >
      {String(value)}
    </a>
  );
});

/**
 * Email 渲染器 - mailto 链接
 */
cellRendererRegistry.register('email', ({ value }) => {
  if (!value) return null;
  return (
    <a
      href={`mailto:${String(value)}`}
      className="text-accent hover:text-accent-hover hover:underline dark:text-blue-400"
      onClick={(e) => e.stopPropagation()}
    >
      {String(value)}
    </a>
  );
});

/**
 * Color 渲染器 - 色块 + hex 值
 */
cellRendererRegistry.register('color', ({ value }) => {
  if (!value) return null;
  return (
    <div className="flex items-center gap-1.5">
      <div
        className="border-border-strong h-4 w-4 rounded border dark:border-gray-600"
        style={{ backgroundColor: String(value) }}
      />
      <span className="text-text-2 text-xs dark:text-gray-400">{String(value)}</span>
    </div>
  );
});

/**
 * 数字渲染器 - 带千分位和小数位
 */
cellRendererRegistry.register('number', ({ value, column }) => {
  if (value === null || value === undefined) return null;

  const numValue = typeof value === 'number' ? value : parseFloat(value);
  if (isNaN(numValue)) return <span>{String(value)}</span>;

  const precision = column.render?.precision;
  const thousandsSeparator = column.render?.thousandsSeparator !== false;

  let formatted = precision !== undefined ? numValue.toFixed(precision) : String(numValue);

  if (thousandsSeparator) {
    const parts = formatted.split('.');
    parts[0] = parts[0].replace(/\B(?=(\d{3})+(?!\d))/g, ',');
    formatted = parts.join('.');
  }

  return <span className="font-mono">{formatted}</span>;
});

/**
 * 货币渲染器
 */
cellRendererRegistry.register('currency', ({ value, column, locale }) => {
  if (value === null || value === undefined) return null;

  const numValue = typeof value === 'number' ? value : parseFloat(value);
  if (isNaN(numValue)) return <span>{String(value)}</span>;

  const currencyCode = column.currencyCode || column.render?.currencyCode || 'cny';
  const formatted = new Intl.NumberFormat(locale || 'en', {
    style: 'currency',
    currency: currencyCode,
    minimumFractionDigits: column.render?.precision ?? 2,
    maximumFractionDigits: column.render?.precision ?? 2,
  }).format(numValue);

  return <span className="font-mono">{formatted}</span>;
});

/**
 * JSON 渲染器 - 格式化显示 JSON
 */
cellRendererRegistry.register('json', ({ value }) => {
  if (!value) return null;

  try {
    const jsonStr = typeof value === 'string' ? value : JSON.stringify(value, null, 2);
    return (
      <pre className="bg-hover max-w-xs overflow-auto rounded p-2 text-xs dark:bg-gray-800">
        {jsonStr}
      </pre>
    );
  } catch (error) {
    return <span className="text-status-red">Invalid JSON</span>;
  }
});

/**
 * Button field renderer (GAP-131) — renders a clickable button in the cell.
 * The button dispatches a 'cell-button-click' CustomEvent with command code and record.
 */
cellRendererRegistry.register('button', ({ value, record, column }) => {
  const label = column?.buttonLabel || column?.label || value || 'Action';
  const commandCode = column?.buttonCommandCode || column?.commandCode || '';
  const handleClick = (e: React.MouseEvent) => {
    e.stopPropagation();
    window.dispatchEvent(
      new CustomEvent('cell-button-click', {
        detail: { commandCode, record, field: column?.field },
      }),
    );
  };
  return (
    <button
      type="button"
      onClick={handleClick}
      className="bg-accent-weak text-accent hover:bg-accent-weak rounded-md px-2.5 py-1 text-xs font-medium transition-colors"
      data-testid={`cell-button-${column?.field}`}
    >
      {String(label)}
    </button>
  );
});

/**
 * 自定义渲染器 - 执行自定义渲染函数
 */
cellRendererRegistry.register('custom', ({ value, record, column, expressionContext }) => {
  try {
    // 如果有自定义渲染函数
    if (typeof column.render?.customRender === 'function') {
      return column.render.customRender(value, record, expressionContext);
    }

    // 如果有渲染表达式
    if (column.render?.expression) {
      // TODO: 使用 ExpressionEvaluator 解析表达式
      return <span>{String(value)}</span>;
    }

    return <span>{String(value ?? '')}</span>;
  } catch (error) {
    console.error('[CellRendererRegistry] Custom render error:', error);
    return <span className="text-status-red">渲染错误</span>;
  }
});
