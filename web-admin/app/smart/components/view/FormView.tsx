/**
 * FormView — Data collection form view (GAP-120)
 *
 * Renders a standalone form for data collection, based on model field definitions.
 * Users can submit data which triggers a CREATE command on the model.
 *
 * ViewConfig fields:
 * - formFields: string[] — field codes to include (empty = all fields)
 * - formTitle: string — custom form title
 * - formDescription: string — description text shown above form
 * - formSubmitLabel: string — custom submit button text
 * - formSuccessMessage: string — message shown after successful submission
 */

import React, { useState, useEffect, useCallback, useMemo } from 'react';
import { fetchResult } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import type { ViewConfig } from '~/smart/types/savedView';
import type { FieldConfig } from '~/meta/schemas/types';
import { createExpressionContext } from '~/meta/runtime/expression/context';
import { ControlledFieldRenderer } from '~/meta/rendering/ControlledFieldRenderer';
import { ViewEmptyState } from './shared';

interface FormViewProps {
  viewConfig?: ViewConfig;
  modelCode: string;
  onSubmitSuccess?: (recordId: string) => void;
  onOpenViewConfig?: () => void;
  className?: string;
}

interface FieldMeta {
  code: string;
  columnName: string;
  displayName: string;
  dataType: string;
  required: boolean;
  dictCode?: string;
  referenceModelCode?: string;
  referenceDisplayField?: string;
  component?: string;
}

function fieldMetaToFieldConfig(field: FieldMeta): FieldConfig {
  const config: FieldConfig = {
    field: field.code,
    label: field.displayName,
    required: field.required,
    dictCode: field.dictCode,
    layout: { colSpan: 12 },
  };
  (config as any).dataType = field.dataType;
  if (field.referenceModelCode) {
    config.props = {
      refTarget: {
        targetModel: field.referenceModelCode,
        targetField: field.referenceDisplayField || 'name',
      },
    };
  }
  if (field.component) {
    config.component = field.component;
  }
  return config;
}

export const FormView: React.FC<FormViewProps> = ({
  viewConfig,
  modelCode,
  onSubmitSuccess,
  onOpenViewConfig,
  className,
}) => {
  const [fields, setFields] = useState<FieldMeta[]>([]);
  const [formData, setFormData] = useState<Record<string, any>>({});
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const formFields = viewConfig?.formFields;
  const formTitle = viewConfig?.formTitle || 'Submit';
  const formDescription = viewConfig?.formDescription;
  const submitLabel = viewConfig?.formSubmitLabel || 'Submit';
  const successMessage = viewConfig?.formSuccessMessage || 'Thank you! Your submission has been received.';

  // Fetch model field metadata
  useEffect(() => {
    if (!modelCode) { setLoading(false); return; }
    const slug = modelCode.replace(/_/g, '-');
    fetchResult<any>(`/api/meta/dynamic/${slug}/meta`)
      .then((result) => {
        if (ResultHelper.isSuccess(result) && result.data) {
          const meta = result.data;
          let fieldDefs: FieldMeta[] = (meta.fields || []).map((f: any) => ({
            code: f.code,
            columnName: f.columnName || f.code,
            displayName: f.displayName || f.code,
            dataType: f.dataType || 'string',
            required: f.required || false,
            dictCode: f.dictCode,
            referenceModelCode: f.referenceModelCode,
            referenceDisplayField: f.referenceDisplayField,
            component: f.extension?.renderComponent,
          }));

          // Filter to configured fields if specified
          if (formFields && formFields.length > 0) {
            const allowedSet = new Set(formFields);
            fieldDefs = fieldDefs.filter((f) => allowedSet.has(f.code));
            // Sort by formFields order
            fieldDefs.sort((a, b) => formFields.indexOf(a.code) - formFields.indexOf(b.code));
          }

          // Exclude system/auto fields
          const systemFields = new Set(['id', 'pid', 'tenant_id', 'created_at', 'updated_at', 'created_by', 'updated_by', 'deleted_flag']);
          fieldDefs = fieldDefs.filter((f) => !systemFields.has(f.code));

          setFields(fieldDefs);
        }
      })
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false));
  }, [modelCode, formFields]);

  const handleFieldChange = useCallback((fieldCode: string, value: any) => {
    setFormData((prev) => ({ ...prev, [fieldCode]: value }));
  }, []);

  const handleSubmit = useCallback(async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    setError(null);

    try {
      // Find the CREATE command for this model
      const slug = modelCode.replace(/_/g, '-');
      const result = await fetchResult<any>(`/api/dynamic/${slug}`, {
        method: 'post',
        params: formData,
      });

      if (ResultHelper.isSuccess(result)) {
        setSubmitted(true);
        const newId = result.data?.pid || result.data?.id;
        if (newId) onSubmitSuccess?.(String(newId));
      } else {
        setError(result.desc || 'Submission failed');
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Submission failed');
    } finally {
      setSubmitting(false);
    }
  }, [modelCode, formData, onSubmitSuccess]);

  const handleReset = useCallback(() => {
    setFormData({});
    setSubmitted(false);
    setError(null);
  }, []);

  const expressionContext = useMemo(
    () => createExpressionContext({ form: formData, locale: 'zh-CN' }),
    [formData],
  );

  if (loading) {
    return (
      <div className="flex items-center justify-center p-12">
        <div className="h-5 w-5 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
      </div>
    );
  }

  if (fields.length === 0) {
    return (
      <ViewEmptyState
        variant="not-configured"
        title="Form view not configured"
        description="No fields available for this model."
        onConfigure={onOpenViewConfig}
        className={className}
      />
    );
  }

  // Success state
  if (submitted) {
    return (
      <div className={`mx-auto max-w-lg p-8 ${className || ''}`} data-testid="form-view-success">
        <div className="rounded-lg border border-green-200 bg-green-50 p-6 text-center">
          <svg className="mx-auto mb-3 h-12 w-12 text-green-500" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
          </svg>
          <h3 className="mb-2 text-lg font-medium text-green-800">{successMessage}</h3>
          <button
            type="button"
            onClick={handleReset}
            className="mt-4 rounded-md bg-green-600 px-4 py-2 text-sm font-medium text-white hover:bg-green-700"
            data-testid="form-view-submit-another"
          >
            Submit Another
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className={`mx-auto max-w-lg p-6 ${className || ''}`} data-testid="form-view">
      <form onSubmit={handleSubmit} className="space-y-6">
        {/* Header */}
        <div className="text-center">
          <h2 className="text-xl font-semibold text-gray-900" data-testid="form-view-title">{formTitle}</h2>
          {formDescription && (
            <p className="mt-1 text-sm text-gray-500">{formDescription}</p>
          )}
        </div>

        {/* Error */}
        {error && (
          <div className="rounded-md bg-red-50 p-3 text-sm text-red-700" data-testid="form-view-error">
            {error}
          </div>
        )}

        {/* Fields */}
        <div className="space-y-4">
          {fields.map((field) => (
            <ControlledFieldRenderer
              key={field.code}
              field={fieldMetaToFieldConfig(field)}
              value={formData[field.code]}
              onChange={(v) => handleFieldChange(field.code, v)}
              context={expressionContext}
            />
          ))}
        </div>

        {/* Submit */}
        <button
          type="submit"
          disabled={submitting}
          className="w-full rounded-md bg-blue-600 px-4 py-2.5 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
          data-testid="form-view-submit"
        >
          {submitting ? 'Submitting...' : submitLabel}
        </button>
      </form>
    </div>
  );
};

export default FormView;
