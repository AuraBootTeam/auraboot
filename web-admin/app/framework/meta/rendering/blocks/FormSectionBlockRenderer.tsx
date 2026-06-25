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
      const displayValue =
        value === null || value === undefined || value === '' ? '—' : String(value);
      const isLongText = displayValue.includes('\n') || displayValue.length > 56;
      const toneClass = isPositiveDisplayValue(displayValue)
        ? 'border-emerald-200 bg-emerald-50 text-emerald-700'
        : isNegativeDisplayValue(displayValue)
          ? 'border-slate-200 bg-slate-100 text-slate-600'
          : 'border-blue-200 bg-blue-50 text-blue-700';

      if (isLongText) {
        return (
          <p className="mt-2 text-sm leading-6 whitespace-pre-line text-slate-700">
            {displayValue}
          </p>
        );
      }

      return (
        <span
          className={`mt-2 inline-flex w-fit max-w-full items-center rounded-md border px-2.5 py-1 text-sm font-medium ${toneClass}`}
        >
          {displayValue}
        </span>
      );
    };

    return (
      <section className="mb-4 overflow-hidden rounded-lg border border-slate-200 bg-white shadow-sm">
        <div className="flex items-start gap-3 border-b border-slate-100 bg-slate-50 px-5 py-4">
          <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-md bg-blue-50 text-blue-600 ring-1 ring-blue-100">
            <Icon className="h-4 w-4" aria-hidden="true" />
          </div>
          <div className="min-w-0">
            <h3 className="text-base font-semibold text-slate-900">{title}</h3>
            {description ? <p className="mt-1 text-sm text-slate-500">{description}</p> : null}
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
                className="min-w-0 rounded-md border border-slate-100 bg-white px-4 py-3 ring-1 ring-slate-50"
                style={{ gridColumn: `span ${Math.min(12, Math.max(1, colSpan))}` }}
              >
                <div className="text-xs font-medium text-slate-500">{label}</div>
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
