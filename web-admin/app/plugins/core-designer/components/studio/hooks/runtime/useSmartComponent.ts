/**
 * Smart组件通用Hooks
 * 提供Smart组件的通用功能，包括状态管理、验证、数据源、表达式解析等
 */

import { useState, useCallback, useEffect, useMemo } from 'react';
import {
  expressionEvaluator,
  bind as bindExpression,
  evaluateCondition as evaluateDslCondition,
} from '~/framework/meta/runtime/expression/evaluator';
import { createExpressionContext } from '~/framework/meta/runtime/expression/context';
import type { ExpressionContext } from '~/framework/meta/runtime/expression/context';

// 基础类型定义
export interface ValidationRule {
  type: 'required' | 'minLength' | 'maxLength' | 'pattern' | 'email' | 'custom';
  value?: any;
  // Named constraint properties (produced by mergeFieldValidationRules in FormPageContent)
  maxLength?: number;
  minLength?: number;
  pattern?: string;
  message?: string;
  validator?: string;
}

export interface DataSourceConfig {
  type: 'static' | 'api' | 'expression';
  url?: string;
  method?: 'get' | 'post';
  params?: Record<string, any>;
  transform?: string;
  cache?: boolean;
}

export interface OptionItem {
  label: string;
  value: any;
  key?: string;
  disabled?: boolean;
  children?: OptionItem[];
}

// Smart组件状态管理Hook
export interface UseSmartComponentStateProps {
  name: string;
  value?: any;
  defaultValue?: any;
  onChange?: (value: any) => void;
  onBlur?: () => void;
}

export interface UseControllableValueProps<T> {
  value?: T;
  defaultValue?: T;
  onChange?: (value: T) => void;
}

export const useControllableValue = <T>({
  value,
  defaultValue,
  onChange,
}: UseControllableValueProps<T>) => {
  const [internalValue, setInternalValue] = useState<T | undefined>(defaultValue);
  const isControlled = value !== undefined;
  const currentValue = isControlled ? (value as T) : (internalValue as T);

  const setValue = useCallback(
    (nextValue: T) => {
      if (!isControlled) {
        setInternalValue(nextValue);
      }
      onChange?.(nextValue);
    },
    [isControlled, onChange],
  );

  return [currentValue, setValue] as const;
};

export const useSmartComponentState = ({
  name,
  value: propValue,
  defaultValue,
  onChange,
  onBlur,
}: UseSmartComponentStateProps) => {
  const [internalValue, setInternalValue] = useState(propValue ?? defaultValue);
  const [touched, setTouched] = useState(false);

  // 受控组件处理
  const value = propValue !== undefined ? propValue : internalValue;

  const handleChange = useCallback(
    (newValue: any) => {
      if (propValue === undefined) {
        setInternalValue(newValue);
      }
      onChange?.(newValue);
    },
    [propValue, onChange],
  );

  const handleBlur = useCallback(() => {
    setTouched(true);
    onBlur?.();
  }, [onBlur]);

  return {
    value,
    touched,
    handleChange,
    handleBlur,
  };
};

export interface UseSmartFieldProps<T> extends UseSmartComponentStateProps {
  validationRules?: ValidationRule[];
  required?: boolean;
  context?: Record<string, any>;
  value?: T;
  defaultValue?: T;
  onChange?: (value: T) => void;
}

export const useSmartField = <T>(props: UseSmartFieldProps<T>) => {
  const {
    value: propValue,
    defaultValue,
    onChange,
    onBlur,
    validationRules = [],
    required,
    context,
  } = props;
  const [value, setValue] = useControllableValue<T>({
    value: propValue,
    defaultValue,
    onChange,
  });
  const [touched, setTouched] = useState(false);

  const validation = useValidation({
    value,
    rules: validationRules,
    required,
    context,
  });

  const handleChange = useCallback(
    (nextValue: T) => {
      setTouched(true);
      setValue(nextValue);
      validation.validate(nextValue);
    },
    [setValue, validation.validate],
  );

  const handleBlur = useCallback(() => {
    setTouched(true);
    onBlur?.();
    validation.validate(value);
  }, [onBlur, validation.validate, value]);

  const meta = {
    touched,
    error: validation.error ?? undefined,
    warning: undefined,
    validating: false,
  };

  return {
    value,
    setValue: handleChange,
    onBlur: handleBlur,
    touched,
    meta,
    ...validation,
  };
};

// 验证Hook
export interface UseValidationProps {
  value: any;
  rules: ValidationRule[];
  required?: boolean;
  context?: Record<string, any>;
}

export const useValidation = ({ value, rules, required, context = {} }: UseValidationProps) => {
  const [errors, setErrors] = useState<string[]>([]);

  const validate = useCallback(
    async (valueToValidate: any = value) => {
      let validationError: string | null = null;
      const expressionContext = ensureExpressionContext(context);
      (expressionContext as any).value = valueToValidate;
      (expressionContext as any).currentValue = valueToValidate;

      // 必填验证
      if (
        required &&
        (valueToValidate === undefined || valueToValidate === null || valueToValidate === '')
      ) {
        validationError = '此字段为必填项';
      }

      // 规则验证
      if (!validationError && rules.length > 0) {
        for (const rule of rules) {
          let isValid = true;

          switch (rule.type) {
            case 'required':
              isValid =
                valueToValidate !== undefined && valueToValidate !== null && valueToValidate !== '';
              break;
            case 'minLength': {
              const minLen = (rule.value as number) ?? rule.minLength;
              isValid =
                !valueToValidate || minLen == null || String(valueToValidate).length >= minLen;
              break;
            }
            case 'maxLength': {
              const maxLen = (rule.value as number) ?? rule.maxLength;
              isValid =
                !valueToValidate || maxLen == null || String(valueToValidate).length <= maxLen;
              break;
            }
            case 'pattern': {
              const pat = (rule.value as string) ?? rule.pattern;
              isValid =
                !valueToValidate || !pat || new RegExp(pat).test(String(valueToValidate));
              break;
            }
            case 'email':
              isValid =
                !valueToValidate || /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(String(valueToValidate));
              break;
            case 'custom':
              // 自定义验证逻辑
              if (rule.validator) {
                try {
                  const result = evaluateWithContext(rule.validator, expressionContext);
                  isValid = Boolean(result);
                } catch (error) {
                  console.error('Custom validation error:', error);
                  isValid = false;
                }
              }
              break;
          }

          if (!isValid) {
            validationError = rule.message || `验证失败`;
            break;
          }
        }
      }

      setErrors(validationError ? [validationError] : []);
      return validationError === null;
    },
    [value, rules, required, context],
  );

  return {
    error: errors[0] ?? null,
    errors,
    isValid: errors.length === 0,
    validate,
  };
};

// 数据源Hook
export interface UseDataSourceProps {
  staticOptions?: OptionItem[];
  dataSource?: DataSourceConfig;
  context?: Record<string, any>;
}

export const useDataSource = ({
  staticOptions = [],
  dataSource,
  context = {},
}: UseDataSourceProps) => {
  const [options, setOptions] = useState<OptionItem[]>(staticOptions);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchData = useCallback(async () => {
    if (!dataSource || dataSource.type === 'static') {
      setOptions(staticOptions);
      return;
    }

    if (dataSource.type === 'api' && dataSource.url) {
      setLoading(true);
      setError(null);

      try {
        const response = await fetch(dataSource.url, {
          method: dataSource.method || 'get',
          headers: {
            'Content-Type': 'application/json',
          },
          body: dataSource.method === 'post' ? JSON.stringify(dataSource.params || {}) : undefined,
        });

        if (!response.ok) {
          throw new Error(`HTTP error! status: ${response.status}`);
        }

        let data = await response.json();

        // 数据转换
        if (dataSource.transform) {
          try {
            const evalContext = ensureExpressionContext(context);
            (evalContext as any).data = data;
            const transformed = evaluateWithContext(dataSource.transform, evalContext);
            if (transformed !== undefined) {
              data = transformed;
            }
          } catch (error) {
            console.error('Data transform error:', error);
          }
        }

        setOptions(Array.isArray(data) ? data : []);
      } catch (err) {
        setError(err instanceof Error ? err.message : '数据加载失败');
        setOptions([]);
      } finally {
        setLoading(false);
      }
    }
  }, [dataSource, staticOptions, context]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  return {
    options,
    loading,
    error,
    refetch: fetchData,
  };
};

// 表达式值Hook
export const useExpressionValue = (expression: any, context: Record<string, any> = {}) => {
  return useMemo(() => {
    if (typeof expression !== 'string') {
      return expression;
    }

    const exprContext = ensureExpressionContext(context);

    if (expression.startsWith('{{') && expression.endsWith('}}')) {
      try {
        const bound = bindExpression(expression, exprContext);
        if (bound && typeof bound === 'object' && 'value' in bound) {
          return bound.value;
        }
        return bound;
      } catch (error) {
        console.error('Expression evaluation error:', error);
        return expression;
      }
    }

    if (expression.includes('${')) {
      return expressionEvaluator.evaluate(expression, exprContext);
    }

    return expression;
  }, [expression, context]);
};

// 条件渲染Hook
export const useConditionalRender = (condition: any, context: Record<string, any> = {}) => {
  return useMemo(() => {
    if (condition === undefined || condition === null) {
      return true;
    }

    if (typeof condition === 'boolean') {
      return condition;
    }

    if (typeof condition === 'string') {
      const exprContext = ensureExpressionContext(context);
      if (condition.startsWith('{{') && condition.endsWith('}}')) {
        try {
          const bound = bindExpression(condition, exprContext);
          if (bound && typeof bound === 'object' && 'value' in bound) {
            return Boolean(bound.value);
          }
          return Boolean(bound);
        } catch (error) {
          console.error('Condition evaluation error:', error);
          return true;
        }
      }

      const normalized = condition.trim().startsWith('${') ? condition : `\${${condition.trim()}}`;
      return Boolean(evaluateDslCondition(normalized, exprContext));
    }

    return Boolean(condition);
  }, [condition, context]);
};

// 表单字段Hook
export interface UseFormFieldProps extends UseSmartComponentStateProps {
  validationRules?: ValidationRule[];
  required?: boolean;
  context?: Record<string, any>;
}

export const useFormField = (props: UseFormFieldProps) => {
  const state = useSmartComponentState(props);
  const validation = useValidation({
    value: state.value,
    rules: props.validationRules || [],
    required: props.required,
    context: props.context,
  });

  const handleChange = useCallback(
    (value: any) => {
      state.handleChange(value);
      validation.validate(value);
    },
    [state.handleChange, validation.validate],
  );

  return {
    ...state,
    ...validation,
    handleChange,
  };
};

function ensureExpressionContext(context: Record<string, any>): ExpressionContext {
  if (context && (context as ExpressionContext).global !== undefined) {
    return context as ExpressionContext;
  }
  return createExpressionContext(context as Partial<ExpressionContext>);
}

function evaluateWithContext(expression: string, context: ExpressionContext) {
  const trimmed = expression.trim();
  const finalExpression =
    trimmed.startsWith('${') || trimmed.startsWith('{{') ? trimmed : `\${${trimmed}}`;
  if (finalExpression.startsWith('{{')) {
    const bound = bindExpression(finalExpression, context);
    if (bound && typeof bound === 'object' && 'value' in bound) {
      return bound.value;
    }
    return bound;
  }
  return expressionEvaluator.evaluate(finalExpression, context);
}
