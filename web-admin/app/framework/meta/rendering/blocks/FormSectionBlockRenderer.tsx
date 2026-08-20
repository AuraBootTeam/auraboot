/**
 * FormSectionBlockRenderer - 表单分组块渲染器
 * 用于渲染带标题的表单字段分组
 */

import React, { useMemo } from 'react';
import {
  FileText,
  KeyRound,
  LockKeyhole,
  RotateCcwKey,
  ShieldCheck,
  type LucideIcon,
} from 'lucide-react';
import type { BlockConfig, FieldConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { FieldRenderer } from '~/framework/meta/rendering/FieldRenderer';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';

export interface FormSectionBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

const SECTION_ICONS: Record<string, LucideIcon> = {
  behavior: ShieldCheck,
  password: KeyRound,
  reset: RotateCcwKey,
  lockout: LockKeyhole,
  notes: FileText,
};

function resolveSectionIcon(block: BlockConfig): LucideIcon {
  const explicitIcon = String((block as any).extension?.icon || '').toLowerCase();
  const blockId = String(block.id || '').toLowerCase();
  const key = explicitIcon || Object.keys(SECTION_ICONS).find((item) => blockId.includes(item));
  return key && SECTION_ICONS[key] ? SECTION_ICONS[key] : FileText;
}

function isPositiveDisplayValue(value: unknown): boolean {
  const text = String(value ?? '').toLowerCase();
  return text.includes('已启用') || text.includes('enabled') || text.includes('管理员托管');
}

function isNegativeDisplayValue(value: unknown): boolean {
  const text = String(value ?? '').toLowerCase();
  return text.includes('已停用') || text.includes('disabled');
}

export function resolveSettingsCardDisplayValue(
  value: unknown,
  valueMap: unknown,
  locale: string,
  t: (key: string) => string,
): string {
  if (value !== null && value !== undefined && valueMap && typeof valueMap === 'object') {
    const key = String(value);
    const mapped = (valueMap as Record<string, unknown>)[key];
    if (mapped !== undefined) {
      return getLocalizedText(mapped as any, locale, t);
    }
  }
  return value === null || value === undefined || value === '' ? '—' : String(value);
}

export function resolveSettingsCardFieldDisplayValue(
  value: unknown,
  field: Pick<FieldConfig, 'props'> & { valueMap?: unknown },
  locale: string,
  t: (key: string) => string,
): string {
  return resolveSettingsCardDisplayValue(value, field.valueMap ?? field.props?.valueMap, locale, t);
}

export const FormSectionBlockRenderer: React.FC<FormSectionBlockRendererProps> = ({
  block,
  runtime,
}) => {
  const context = runtime.getContext();
  const fields = block.fields || [];

  // 获取 locale 和 t 函数
  const locale = context.locale || 'zh-CN';
  const t = context.t || ((key: string) => key);
  const extension = (block as any).extension || {};
  const displayVariant = String(extension.displayVariant || extension.variant || '');
  const isSettingsCard = block.blockType === 'detail-section' && displayVariant === 'settings-card';

  // 计算网格布局样式 - 基于 layout.cols (通常是 12 列)
  const gridStyle = useMemo(() => {
    const cols = 12; // 默认 12 列网格
    const colGap = block.layout?.colGap || 12;
    const rowGap = block.layout?.rowGap || 12;

    return {
      display: 'grid',
      gridTemplateColumns: `repeat(${cols}, 1fr)`,
      columnGap: `${colGap}px`,
      rowGap: `${rowGap}px`,
    };
  }, [block.layout]);

  // 渲染标题
  const renderTitle = () => {
    if (!block.title) return null;
    const title = getLocalizedText(block.title, locale, t);
    if (isSettingsCard) return null;
    return (
      <div className="border-border mb-4 border-b pb-2">
        <h3 className="text-text text-lg font-medium">{title}</h3>
      </div>
    );
  };

  const renderSettingsCard = () => {
    const title = block.title ? getLocalizedText(block.title, locale, t) : '';
    const description = (block as any).description
      ? getLocalizedText((block as any).description, locale, t)
      : '';
    const Icon = resolveSectionIcon(block);
    const stateManager = runtime.getStateManager();
    const scopeId = runtime.getScopeId();
    const cardGridStyle = {
      display: 'grid',
      gridTemplateColumns: 'repeat(12, minmax(0, 1fr))',
      gap: `${block.layout?.rowGap || 12}px ${block.layout?.colGap || 12}px`,
    };

    const renderValue = (field: FieldConfig) => {
      const value = stateManager.getFieldValue(scopeId, field.field);
      const displayValue = resolveSettingsCardFieldDisplayValue(value, field, locale, t);
      const isLongText = displayValue.includes('\n') || displayValue.length > 56;
      const toneClass = isPositiveDisplayValue(displayValue)
        ? 'border-status-green bg-status-green-bg text-status-green'
        : isNegativeDisplayValue(displayValue)
          ? 'border-status-gray bg-status-gray-bg text-status-gray'
          : 'border-status-blue bg-status-blue-bg text-status-blue';

      if (isLongText) {
        return (
          <p className="text-text-2 mt-2 text-sm leading-6 whitespace-pre-line">{displayValue}</p>
        );
      }

      return (
        <span
          className={`rounded-control mt-2 inline-flex w-fit max-w-full items-center border px-2.5 py-1 text-sm font-medium ${toneClass}`}
        >
          {displayValue}
        </span>
      );
    };

    return (
      <section className="rounded-card border-border bg-panel shadow-card mb-4 overflow-hidden border">
        <div className="border-border bg-subtle flex items-start gap-3 border-b px-5 py-4">
          <div className="rounded-control bg-accent-weak text-accent flex h-9 w-9 shrink-0 items-center justify-center">
            <Icon className="h-4 w-4" aria-hidden="true" />
          </div>
          <div className="min-w-0">
            <h3 className="text-text text-base font-semibold">{title}</h3>
            {description ? <p className="text-text-3 mt-1 text-sm">{description}</p> : null}
          </div>
        </div>
        <div className="p-4 sm:p-5" style={cardGridStyle}>
          {fields.map((field) => {
            const colSpan = field.layout?.colSpan || 6;
            const label = field.label
              ? getLocalizedText(field.label as any, locale, t)
              : field.field;
            return (
              <div
                key={field.field}
                className="rounded-control border-border bg-panel min-w-0 border px-4 py-3"
                style={{ gridColumn: `span ${Math.min(12, Math.max(1, colSpan))}` }}
              >
                <div className="text-text-3 text-xs font-medium">{label}</div>
                {renderValue(field)}
              </div>
            );
          })}
        </div>
      </section>
    );
  };

  if (isSettingsCard) {
    return renderSettingsCard();
  }

  return (
    <div className="form-section mb-6">
      {renderTitle()}
      <div style={gridStyle}>
        {fields.map((field) => {
          // 计算字段的列跨度
          const colSpan = field.layout?.colSpan || 12;
          const rowSpan = field.layout?.rowSpan || 1;

          return (
            <div
              key={field.field}
              style={{
                gridColumn: `span ${colSpan}`,
                gridRow: rowSpan > 1 ? `span ${rowSpan}` : undefined,
              }}
            >
              <FieldRenderer field={field} runtime={runtime} />
            </div>
          );
        })}
      </div>
    </div>
  );
};

export default FormSectionBlockRenderer;
