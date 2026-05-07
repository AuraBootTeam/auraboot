import React, { useState, useCallback } from 'react';
import { toast } from 'sonner';
import type { ModelFieldBinding, ValidationRule } from '~/types/model';
import { useI18n } from '~/contexts/I18nContext';
import { SchemaBlockConfigPanel } from '~/shared/designer/SchemaBlockConfigPanel';
import { fieldConfigSchemas } from './fieldConfigSchemas';

export interface FieldBindingConfig {
  required: boolean;
  readonly: boolean;
  visible: boolean;
  displayOrder?: number;
  defaultValueMode: 'static' | 'expression';
  defaultValue?: string | number | boolean | null;
  defaultValueExpression?: string;
  dictCode?: string;
  validationRules?: ValidationRule[];
}

interface FieldConfigDialogProps {
  field: ModelFieldBinding;
  onSave: (binding: Partial<ModelFieldBinding>) => Promise<void>;
  onClose: () => void;
}

export function FieldConfigDialog({ field, onSave, onClose }: FieldConfigDialogProps) {
  const { t } = useI18n();
  const [config, setConfig] = useState<FieldBindingConfig>(() => bindingToConfig(field));
  const [saving, setSaving] = useState(false);

  const handleSave = useCallback(async () => {
    setSaving(true);
    try {
      await onSave(configToBinding(config));
      toast.success(t('field.dialog.save_success', undefined, '字段配置已保存'));
      onClose();
    } catch (err) {
      console.error('[FieldConfigDialog] save failed', err);
      toast.error(
        err instanceof Error ? err.message : t('common.save_failed', undefined, '保存失败'),
      );
    } finally {
      setSaving(false);
    }
  }, [config, onSave, onClose, t]);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/50" onClick={onClose} />
      <div className="relative flex max-h-[80vh] w-full max-w-3xl flex-col rounded-lg bg-white shadow-xl">
        <div className="border-b px-6 py-4">
          <h2 className="text-lg font-semibold">
            {t('field.dialog.title', { code: field.fieldCode }, `配置字段 ${field.fieldCode}`)}
          </h2>
        </div>
        <div className="flex-1 overflow-y-auto px-6 py-4">
          <SchemaBlockConfigPanel
            schemas={fieldConfigSchemas as any}
            value={config as unknown as Record<string, unknown>}
            onChange={(next: Record<string, unknown>) =>
              setConfig(next as unknown as FieldBindingConfig)
            }
          />
        </div>
        <div className="flex justify-end gap-3 border-t px-6 py-4">
          <button onClick={onClose} className="rounded border px-4 py-2">
            {t('common.cancel', undefined, '取消')}
          </button>
          <button
            onClick={handleSave}
            disabled={saving}
            className="rounded bg-blue-600 px-4 py-2 text-white disabled:opacity-50"
          >
            {saving
              ? t('common.saving', undefined, '保存中...')
              : t('common.save', undefined, '保存')}
          </button>
        </div>
      </div>
    </div>
  );
}

// ---- Adapters (exported for testing) ----

export function bindingToConfig(field: ModelFieldBinding): FieldBindingConfig {
  const expr = (field.extension as any)?.defaultValueExpression as string | undefined;
  return {
    required: field.required ?? false,
    readonly: field.readonly ?? false,
    visible: field.visible !== false,
    displayOrder: field.displayOrder ?? 0,
    defaultValueMode: expr ? 'expression' : 'static',
    defaultValue: expr ? undefined : (field.defaultValue as FieldBindingConfig['defaultValue']),
    defaultValueExpression: expr,
    dictCode: field.dictCode,
    validationRules: field.validationRules ?? [],
  };
}

export function configToBinding(c: FieldBindingConfig): Partial<ModelFieldBinding> {
  const isExpr = c.defaultValueMode === 'expression';
  return {
    required: c.required,
    readonly: c.readonly,
    visible: c.visible,
    displayOrder: c.displayOrder,
    defaultValue: isExpr ? null : (c.defaultValue ?? null),
    extension: isExpr ? { defaultValueExpression: c.defaultValueExpression } : {},
    dictCode: c.dictCode || undefined,
    validationRules: c.validationRules?.length ? c.validationRules : undefined,
  };
}
