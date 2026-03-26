import React from 'react';
import { TRIGGER_EVENT_INFO, type LinkageTrigger, type TriggerEvent } from './types';

interface TriggerConfigProps {
  trigger: LinkageTrigger;
  onChange: (updates: Partial<LinkageTrigger>) => void;
  fieldOptions: { code: string; label: string }[];
  readonly?: boolean;
}

/**
 * TriggerConfig - configure the trigger field, event type, and optional condition.
 *
 * @since 3.5.0
 */
export const TriggerConfig: React.FC<TriggerConfigProps> = ({
  trigger,
  onChange,
  fieldOptions,
  readonly = false,
}) => {
  return (
    <div className="space-y-2">
      <div className="flex items-center gap-1.5">
        <span className="shrink-0 text-xs text-gray-500">当</span>

        {/* Field selector */}
        <select
          value={trigger.fieldCode}
          onChange={(e) => onChange({ fieldCode: e.target.value })}
          className="min-w-0 flex-1 rounded border border-gray-200 px-2 py-1 text-xs focus:ring-1 focus:ring-blue-300 focus:outline-none"
          disabled={readonly}
        >
          <option value="">选择字段</option>
          {fieldOptions.map((f) => (
            <option key={f.code} value={f.code}>
              {f.label}
            </option>
          ))}
        </select>

        {/* Event selector */}
        <select
          value={trigger.event}
          onChange={(e) => onChange({ event: e.target.value as TriggerEvent })}
          className="w-20 rounded border border-gray-200 px-1.5 py-1 text-xs focus:ring-1 focus:ring-blue-300 focus:outline-none"
          disabled={readonly}
        >
          {Object.entries(TRIGGER_EVENT_INFO).map(([key, info]) => (
            <option key={key} value={key}>
              {info.label}
            </option>
          ))}
        </select>

        <span className="shrink-0 text-xs text-gray-500">时</span>
      </div>

      {/* Condition expression */}
      <div className="flex items-center gap-1.5">
        <span className="shrink-0 text-xs text-gray-500">条件</span>
        <input
          type="text"
          value={trigger.condition ?? ''}
          onChange={(e) => onChange({ condition: e.target.value || undefined })}
          placeholder="可选，如: #fieldCode == 'value'"
          className="flex-1 rounded border border-gray-200 px-2 py-1 font-mono text-xs focus:ring-1 focus:ring-blue-300 focus:outline-none"
          disabled={readonly}
        />
      </div>
    </div>
  );
};
