import { useCallback } from 'react';
import type { ColumnConfig } from '~/framework/meta/schemas/types';
import { evaluateValidationRule } from './subTableValues';
import type { ResolvedSubTableViewerProps } from './subTableTypes';

type UseSubTableValidationArgs = Pick<
  ResolvedSubTableViewerProps,
  't' | 'config' | 'parentRecordData' | 'locale'
>;

export function useSubTableValidation({ t, config, parentRecordData, locale }: UseSubTableValidationArgs) {
  // ------- Field Validation -------
  const validateFieldValue = useCallback(
    (col: ColumnConfig, value: any): string | null => {
      // Required
      if (col.required && (value === undefined || value === null || value === '')) {
        const msg = t('common.validation.required');
        return msg !== 'common.validation.required' ? msg : locale.startsWith('zh') ? '此项为必填项' : 'This field is required';
      }

      if (value === undefined || value === null || value === '') return null;

      // Numeric min/max
      const numVal = typeof value === 'number' ? value : Number(value);
      if (!isNaN(numVal)) {
        if (col.min !== undefined && numVal < col.min) {
          const msg = t('validation.min');
          return msg !== 'validation.min' ? `${msg}: ${col.min}` : `Minimum value: ${col.min}`;
        }
        if (col.max !== undefined && numVal > col.max) {
          const msg = t('validation.max');
          return msg !== 'validation.max' ? `${msg}: ${col.max}` : `Maximum value: ${col.max}`;
        }
      }

      // Pattern
      if (col.pattern && typeof value === 'string') {
        if (!new RegExp(col.pattern).test(value)) {
          const msg = t('validation.pattern');
          return msg !== 'validation.pattern' ? msg : 'Invalid format';
        }
      }

      // Custom validation rules
      if (col.validation) {
        for (const rule of col.validation) {
          const ruleError = evaluateValidationRule(rule, value, t);
          if (ruleError) return ruleError;
        }
      }

      return null;
    },
    [t, locale],
  );

  // ------- Cross-Row Validation -------
  const validateCrossRowRules = useCallback(
    (allRows: Record<string, any>[]): string[] => {
      if (!config.crossRowRules || config.crossRowRules.length === 0) return [];

      const errors: string[] = [];
      for (const rule of config.crossRowRules) {
        const values = allRows.map((r) => Number(r[rule.field]) || 0);
        let aggregated = 0;
        switch (rule.aggregation) {
          case 'sum':
            aggregated = values.reduce((a, b) => a + b, 0);
            break;
          case 'avg':
            aggregated = values.length > 0 ? values.reduce((a, b) => a + b, 0) / values.length : 0;
            break;
          case 'count':
            aggregated = values.length;
            break;
          case 'min':
            aggregated = values.length > 0 ? Math.min(...values) : 0;
            break;
          case 'max':
            aggregated = values.length > 0 ? Math.max(...values) : 0;
            break;
        }

        let threshold: number;
        const recordFieldMatch =
          typeof rule.value === 'string'
            ? rule.value.match(/^\$\{record\.([A-Za-z0-9_.-]+)\}$/)
            : null;
        if (recordFieldMatch) {
          const fieldName = recordFieldMatch[1];
          threshold = Number(parentRecordData?.[fieldName]) || 0;
        } else {
          threshold = Number(rule.value) || 0;
        }

        let violated = false;
        switch (rule.operator) {
          case 'lte':
            violated = aggregated > threshold;
            break;
          case 'gte':
            violated = aggregated < threshold;
            break;
          case 'LT':
            violated = aggregated >= threshold;
            break;
          case 'GT':
            violated = aggregated <= threshold;
            break;
          case 'EQ':
            violated = aggregated !== threshold;
            break;
        }

        if (violated) {
          const msg =
            typeof rule.message === 'string'
              ? t(rule.message) !== rule.message
                ? t(rule.message)
                : rule.message
              : (rule.message as any)?.['zh-CN'] || (rule.message as any)?.['en-US'] || '';
          errors.push(msg);
        }
      }
      return errors;
    },
    [config.crossRowRules, parentRecordData, t],
  );
  return { validateFieldValue, validateCrossRowRules };
}
