/**
 * GuardrailsEditor - Structured guardrails editor for Agent definitions
 *
 * Renders a structured form for common guardrail settings instead of raw JSON textarea.
 * Stored value is JSON: { maxCostPerRun, maxTokensPerRun, maxRunsPerDay, memoryMaxChars,
 *   forbiddenTools, requireApproval, timeoutMs }
 */

import React, { forwardRef, useState, useCallback, useMemo } from 'react';
import { useSmartFieldContract } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldContract';
import { FieldBase } from '~/ui/ui/field-base';
import { FieldControl } from '~/ui/ui/field-control';

interface Guardrails {
  maxCostPerRun?: number;
  maxTokensPerRun?: number;
  maxRunsPerDay?: number;
  memoryMaxChars?: number;
  forbiddenTools?: string[];
  requireApproval?: boolean;
  timeoutMs?: number;
}

interface GuardrailsEditorProps {
  label?: string;
  name: string;
  value?: string;
  placeholder?: string;
  error?: string;
  required?: boolean;
  readOnly?: boolean;
  onChange?: (value: string) => void;
}

const GUARDRAIL_FIELDS = [
  {
    key: 'maxCostPerRun',
    label: 'Max Cost per Run ($)',
    type: 'number',
    step: '0.01',
    min: 0,
    desc: 'Maximum dollar cost for a single agent run',
  },
  {
    key: 'maxTokensPerRun',
    label: 'Max Tokens per Run',
    type: 'number',
    step: '1000',
    min: 0,
    desc: 'Maximum total tokens (input + output) per run',
  },
  {
    key: 'maxRunsPerDay',
    label: 'Max Runs per Day',
    type: 'number',
    step: '1',
    min: 0,
    desc: 'Daily execution limit for this agent',
  },
  {
    key: 'memoryMaxChars',
    label: 'Memory Max Chars',
    type: 'number',
    step: '100',
    min: 0,
    desc: 'Maximum characters stored in agent memory per entry',
  },
  {
    key: 'timeoutMs',
    label: 'Timeout (ms)',
    type: 'number',
    step: '1000',
    min: 0,
    desc: 'Maximum execution time in milliseconds',
  },
  {
    key: 'requireApproval',
    label: 'Require Approval',
    type: 'boolean',
    desc: 'Require human approval before execution',
  },
  {
    key: 'forbiddenTools',
    label: 'Forbidden Tools',
    type: 'tags',
    desc: 'Tool codes that this agent cannot use (comma-separated)',
  },
] as const;

export const GuardrailsEditor = forwardRef<HTMLDivElement, GuardrailsEditorProps>(
  (
    {
      label,
      name,
      value,
      placeholder,
      error: propError,
      required = false,
      readOnly = false,
      onChange,
    },
    ref,
  ) => {
    const { labelText, required: requiredValue } = useSmartFieldContract({
      label,
      placeholder,
      required,
    });

    const [showRaw, setShowRaw] = useState(false);

    const guardrails = useMemo<Guardrails>(() => {
      if (!value) return {};
      try {
        const parsed = typeof value === 'string' ? JSON.parse(value) : value;
        return typeof parsed === 'object' && parsed !== null ? parsed : {};
      } catch {
        return {};
      }
    }, [value]);

    const updateField = useCallback(
      (key: string, val: any) => {
        if (readOnly) return;
        const updated = { ...guardrails, [key]: val };
        // Remove null/undefined/empty values
        Object.keys(updated).forEach((k) => {
          const v = updated[k as keyof Guardrails] as unknown;
          if (v === null || v === undefined || v === '') {
            delete updated[k as keyof Guardrails];
          }
        });
        onChange?.(JSON.stringify(updated));
      },
      [guardrails, readOnly, onChange],
    );

    const handleRawChange = useCallback(
      (raw: string) => {
        onChange?.(raw);
      },
      [onChange],
    );

    return (
      <FieldBase label={labelText} required={requiredValue} error={propError} ref={ref}>
        <FieldControl error={propError}>
          <div data-testid={`guardrails-editor-${name}`}>
            {/* Toggle raw/structured */}
            <div className="mb-2 flex justify-end">
              <button
                type="button"
                className="text-xs text-gray-500 transition-colors hover:text-blue-500 dark:text-gray-400"
                onClick={() => setShowRaw(!showRaw)}
              >
                {showRaw ? 'Structured View' : 'Raw JSON'}
              </button>
            </div>

            {showRaw ? (
              <textarea
                name={name}
                className="h-40 w-full resize-y rounded-lg border border-gray-300 bg-white px-3 py-2 font-mono text-sm text-gray-900 focus:ring-1 focus:ring-blue-500 focus:outline-none dark:border-gray-600 dark:bg-gray-800 dark:text-white"
                value={value || '{}'}
                onChange={(e) => handleRawChange(e.target.value)}
                readOnly={readOnly}
                data-testid="guardrails-raw"
              />
            ) : (
              <div className="space-y-3 rounded-lg border border-gray-200 bg-gray-50/50 p-3 dark:border-gray-700 dark:bg-gray-800/50">
                {GUARDRAIL_FIELDS.map((field) => (
                  <GuardrailField
                    key={field.key}
                    field={field}
                    value={guardrails[field.key as keyof Guardrails]}
                    readOnly={readOnly}
                    onChange={(val) => updateField(field.key, val)}
                  />
                ))}
                <input type="hidden" name={name} value={value || '{}'} />
              </div>
            )}
          </div>
        </FieldControl>
      </FieldBase>
    );
  },
);

function GuardrailField({
  field,
  value,
  readOnly,
  onChange,
}: {
  field: (typeof GUARDRAIL_FIELDS)[number];
  value: any;
  readOnly: boolean;
  onChange: (val: any) => void;
}) {
  const [input, setInput] = useState('');

  if (field.type === 'boolean') {
    return (
      <div className="flex items-center justify-between">
        <div>
          <div className="text-sm font-medium text-gray-700 dark:text-gray-300">{field.label}</div>
          <div className="text-xs text-gray-500 dark:text-gray-400">{field.desc}</div>
        </div>
        <button
          type="button"
          role="switch"
          aria-checked={!!value}
          disabled={readOnly}
          className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors ${value ? 'bg-blue-600' : 'bg-gray-300 dark:bg-gray-600'} ${readOnly ? 'opacity-50' : ''}`}
          onClick={() => onChange(!value)}
        >
          <span
            className={`inline-block h-3.5 w-3.5 transform rounded-full bg-white transition-transform ${value ? 'translate-x-4' : 'translate-x-0.5'}`}
          />
        </button>
      </div>
    );
  }

  if (field.type === 'tags') {
    const tags = Array.isArray(value) ? value : [];

    return (
      <div>
        <div className="text-sm font-medium text-gray-700 dark:text-gray-300">{field.label}</div>
        <div className="mb-1 text-xs text-gray-500 dark:text-gray-400">{field.desc}</div>
        <div className="mb-1 flex flex-wrap gap-1">
          {tags.map((tag: string, i: number) => (
            <span
              key={i}
              className="inline-flex items-center gap-1 rounded-full bg-red-100 px-2 py-0.5 text-xs text-red-700 dark:bg-red-900/30 dark:text-red-300"
            >
              {tag}
              {!readOnly && (
                <button
                  type="button"
                  onClick={() => onChange(tags.filter((_: string, j: number) => j !== i))}
                  className="hover:text-red-900"
                >
                  ×
                </button>
              )}
            </span>
          ))}
        </div>
        {!readOnly && (
          <input
            type="text"
            className="w-full rounded border border-gray-300 bg-white px-2 py-1 text-sm text-gray-900 dark:border-gray-600 dark:bg-gray-800 dark:text-white"
            placeholder="Type tool code and press Enter"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && input.trim()) {
                e.preventDefault();
                onChange([...tags, input.trim()]);
                setInput('');
              }
            }}
          />
        )}
      </div>
    );
  }

  // Number fields
  return (
    <div>
      <div className="flex items-center justify-between">
        <div>
          <div className="text-sm font-medium text-gray-700 dark:text-gray-300">{field.label}</div>
          <div className="text-xs text-gray-500 dark:text-gray-400">{field.desc}</div>
        </div>
        <input
          type="number"
          className="w-32 rounded border border-gray-300 bg-white px-2 py-1 text-right text-sm text-gray-900 dark:border-gray-600 dark:bg-gray-800 dark:text-white"
          value={value ?? ''}
          step={field.step}
          min={field.min}
          readOnly={readOnly}
          onChange={(e) => {
            const num = e.target.value === '' ? undefined : Number(e.target.value);
            onChange(num);
          }}
          data-testid={`guardrail-${field.key}`}
        />
      </div>
    </div>
  );
}

GuardrailsEditor.displayName = 'GuardrailsEditor';
export default GuardrailsEditor;
