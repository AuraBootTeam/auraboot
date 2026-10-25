import { useCallback } from 'react';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { evaluateCondition } from '~/framework/meta/runtime/expression/evaluator';
import { scrollToFormField } from '~/framework/meta/rendering/pages/form/scrollToFormField';
import {
  buildConstraintFieldMessage,
  buildRequiredFieldMessage,
} from '~/framework/meta/utils/validationMessages';
import { mergeRules as crossFieldMergeRules } from '~/framework/meta/validation/ruleMerger';
import { evaluateCondition as crossFieldEvalCondition } from '~/framework/meta/validation/conditionEvaluator';
import { evaluateAssert as crossFieldEvalAssert } from '~/framework/meta/validation/assertEvaluator';
import { checkKindCompatibility } from '~/shared/utils/kindCapability';
import type { FormValidationResult } from './formPageTypes';
import { resolveValidationToastMessage, pushUniqueMessage } from './formValidationValues';
import { getBomUploadReviewError } from './formPayload';
import { getJsonFormValueError } from './formRecordValues';
import { mergeFieldValidationRules } from './formFieldMetadata';
import type { useFormPageState } from './useFormPageState';
import type { useFormPageRuntime } from './useFormPageRuntime';
import type { useFormMetadata } from './useFormMetadata';

type UseFormValidationArgs = Pick<
  ReturnType<typeof useFormPageState>,
  'schema' | 'recordPid' | 'initialFormData' | 'formData' | 'showErrorToast'
> &
  Pick<
    ReturnType<typeof useFormPageRuntime>,
    'pageContext' | 't' | 'locale' | 'hasModelCodeAndKindFields' | 'kindCapabilities'
  > &
  Pick<ReturnType<typeof useFormMetadata>, 'modelFields'>;

export function useFormValidation({
  schema,
  recordPid,
  initialFormData,
  formData,
  pageContext,
  modelFields,
  t,
  locale,
  hasModelCodeAndKindFields,
  kindCapabilities,
  showErrorToast,
}: UseFormValidationArgs) {
  const validateFormBeforeSubmit = useCallback((): FormValidationResult => {
    if (!schema?.blocks) {
      return { fieldErrors: {}, summaryErrors: [] };
    }
    const nextFieldErrors: Record<string, string> = {};
    const nextSummaryErrors: string[] = [];
    const submissionData = recordPid ? { ...(initialFormData || {}), ...formData } : formData;
    const blocks = schema.blocks;
    const formSectionBlocks = blocks.filter((b: any) => b.blockType === 'form-section');

    for (const block of formSectionBlocks) {
      if (block.visibleWhen && !evaluateCondition(block.visibleWhen, pageContext)) {
        continue;
      }
      const fields = Array.isArray(block.fields) ? block.fields : [];
      for (const rawField of fields) {
        const meta = modelFields[rawField.field];
        const rules = mergeFieldValidationRules(rawField, meta, t, locale);
        const label =
          getLocalizedText(rawField.label, locale, t) || meta?.displayName || rawField.field;

        if (rawField.visibleWhen && !evaluateCondition(rawField.visibleWhen, pageContext)) {
          continue;
        }

        const value = submissionData[rawField.field];
        const reviewError = getBomUploadReviewError(
          value,
          locale.startsWith('zh')
            ? '请确认 BOM 识别列，并且只选择一个单套用量列。'
            : 'Review the BOM columns and select exactly one quantity column.',
        );
        if (reviewError) {
          nextFieldErrors[rawField.field] = reviewError;
          continue;
        }
        const jsonValueError = getJsonFormValueError(
          value,
          rawField.dataType || meta?.dataType,
          label,
        );
        if (jsonValueError) {
          nextFieldErrors[rawField.field] = jsonValueError;
          continue;
        }
        for (const rule of rules) {
          if (!rule?.type) continue;
          if (rule.type === 'required') {
            const empty =
              value === undefined ||
              value === null ||
              value === '' ||
              (Array.isArray(value) && value.length === 0);
            if (empty) {
              nextFieldErrors[rawField.field] =
                rule.message ||
                buildRequiredFieldMessage(label, {
                  dataType: meta?.dataType,
                  component: rawField.component,
                  locale,
                  t,
                });
              break;
            }
          }
          if (
            rule.type === 'maxLength' &&
            typeof value === 'string' &&
            Number.isFinite(rule.maxLength)
          ) {
            if (value.length > Number(rule.maxLength)) {
              nextFieldErrors[rawField.field] = buildConstraintFieldMessage(
                label,
                { type: 'maxLength', maxLength: Number(rule.maxLength) },
                { locale, t },
              );
              break;
            }
          }
          if (
            rule.type === 'minLength' &&
            typeof value === 'string' &&
            Number.isFinite(rule.minLength)
          ) {
            if (value.length < Number(rule.minLength)) {
              nextFieldErrors[rawField.field] =
                `${label} is shorter than min length ${rule.minLength}`;
              break;
            }
          }
          if (rule.type === 'pattern' && typeof value === 'string' && rule.pattern) {
            try {
              const regex = new RegExp(rule.pattern);
              if (!regex.test(value)) {
                nextFieldErrors[rawField.field] = buildConstraintFieldMessage(
                  label,
                  { type: 'pattern' },
                  { locale, t },
                );
                break;
              }
            } catch {
              // Ignore invalid regex rule configuration.
            }
          }
          if (rule.type === 'minValue' && Number.isFinite(rule.minValue)) {
            const num = Number(value);
            if (Number.isFinite(num) && num < rule.minValue!) {
              nextFieldErrors[rawField.field] = buildConstraintFieldMessage(
                label,
                { type: 'minValue', minValue: rule.minValue },
                { locale, t },
              );
              break;
            }
          }
          if (rule.type === 'maxValue' && Number.isFinite(rule.maxValue)) {
            const num = Number(value);
            if (Number.isFinite(num) && num > rule.maxValue!) {
              nextFieldErrors[rawField.field] = buildConstraintFieldMessage(
                label,
                { type: 'maxValue', maxValue: rule.maxValue },
                { locale, t },
              );
              break;
            }
          }
        }
      }
    }

    // Kind × ModelCapabilities validation (P2B-T2).
    // When this form binds both `model_code` and `kind`, block submit if the
    // selected kind is not supported by the selected model's capabilities.
    if (hasModelCodeAndKindFields) {
      const selectedKind =
        typeof submissionData.kind === 'string' ? submissionData.kind : undefined;
      const selectedModelCode =
        typeof submissionData.model_code === 'string' ? submissionData.model_code : undefined;
      if (selectedKind && selectedModelCode && kindCapabilities) {
        const compat = checkKindCompatibility(selectedKind, kindCapabilities);
        if (!compat.compatible) {
          pushUniqueMessage(
            nextSummaryErrors,
            compat.reason
              ? `Kind "${selectedKind}" is not supported by model "${selectedModelCode}": ${compat.reason}`
              : `Kind "${selectedKind}" is not supported by model "${selectedModelCode}"`,
          );
        }
      }
    }

    // Cross-field validation rules (from model-level rules + command overrides)
    if (schema?.rules && schema.rules.length > 0) {
      const finalRules = crossFieldMergeRules(schema.rules, schema.ruleOverrides ?? []);
      for (const rule of finalRules) {
        if (rule.when) {
          if (!crossFieldEvalCondition(rule.when, submissionData)) continue;
        }
        const result = crossFieldEvalAssert(rule.assert, submissionData);
        if (!result.skipped && !result.passed) {
          if (rule.severity !== 'warning') {
            const msg = rule.message?.replace(/\{(\w+)\}/g, (_, k: string) =>
              String(submissionData[k] ?? k),
            );
            const finalMessage = msg || `Validation failed: ${rule.id}`;
            if (rule.targetField && !nextFieldErrors[rule.targetField]) {
              nextFieldErrors[rule.targetField] = finalMessage;
            }
            pushUniqueMessage(nextSummaryErrors, finalMessage);
          }
        }
      }
    }

    return { fieldErrors: nextFieldErrors, summaryErrors: nextSummaryErrors };
  }, [
    schema,
    pageContext,
    modelFields,
    t,
    locale,
    formData,
    initialFormData,
    recordPid,
    hasModelCodeAndKindFields,
    kindCapabilities,
  ]);

  const notifyValidationFailure = useCallback(
    (validationResult: FormValidationResult) => {
      const firstFieldCode = Object.keys(validationResult.fieldErrors)[0];
      const toastMessage = resolveValidationToastMessage(validationResult);
      if (toastMessage) showErrorToast(toastMessage);
      // §4: scroll to + focus the first invalid field on submit (mixed-timing
      // validation — field-level errors render, and the page jumps to the first one).
      if (firstFieldCode && typeof document !== 'undefined') {
        requestAnimationFrame(() => scrollToFormField(firstFieldCode));
      }
    },
    [showErrorToast],
  );
  return { validateFormBeforeSubmit, notifyValidationFailure };
}
