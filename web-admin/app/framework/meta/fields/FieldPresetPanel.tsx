import React, { useCallback } from 'react';
import { cn } from '~/utils/cn';
import { useI18n } from '~/contexts/I18nContext';
import { ONBOARDING_KEYS } from '~/framework/smart/onboarding/i18nKeys';
import { fieldPresetGroups, type FieldPreset, type FieldPresetGroup } from './fieldPresets';

// ---------------------------------------------------------------------------
// Props
// ---------------------------------------------------------------------------

export interface FieldPresetPanelProps {
  /** Already-added field codes (to grey out duplicates) */
  existingFieldCodes: Set<string>;
  /** Called when the user adds fields from a preset group */
  onAddFields: (fields: FieldPreset[]) => void;
  /** Optional className for the wrapper */
  className?: string;
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export function FieldPresetPanel({
  existingFieldCodes,
  onAddFields,
  className,
}: FieldPresetPanelProps) {
  const { t } = useI18n();

  const handleAddGroup = useCallback(
    (group: FieldPresetGroup) => {
      const newFields = group.fields.filter((f) => !existingFieldCodes.has(f.code));
      if (newFields.length > 0) {
        onAddFields(newFields);
      }
    },
    [existingFieldCodes, onAddFields],
  );

  const handleAddSingle = useCallback(
    (field: FieldPreset) => {
      if (!existingFieldCodes.has(field.code)) {
        onAddFields([field]);
      }
    },
    [existingFieldCodes, onAddFields],
  );

  return (
    <div className={cn('space-y-4', className)} data-testid="field-preset-panel">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-gray-700">
          {t(ONBOARDING_KEYS.fieldPresetQuickAdd)}
        </h3>
      </div>

      <div className="space-y-3">
        {fieldPresetGroups.map((group) => {
          const allExist = group.fields.every((f) => existingFieldCodes.has(f.code));
          const newCount = group.fields.filter((f) => !existingFieldCodes.has(f.code)).length;

          return (
            <div
              key={group.id}
              className="rounded-lg border border-gray-200 p-3"
              data-testid={`preset-group-${group.id}`}
            >
              <div className="mb-2 flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <span className="text-sm font-medium text-gray-700">{t(group.nameKey)}</span>
                  <span className="text-xs text-gray-400">{group.fields.length} fields</span>
                </div>
                <button
                  onClick={() => handleAddGroup(group)}
                  disabled={allExist}
                  className={cn(
                    'rounded-md px-2.5 py-1 text-xs font-medium transition-colors',
                    allExist
                      ? 'cursor-not-allowed bg-gray-100 text-gray-400'
                      : 'bg-blue-50 text-blue-600 hover:bg-blue-100',
                  )}
                >
                  {allExist
                    ? t(ONBOARDING_KEYS.fieldPresetAddAll)
                    : `${t(ONBOARDING_KEYS.fieldPresetAddAll)} (${newCount})`}
                </button>
              </div>

              <div className="flex flex-wrap gap-1.5">
                {group.fields.map((field) => {
                  const exists = existingFieldCodes.has(field.code);
                  return (
                    <button
                      key={field.code}
                      onClick={() => handleAddSingle(field)}
                      disabled={exists}
                      className={cn(
                        'inline-flex items-center gap-1 rounded-md border px-2 py-1 text-xs transition-colors',
                        exists
                          ? 'cursor-not-allowed border-gray-100 bg-gray-50 text-gray-400'
                          : 'cursor-pointer border-gray-200 bg-white text-gray-700 hover:border-blue-300 hover:bg-blue-50',
                      )}
                      title={`${field.code} (${field.type})`}
                    >
                      <span className="font-mono">{field.code}</span>
                      <span className="text-gray-400">{field.type}</span>
                      {field.required && <span className="text-red-400">*</span>}
                      {!exists && (
                        <svg
                          className="h-3 w-3 text-blue-500"
                          fill="none"
                          viewBox="0 0 24 24"
                          strokeWidth={2}
                          stroke="currentColor"
                        >
                          <path strokeLinecap="round" strokeLinejoin="round" d="M12 4v16m8-8H4" />
                        </svg>
                      )}
                    </button>
                  );
                })}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

export default FieldPresetPanel;
