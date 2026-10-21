import { useState, useMemo, useEffect } from 'react';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { useComputedFields } from '~/framework/meta/hooks/useComputedFields';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useFormFill } from '~/framework/meta/rendering/useFormFill';
import { collectFormFillFields } from '~/framework/meta/rendering/formFill';
import { resolveExtensionDisplayName } from '~/framework/meta/utils/i18nResolver';
import type { ComputedFieldDef } from '~/framework/meta/runtime/computed/types';
import type { FieldMetaInfo } from './formPageTypes';
import { mergeRefTarget, resolveComponentByFieldMeta } from './formFieldMetadata';
import { collectFormFieldDataTypes } from './formRecordValues';
import type { useFormPageState } from './useFormPageState';
import type { useFormPageRuntime } from './useFormPageRuntime';
import type { useFormRecord } from './useFormRecord';

type UseFormMetadataArgs = Pick<
  ReturnType<typeof useFormPageState>,
  | 'schema'
  | 'fieldPermissions'
  | 'tableName'
  | 'routeRecordPid'
  | 'formData'
  | 'setFormData'
  | 'token'
> &
  Pick<ReturnType<typeof useFormPageRuntime>, 'locale' | 't'> &
  Pick<
    ReturnType<typeof useFormRecord>,
    'mainRecordLoaded' | 'fieldDataTypesRef' | 'syncRuntimeFieldValue'
  >;

export function useFormMetadata({
  schema,
  fieldPermissions,
  locale,
  t,
  tableName,
  routeRecordPid,
  formData,
  setFormData,
  mainRecordLoaded,
  fieldDataTypesRef,
  token,
  syncRuntimeFieldValue,
}: UseFormMetadataArgs) {
  // Fetch model field metadata for component resolution (must be before early returns)
  const [modelFields, setModelFields] = useState<Record<string, FieldMetaInfo>>({});
  const [fieldMetaLoaded, setFieldMetaLoaded] = useState(false);
  const aiFields = useMemo(
    () =>
      collectFormFillFields(
        schema,
        modelFields,
        fieldPermissions,
        (value) => getLocalizedText(value as any, locale, t) || t('ai.fill.field'),
      ),
    [schema, modelFields, fieldPermissions, locale, t],
  );
  const aiFill = useFormFill(
    `${schema?.id ?? schema?.pageKey ?? tableName}:${routeRecordPid ?? 'new'}`,
    schema?.modelCode || tableName,
    aiFields,
    formData,
    setFormData,
    mainRecordLoaded && fieldMetaLoaded,
  );

  useEffect(() => {
    fieldDataTypesRef.current = collectFormFieldDataTypes(schema?.blocks, modelFields);
  }, [schema?.blocks, modelFields, fieldDataTypesRef]);

  useEffect(() => {
    let cancelled = false;
    setFieldMetaLoaded(false);
    // For page-key routes (e.g. dp_issue_triage), schema.modelCode is the real model.
    const targetModelCode = schema?.modelCode || tableName;
    // Use /api/dynamic/{pageKey}/field-meta which requires model-level read permission
    // instead of /api/meta/models/{pid}/fields which requires management permission
    fetchResult<
      Array<{
        code: string;
        dataType: string;
        dictCode?: string;
        refTarget?: Record<string, any> | null;
        referenceModelCode?: string;
        extension?: any;
        uiSchema?: { label?: string };
        required?: boolean;
        feature?: Record<string, any>;
        ruleSchema?: Record<string, any>;
      }>
    >(`/api/dynamic/${targetModelCode}/field-meta`, {
      method: 'get',
      token: token || undefined,
    })
      .then((fieldsResp) => {
        if (cancelled) return;
        if (fieldsResp && ResultHelper.isSuccess(fieldsResp) && Array.isArray(fieldsResp.data)) {
          const map: Record<string, FieldMetaInfo> = {};
          for (const f of fieldsResp.data) {
            const displayName = resolveExtensionDisplayName(
              f.extension,
              f.code,
              locale,
              f.uiSchema?.label,
            );
            // Merge constraints from extension.extension.constraints into feature.validation
            // Plugin imports store constraints (min, max, maxLength, minLength, pattern)
            // in extension.extension.constraints, but mergeFieldValidationRules reads from
            // feature.validation. Merge them so validation rules are picked up.
            const extConstraints = (f.extension?.extension as any)?.constraints;
            const mergedFeature = f.feature ? { ...f.feature } : {};
            if (extConstraints && typeof extConstraints === 'object') {
              mergedFeature.validation = {
                ...extConstraints,
                ...(mergedFeature.validation || {}),
              };
            }

            map[f.code] = {
              dataType: f.dataType,
              displayName,
              dictCode: f.dictCode,
              refTarget: mergeRefTarget(f.refTarget, f.extension?.refTarget),
              // Also resolve from nested extension (plugin import may store as extension.extension.referenceModelCode or .refModelCode)
              referenceModelCode:
                f.referenceModelCode ||
                (f.extension?.extension as any)?.referenceModelCode ||
                (f.extension?.extension as any)?.refModelCode,
              component: resolveComponentByFieldMeta(f.dataType, f.extension),
              required: Boolean(f.required || extConstraints?.required),
              feature: mergedFeature,
              ruleSchema: f.ruleSchema,
              // Preserve extension for component-specific config (levels, multiple, allowClear, etc.)
              extension: f.extension,
              // Pre-compute component-specific props from extension (stable reference).
              // Plugin import may store properties under nested `extension.extension`;
              // flatten both shapes so downstream renderers see a single bag of props.
              extensionProps: f.extension
                ? Object.fromEntries(
                    Object.entries({
                      ...((f.extension as any)?.extension ?? {}),
                      ...f.extension,
                    }).filter(
                      ([k]) =>
                        ![
                          'renderComponent',
                          'component',
                          'uiComponent',
                          'displayName',
                          'description',
                          'constraints',
                          'precision',
                          'scale',
                          'readOnly',
                          'extension',
                        ].includes(k),
                    ),
                  )
                : undefined,
            };
          }
          setModelFields(map);
        }
      })
      .catch(() => {
        if (!cancelled) setModelFields({});
      })
      .finally(() => {
        if (!cancelled) setFieldMetaLoaded(true);
      });
    return () => {
      cancelled = true;
    };
  }, [tableName, schema?.modelCode, token, locale]);

  const computedFieldDefs = useMemo<ComputedFieldDef[]>(() => {
    const defs: ComputedFieldDef[] = [];
    const blocks = Array.isArray(schema?.blocks) ? schema.blocks : [];

    for (const block of blocks) {
      if (block?.blockType !== 'form-section' || !Array.isArray(block.fields)) continue;
      for (const rawField of block.fields) {
        if (!rawField?.field) continue;
        const meta = modelFields[rawField.field];
        const extensionProps = meta?.extensionProps;
        const formula =
          typeof extensionProps?.formula === 'string' ? extensionProps.formula.trim() : '';
        const computed = extensionProps?.computed === true && formula.length > 0;
        if (!computed) continue;

        const dependencyConfig =
          extensionProps?.computeDependencies ?? extensionProps?.dependencies ?? [];
        const dependencies = Array.isArray(dependencyConfig)
          ? dependencyConfig.map((dep) => String(dep)).filter(Boolean)
          : typeof dependencyConfig === 'string'
            ? dependencyConfig
                .split(',')
                .map((dep) => dep.trim())
                .filter(Boolean)
            : [];

        defs.push({
          fieldCode: rawField.field,
          label: meta?.displayName || rawField.label,
          expression: formula,
          dependencies,
          type: extensionProps?.materialize === false ? 'computed_temp' : 'computed_materialized',
          dataType: meta?.dataType,
          fallbackValue: extensionProps?.computeFallbackValue ?? '',
        });
      }
    }

    return defs;
  }, [schema?.blocks, modelFields]);

  useComputedFields({
    fields: computedFieldDefs,
    formData,
    enabled: computedFieldDefs.length > 0,
    onChange: (fieldCode, value) => {
      syncRuntimeFieldValue(fieldCode, value);
      setFormData((prev) => {
        if (prev[fieldCode] === value) return prev;
        return {
          ...prev,
          [fieldCode]: value,
        };
      });
    },
    onError: (fieldCode, error) => {
      console.warn(`Failed to evaluate computed field "${fieldCode}":`, error);
    },
  });
  return { modelFields, fieldMetaLoaded, aiFields, aiFill };
}
