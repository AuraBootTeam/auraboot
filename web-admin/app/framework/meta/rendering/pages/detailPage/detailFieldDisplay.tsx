import {
  FileText,
  KeyRound,
  LockKeyhole,
  RotateCcwKey,
  ShieldCheck,
  type LucideIcon,
} from 'lucide-react';
import type { BlockConfig, FieldConfig } from '~/framework/meta/schemas/types';
import {
  formatInTimezone,
  resolveTemporalFormat,
  resolveTemporalType,
  type DateTimeFormatPreferences,
} from '~/shared/services/dateTimeFormatService';

export const DETAIL_SECTION_ICONS: Record<string, LucideIcon> = {
  behavior: ShieldCheck,
  password: KeyRound,
  reset: RotateCcwKey,
  lockout: LockKeyhole,
  notes: FileText,
};

export function resolveDetailSectionIcon(block: BlockConfig): LucideIcon {
  const explicitIcon = String((block as any).extension?.icon || '').toLowerCase();
  const blockId = String(block.id || '').toLowerCase();
  const key =
    explicitIcon || Object.keys(DETAIL_SECTION_ICONS).find((item) => blockId.includes(item));
  return key && DETAIL_SECTION_ICONS[key] ? DETAIL_SECTION_ICONS[key] : FileText;
}

export function getDetailValueTone(value: unknown): string {
  const text = String(value ?? '').toLowerCase();
  if (text.includes('已启用') || text.includes('enabled') || text.includes('管理员托管')) {
    return 'border-status-green bg-status-green-bg text-status-green';
  }
  if (text.includes('已停用') || text.includes('disabled')) {
    return 'border-border bg-subtle text-text-2';
  }
  return 'border-status-blue bg-status-blue-bg text-status-blue';
}

export function resolveDetailFieldComponent(meta?: {
  dataType?: string;
  referenceModelCode?: string;
  refTarget?: Record<string, any>;
  extension?: Record<string, any>;
}): string | undefined {
  if (!meta) return undefined;

  const renderComp =
    meta.extension?.renderComponent || meta.extension?.component || meta.extension?.uiComponent;

  if (renderComp) {
    return String(renderComp).trim().toLowerCase();
  }

  const refTarget = {
    ...(meta.extension?.refTarget || {}),
    ...(meta.refTarget || {}),
  };
  const referenceModel = String(
    refTarget.targetModel ||
      refTarget.targetEntity ||
      refTarget.modelCode ||
      meta.referenceModelCode ||
      meta.extension?.referenceModelCode ||
      meta.extension?.refModelCode ||
      '',
  ).toLowerCase();

  switch (String(meta.dataType || '').toLowerCase()) {
    case 'json':
    case 'jsonb':
      return 'jsonviewer';
    case 'boolean':
      return 'switch';
    case 'date':
      return 'date';
    case 'datetime':
      return 'datetime';
    case 'file':
      return 'fileattachment';
    case 'money':
    case 'decimal':
    case 'integer':
      return 'number';
    case 'reference':
      if (referenceModel === 'sys_user') return 'userselect';
      if (referenceModel === 'org_department') return 'organizationselect';
      return 'reference';
    default:
      return undefined;
  }
}

export function getMetaExtension(meta?: { extension?: Record<string, any> }): Record<string, any> {
  return meta?.extension && typeof meta.extension === 'object' ? meta.extension : {};
}

export function resolveTextFallback(
  t: (key: string) => string,
  key: string,
  fallback: string,
): string {
  const resolved = t(key);
  return resolved && resolved !== key ? resolved : fallback;
}

export function shouldReplaceGeneratedLabel(label: unknown, fieldCode: string): boolean {
  if (!label) return true;
  if (typeof label !== 'string') return false;
  const normalized = label.trim();
  return normalized === fieldCode || normalized === fieldCode.toUpperCase();
}

export function isJsonFieldCode(fieldCode: string): boolean {
  return /(^|_)json$/i.test(fieldCode.trim());
}

export function enrichDetailField(field: FieldConfig, meta?: Record<string, any>): FieldConfig {
  const enriched = { ...field } as any;
  if (!meta) {
    if (!enriched.component && isJsonFieldCode(field.field)) {
      enriched.component = 'jsonviewer';
    }
    return enriched as FieldConfig;
  }

  const extensionProps = getMetaExtension(meta);
  if (!enriched.dataType) enriched.dataType = meta.dataType;
  const refTarget = {
    ...(extensionProps.refTarget || {}),
    ...(meta.refTarget || {}),
  };
  const dictCode = field.dictCode || meta.dictCode || extensionProps.dictCode;
  const displayName = meta.displayName || extensionProps.displayName;

  enriched.props = {
    ...(field.props || {}),
    ...extensionProps,
  };

  if (dictCode) {
    enriched.dictCode = dictCode;
  }

  if (displayName && shouldReplaceGeneratedLabel(field.label, field.field)) {
    enriched.label = displayName;
  }

  if (!enriched.component) {
    const resolvedComponent = resolveDetailFieldComponent(meta);
    if (resolvedComponent) {
      enriched.component = resolvedComponent;
    } else if (isJsonFieldCode(field.field)) {
      enriched.component = 'jsonviewer';
    }
  }

  if (Object.keys(refTarget).length > 0 && !enriched.refTarget) {
    enriched.refTarget = refTarget;
  }

  if (!enriched.referenceModelCode) {
    enriched.referenceModelCode =
      meta.referenceModelCode ||
      extensionProps.referenceModelCode ||
      extensionProps.refModelCode ||
      refTarget.targetModel ||
      refTarget.targetEntity ||
      refTarget.modelCode;
  }

  return enriched as FieldConfig;
}

export function resolveSettingsCardField(
  field: FieldConfig,
  resolveLabel: (fieldCode: string) => string,
  enrichField?: (field: FieldConfig) => FieldConfig,
): FieldConfig {
  const fieldWithFallbackLabel = field.label
    ? field
    : { ...field, label: resolveLabel(field.field) };
  return enrichField ? enrichField(fieldWithFallbackLabel) : fieldWithFallbackLabel;
}

export function resolveSettingsCardDisplayValue(
  rawValue: unknown,
  displayValue: unknown,
  field: FieldConfig,
  getDictItems?: (
    code: string,
  ) => Array<{ value: string; label: string; extension?: Record<string, any> }>,
  timeZone?: string,
  formats?: Partial<DateTimeFormatPreferences>,
): string {
  if (rawValue === null || rawValue === undefined || rawValue === '') return '—';
  // List APIs may emit a `<field>_display` value that is identical to the raw enum
  // token. Resolve the dictionary first so settings cards never leak values such as
  // `draft`, `production_pick` or `reversal` when a business label is available.
  if (field.dictCode && getDictItems) {
    const item = (getDictItems(field.dictCode) || []).find(
      (candidate) => String(candidate.value) === String(rawValue),
    );
    if (item?.label) return item.label;
  }
  const temporalType = resolveTemporalType(
    field.field,
    (field as FieldConfig & { dataType?: string }).dataType,
    rawValue,
  );
  if (temporalType) {
    return formatInTimezone(
      rawValue as string | number | Date,
      resolveTemporalFormat(temporalType, formats, (field as any).format),
      timeZone,
    );
  }
  if (displayValue !== null && displayValue !== undefined && displayValue !== '') {
    return String(displayValue);
  }
  return String(rawValue);
}

export function collectDetailDictCodes(
  schema: { blocks?: BlockConfig[]; extension?: Record<string, any> } | undefined | null,
  modelFieldMap: Map<string, any>,
): string[] {
  if (!schema?.blocks) return [];
  const codes: string[] = [];
  const collectFromFields = (fields: FieldConfig[]) => {
    for (const field of fields) {
      const fieldProps = (field as any).props || {};
      const meta = modelFieldMap.get(field.field);
      const metaExtension = getMetaExtension(meta);
      const dictCode =
        field.dictCode || fieldProps.dictCode || meta?.dictCode || metaExtension.dictCode;
      if (dictCode) codes.push(dictCode);
    }
  };
  const walkBlocks = (blocks: BlockConfig[]) => {
    for (const block of blocks) {
      if (
        (block.blockType === 'form-section' || block.blockType === 'detail-section') &&
        block.fields
      ) {
        collectFromFields(block.fields);
      }
      if ((block as any).tabs) {
        for (const tab of (block as any).tabs) {
          if (tab.blocks) walkBlocks(tab.blocks);
        }
      }
      if ((block as any).blocks) {
        walkBlocks((block as any).blocks);
      }
    }
  };
  walkBlocks(schema.blocks);
  if (schema.extension?.headerStatusField)
    collectFromFields([{ field: schema.extension.headerStatusField }]);
  return [...new Set(codes)];
}
