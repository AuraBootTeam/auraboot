/**
 * FormRef 组件
 *
 * 支持独立表单的引用和嵌入
 */

import React, { useState, useEffect, useCallback, useRef } from 'react';
import { useStateContext } from '~/plugins/core-designer/components/studio/workbench/providers/StateProvider';
import { FormRefManagerImpl } from '~/plugins/core-designer/components/studio/workbench/components/FormRef/FormRefManager';
import { FormFieldRenderer } from '~/plugins/core-designer/components/studio/workbench/components/FormRef/FormFieldRenderer';
import { globalActionScheduler } from '~/plugins/core-designer/components/studio/services/runtime/execution/ActionScheduler';
import { ActionUtils } from '~/plugins/core-designer/components/studio/services/runtime/execution';
import { globalEventBus } from '~/plugins/core-designer/components/studio/services/runtime/execution/EventBus';
import type {
  FormRefProps,
  FormRefState,
  CanvasSchema,
  FormRefStatus,
  FormRefConfig,
} from '~/plugins/core-designer/components/studio/workbench/components/FormRef/types';
import type { Action, ActionContext } from '~/plugins/core-designer/components/studio/services/runtime/execution/types';

/**
 * FormRef 组件实现
 */
export const FormRef: React.FC<FormRefProps> = ({
  id,
  className = '',
  style,
  formId,
  mode,
  snapshot,
  value = {},
  onChange,
  onSubmit,
  disabled = false,
  readonly = false,
  loading: externalLoading = false,
  styleOverrides = {},
  fieldOverrides = {},
  layoutOverride,
  validationOverride,
  onFieldChange,
  onValidationChange,
  onLoad,
  onError,
  actionContext,
  onActionExecute,
}) => {
  const { stateManager, getComponentState, setComponentState } = useStateContext();
  const managerRef = useRef<FormRefManagerImpl | null>(null);

  // 组件状态
  const [status, setStatus] = useState<FormRefStatus>('loading');
  const [schema, setSchema] = useState<CanvasSchema | null>(snapshot || null);
  const [formData, setFormData] = useState<Record<string, any>>(value);
  const [errors, setErrors] = useState<Record<string, string[]>>({});
  const [isValid, setIsValid] = useState(true);
  const [isDirty, setIsDirty] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);

  // 初始化管理器
  useEffect(() => {
    if (!managerRef.current) {
      const config: FormRefConfig = {
        api: {
          baseUrl: '/api',
          timeout: 10000,
        },
        cache: {
          enabled: mode === 'pointer',
          ttl: 5 * 60 * 1000,
          maxSize: 100,
        },
        validation: {
          validateOnChange: true,
          validateOnBlur: true,
          showErrorsImmediately: false,
        },
        rendering: {
          fieldRenderers: {},
          defaultFieldRenderer: {
            type: 'text',
            component: FormFieldRenderer as any,
          },
        },
      };

      managerRef.current = new FormRefManagerImpl(config);
    }
  }, [mode]);

  // 加载表单
  useEffect(() => {
    const loadForm = async () => {
      if (!managerRef.current) return;

      try {
        setStatus('loading');

        let formSchema: CanvasSchema;

        if (mode === 'snapshot' && snapshot) {
          // 快照模式，直接使用提供的 schema
          formSchema = snapshot;
        } else {
          // 指针模式，从服务器加载
          formSchema = await managerRef.current.loadForm(formId, mode);
        }

        // 应用覆盖配置
        if (layoutOverride) {
          formSchema.layout = { ...formSchema.layout, ...layoutOverride };
        }

        if (validationOverride) {
          formSchema.validation = validationOverride;
        }

        // 应用字段覆盖
        if (Object.keys(fieldOverrides).length > 0) {
          formSchema.fields = formSchema.fields.map((field) => ({
            ...field,
            ...fieldOverrides[field.name],
          }));
        }

        setSchema(formSchema);
        setStatus('loaded');

        // 初始化表单数据
        if (Object.keys(value).length === 0) {
          const initialData = managerRef.current.resetFormData(formSchema);
          setFormData(initialData);
        }

        onLoad?.(formSchema);

        // 执行表单加载动作
        if (formSchema.actions?.onLoad) {
          await executeActions(formSchema.actions.onLoad, { formSchema });
        }

        // 更新组件状态
        updateComponentState({
          formRef: {
            formId,
            mode,
            status: 'loaded',
            schema: formSchema,
            data: formData,
            errors: {},
            isValid: true,
            isDirty: false,
            isSubmitting: false,
            lastLoadTime: new Date(),
          },
        });
      } catch (error) {
        console.error('加载表单失败:', error);
        setStatus('error');
        onError?.(error as Error);

        updateComponentState({
          formRef: {
            formId,
            mode,
            status: 'error',
            schema: null,
            data: {},
            errors: {},
            isValid: false,
            isDirty: false,
            isSubmitting: false,
          },
        });
      }
    };

    loadForm();
  }, [formId, mode, snapshot]);

  // 同步外部值变化
  useEffect(() => {
    if (JSON.stringify(value) !== JSON.stringify(formData)) {
      setFormData(value);
      setIsDirty(false);
    }
  }, [value]);

  // 更新组件状态
  const updateComponentState = useCallback(
    (updates: Partial<FormRefState>) => {
      const currentState =
        (getComponentState(id) as unknown as FormRefState) || ({} as FormRefState);
      setComponentState(id, {
        ...currentState,
        formRef: {
          ...currentState?.formRef,
          ...(updates.formRef ?? updates),
        },
      });
    },
    [id, getComponentState, setComponentState],
  );

  // 执行动作
  const executeActions = useCallback(
    async (actions: Action[], eventData?: any) => {
      if (!actions || actions.length === 0) return;

      const baseContext = actionContext ?? ActionUtils.createContext();
      const context: ActionContext = {
        ...baseContext,
        componentId: id,
        pageId: baseContext.pageId || '',
        pageState: stateManager.getState(),
        globalState: baseContext.globalState ?? {},
        componentState: getComponentState(id) ?? undefined,
        formData,
        eventData,
        user: baseContext.user ?? undefined,
        env: baseContext.env ?? {},
        utils: baseContext.utils ?? ActionUtils.createContext().utils,
      };

      try {
        for (const action of actions) {
          const result = await globalActionScheduler.executeAction(action, context);
          onActionExecute?.(action, result);

          // 发布事件
          globalEventBus.emit(`formref:action:${action.type}`, {
            formId,
            action,
            result,
            context,
          });
        }
      } catch (error) {
        console.error('动作执行失败:', error);
        onError?.(error as Error);
      }
    },
    [
      actionContext,
      stateManager,
      getComponentState,
      id,
      formData,
      onActionExecute,
      formId,
      onError,
    ],
  );

  // 字段变化处理
  const handleFieldChange = useCallback(
    async (fieldName: string, fieldValue: any) => {
      const newData = { ...formData, [fieldName]: fieldValue };
      setFormData(newData);
      setIsDirty(true);

      // 实时验证
      if (schema && managerRef.current) {
        const validation = managerRef.current.validateForm(schema, newData);
        setErrors(validation.errors);
        setIsValid(validation.isValid);
        onValidationChange?.(validation.isValid, validation.errors);
      }

      onChange?.(newData);
      onFieldChange?.(fieldName, fieldValue);

      // 执行字段变化动作
      if (schema) {
        const field = schema.fields.find((f) => f.name === fieldName);
        if (field?.actions?.onChange) {
          await executeActions(field.actions.onChange, {
            fieldName,
            fieldValue,
            formData: newData,
          });
        }
      }

      // 更新组件状态
      updateComponentState({
        formRef: {
          data: newData,
          errors,
          isValid,
          isDirty: true,
          formId,
          mode,
          status,
          schema,
          isSubmitting,
        },
      });
    },
    [
      formData,
      schema,
      onChange,
      onFieldChange,
      onValidationChange,
      errors,
      isValid,
      updateComponentState,
    ],
  );

  // 表单提交处理
  const handleSubmit = useCallback(
    async (e?: React.FormEvent) => {
      e?.preventDefault();

      if (!schema || !managerRef.current || isSubmitting) {
        return;
      }

      try {
        setIsSubmitting(true);

        // 验证表单
        const validation = managerRef.current.validateForm(schema, formData);
        setErrors(validation.errors);
        setIsValid(validation.isValid);

        if (!validation.isValid) {
          onValidationChange?.(false, validation.errors);
          return;
        }

        // 执行表单提交前动作
        if (schema.actions?.onSubmit) {
          await executeActions(schema.actions.onSubmit, { formData });
        }

        // 提交表单
        await managerRef.current.submitForm(schema, formData);

        onSubmit?.(formData);
        setIsDirty(false);

        // 更新组件状态
        updateComponentState({
          data: formData,
          errors: {},
          isValid: true,
          isDirty: false,
          isSubmitting: false,
          lastSubmitTime: new Date(),
        });
      } catch (error) {
        console.error('表单提交失败:', error);

        // 执行表单错误动作
        if (schema.actions?.onError) {
          await executeActions(schema.actions.onError, { error, formData });
        }

        onError?.(error as Error);
      } finally {
        setIsSubmitting(false);
      }
    },
    [schema, formData, isSubmitting, onSubmit, onValidationChange, onError, updateComponentState],
  );

  // 重置表单
  const handleReset = useCallback(async () => {
    if (!schema || !managerRef.current) {
      return;
    }

    const resetData = managerRef.current.resetFormData(schema);
    setFormData(resetData);
    setErrors({});
    setIsValid(true);
    setIsDirty(false);

    // 执行表单重置动作
    if (schema.actions?.onReset) {
      await executeActions(schema.actions.onReset, { resetData });
    }

    onChange?.(resetData);

    updateComponentState({
      data: resetData,
      errors: {},
      isValid: true,
      isDirty: false,
    });
  }, [schema, onChange, updateComponentState, executeActions]);

  // 渲染加载状态
  const renderLoading = () => (
    <div className="form-ref-loading flex items-center justify-center p-8">
      <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-blue-500"></div>
      <span className="ml-2 text-gray-600">加载表单中...</span>
    </div>
  );

  // 渲染错误状态
  const renderError = () => (
    <div className="form-ref-error rounded-md border border-red-200 bg-red-50 p-4">
      <div className="flex items-center">
        <div className="mr-2 text-red-500">⚠️</div>
        <div>
          <h3 className="font-medium text-red-800">表单加载失败</h3>
          <p className="mt-1 text-sm text-red-600">
            无法加载表单 {formId}，请检查表单配置或网络连接。
          </p>
        </div>
      </div>
    </div>
  );

  // 渲染表单字段
  const renderField = (field: any) => {
    const fieldError = errors[field.name];
    const fieldValue = formData[field.name];

    return (
      <FormFieldRenderer
        key={field.name}
        field={field}
        value={fieldValue}
        error={fieldError}
        disabled={disabled || field.disabled}
        readonly={readonly || field.readonly}
        onChange={(value) => handleFieldChange(field.name, value)}
        styleOverrides={styleOverrides}
      />
    );
  };

  // 渲染表单
  const renderForm = () => {
    if (!schema) {
      return null;
    }

    const containerStyle = {
      ...styleOverrides.container,
      ...style,
    };

    const layoutClass = `form-layout-${schema.layout.type}`;
    const spacingClass = `form-spacing-${schema.layout.spacing}`;

    return (
      <form
        className={`form-ref ${layoutClass} ${spacingClass} ${className}`}
        style={containerStyle}
        onSubmit={handleSubmit}
        noValidate
      >
        {/* 表单标题 */}
        {schema.title && (
          <div className="form-header mb-6">
            <h2 className="text-xl font-semibold text-gray-900">{schema.title}</h2>
            {schema.description && <p className="mt-1 text-gray-600">{schema.description}</p>}
          </div>
        )}

        {/* 表单字段 */}
        <div
          className={`form-fields ${schema.layout.type === 'grid' ? `grid grid-cols-${schema.layout.columns || 1} gap-4` : 'space-y-4'}`}
        >
          {schema.fields.map(renderField)}
        </div>

        {/* 表单操作 */}
        <div className="form-actions mt-6 flex justify-end space-x-3 border-t border-gray-200 pt-4">
          <button
            type="button"
            onClick={handleReset}
            disabled={disabled || !isDirty}
            className="rounded-md bg-gray-100 px-4 py-2 text-gray-700 hover:bg-gray-200 disabled:cursor-not-allowed disabled:opacity-50"
          >
            重置
          </button>
          <button
            type="submit"
            disabled={disabled || !isValid || isSubmitting}
            className="flex items-center rounded-md bg-blue-600 px-4 py-2 text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {isSubmitting && (
              <div className="mr-2 h-4 w-4 animate-spin rounded-full border-b-2 border-white"></div>
            )}
            {isSubmitting ? '提交中...' : '提交'}
          </button>
        </div>
      </form>
    );
  };

  // 主渲染
  if (externalLoading || status === 'loading') {
    return renderLoading();
  }

  if (status === 'error') {
    return renderError();
  }

  return renderForm();
};

// 默认导出
export default FormRef;
