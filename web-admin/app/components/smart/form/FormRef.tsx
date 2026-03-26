/**
 * FormRef 组件
 * 支持表单引用的智能组件，可以嵌入独立的表单
 */

import React, { useState, useEffect, useCallback, useMemo } from 'react';
import type {
  FormRefComponentProps,
  FormRef as FormRefSchema,
  FormRefState,
  FormRefEvent,
} from '~/studio/domain/schema/formref';
import { useFormRefManager } from '~/studio/hooks/forms/useFormRefManager';
import { Form } from '~/components/smart/layout/Form';

interface OverlayProps {
  open: boolean;
  variant: 'modal' | 'drawer' | 'page';
  title?: string;
  width?: number | string;
  closable?: boolean;
  maskClosable?: boolean;
  onClose: () => void;
  children: React.ReactNode;
}

const OverlayContainer: React.FC<OverlayProps> = ({
  open,
  variant,
  title,
  width,
  closable = true,
  maskClosable = true,
  onClose,
  children,
}) => {
  if (!open) return null;

  const measuredWidth =
    typeof width === 'number' ? `${width}px` : width || (variant === 'drawer' ? '420px' : '640px');

  const containerAlignment =
    variant === 'drawer' ? 'items-stretch justify-end' : 'items-center justify-center';

  const panelStyle: React.CSSProperties = {
    width: variant === 'page' ? '90vw' : measuredWidth,
    height: variant === 'drawer' ? '100%' : variant === 'page' ? '90vh' : undefined,
    maxHeight: variant === 'modal' ? '90vh' : undefined,
    borderRadius: variant === 'drawer' ? '0' : '0.75rem',
    backgroundColor: '#fff',
    display: 'flex',
    flexDirection: 'column',
    boxShadow: '0 20px 40px rgba(15, 23, 42, 0.2)',
  };

  return (
    <div className={`fixed inset-0 z-50 flex ${containerAlignment}`}>
      <div
        className="absolute inset-0 bg-black/40"
        aria-hidden="true"
        onClick={maskClosable ? onClose : undefined}
      />
      <div className="relative m-4 flex-1" style={panelStyle}>
        {(title || closable) && (
          <div className="flex items-center justify-between border-b px-4 py-3">
            <h3 className="text-lg font-medium text-gray-900">{title}</h3>
            {closable && (
              <button
                type="button"
                aria-label="close modal"
                className="rounded-full p-2 text-gray-500 hover:bg-gray-100 hover:text-gray-700 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                onClick={onClose}
              >
                ×
              </button>
            )}
          </div>
        )}
        <div className="flex-1 overflow-auto px-4 py-4">{children}</div>
      </div>
    </div>
  );
};

const LoadingIndicator = () => (
  <div className="flex h-32 flex-col items-center justify-center text-gray-500">
    <span className="mb-2 inline-block h-4 w-4 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
    加载中...
  </div>
);

const PlaceholderIcon = () => (
  <div className="text-4xl" aria-hidden="true">
    📝
  </div>
);

export interface FormRefProps extends FormRefComponentProps {
  // 设计器特有属性
  placeholder?: string;
  showTitle?: boolean;
  showBorder?: boolean;
  padding?: number;
  className?: string;
  style?: React.CSSProperties;
}

export const FormRef: React.FC<FormRefProps> = ({
  formRefId,
  formRef: externalFormRef,
  mode = 'embedded',
  title,
  width,
  height,
  closable = true,
  maskClosable = true,
  data = {},
  placeholder = '请选择表单',
  showTitle = true,
  showBorder = true,
  padding = 16,
  className,
  style,
  onSubmit,
  onCancel,
  onValuesChange,
  onFieldsChange,
}) => {
  const formRefManager = useFormRefManager();
  const [formRefState, setFormRefState] = useState<FormRefState>({
    formRef: null,
    formData: {},
    errors: {},
    touched: {},
    loading: false,
    submitting: false,
    dirty: false,
    valid: true,
  });
  const [visible, setVisible] = useState(false);

  // 加载表单引用
  useEffect(() => {
    if (externalFormRef) {
      setFormRefState((prev) => ({
        ...prev,
        formRef: externalFormRef,
        formData: { ...externalFormRef.schema.data?.initialValues, ...data },
      }));
    } else if (formRefId) {
      loadFormRef();
    }
  }, [formRefId, externalFormRef, data]);

  const loadFormRef = useCallback(async () => {
    if (!formRefId) return;

    setFormRefState((prev) => ({ ...prev, loading: true }));
    try {
      const formRef = await formRefManager.getFormRef(formRefId);
      if (formRef) {
        setFormRefState((prev) => ({
          ...prev,
          formRef,
          formData: { ...formRef.schema.data?.initialValues, ...data },
          loading: false,
        }));
      } else {
        setFormRefState((prev) => ({
          ...prev,
          loading: false,
        }));
      }
    } catch (error) {
      console.error('Failed to load form ref:', error);
      setFormRefState((prev) => ({
        ...prev,
        loading: false,
      }));
    }
  }, [formRefId, formRefManager, data]);

  // 处理表单事件
  const handleFormEvent = useCallback(
    (event: FormRefEvent) => {
      switch (event.type) {
        case 'field_change':
          setFormRefState((prev) => ({
            ...prev,
            formData: {
              ...prev.formData,
              [event.payload.name]: event.payload.value,
            },
            dirty: true,
            touched: {
              ...prev.touched,
              [event.payload.name]: true,
            },
          }));
          onValuesChange?.(
            { [event.payload.name]: event.payload.value },
            { ...formRefState.formData, [event.payload.name]: event.payload.value },
          );
          break;

        case 'form_submit':
          handleSubmit(event.payload);
          break;

        case 'form_reset':
          handleReset();
          break;

        case 'form_error':
          setFormRefState((prev) => ({
            ...prev,
            errors: event.payload.errors,
            valid: Object.keys(event.payload.errors).length === 0,
          }));
          break;
      }
    },
    [formRefState.formData, onValuesChange],
  );

  // 处理表单提交
  const handleSubmit = useCallback(
    async (formData: Record<string, any>) => {
      setFormRefState((prev) => ({ ...prev, submitting: true }));
      try {
        await onSubmit?.(formData);
        if (mode !== 'embedded') {
          setVisible(false);
        }
      } catch (error) {
        console.error('Form submission failed:', error);
      } finally {
        setFormRefState((prev) => ({ ...prev, submitting: false }));
      }
    },
    [onSubmit, mode],
  );

  // 处理表单重置
  const handleReset = useCallback(() => {
    const initialValues = formRefState.formRef?.schema.data?.initialValues || {};
    setFormRefState((prev) => ({
      ...prev,
      formData: { ...initialValues, ...data },
      errors: {},
      touched: {},
      dirty: false,
      valid: true,
    }));
  }, [formRefState.formRef, data]);

  // 处理取消
  const handleCancel = useCallback(() => {
    setVisible(false);
    onCancel?.();
  }, [onCancel]);

  // 渲染表单内容
  const renderFormContent = useMemo(() => {
    if (formRefState.loading) {
      return <LoadingIndicator />;
    }

    if (!formRefState.formRef) {
      return (
        <div className="flex flex-col items-center justify-center p-8 text-gray-500">
          <PlaceholderIcon />
          <div>{placeholder}</div>
        </div>
      );
    }

    const smartFormSchema = {
      id: formRefState.formRef?.id ?? formRefId,
      ...formRefState.formRef.schema,
    } as any;

    return (
      <Form
        schema={smartFormSchema}
        data={formRefState.formData}
        onFieldChange={(name: string, value: any) =>
          handleFormEvent({
            type: 'field_change',
            payload: { name, value },
          })
        }
        onSubmit={(data) =>
          handleFormEvent({
            type: 'form_submit',
            payload: data,
          })
        }
      />
    );
  }, [formRefState, placeholder, handleFormEvent]);

  // 嵌入模式
  if (mode === 'embedded') {
    const cardTitle = showTitle && (title || formRefState.formRef?.title);

    const containerStyle: React.CSSProperties = {
      padding,
      height,
    };

    const borderClass = showBorder ? 'rounded-xl border border-gray-200 bg-white shadow-sm' : '';

    return (
      <div className={className} style={style}>
        <div className={borderClass} style={containerStyle}>
          {cardTitle && <div className="mb-4 text-lg font-medium text-gray-900">{cardTitle}</div>}
          {renderFormContent}
        </div>
      </div>
    );
  }

  // 触发按钮（用于模态框和抽屉模式）
  const triggerButton = (
    <button
      type="button"
      onClick={() => setVisible(true)}
      className={`inline-flex items-center rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white shadow-sm transition hover:bg-blue-700 focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 focus:outline-none ${className || ''}`}
      style={style}
    >
      <span className="mr-2" aria-hidden="true">
        📝
      </span>
      {title || formRefState.formRef?.title || '打开表单'}
    </button>
  );

  // 模态框模式
  if (mode === 'modal') {
    return (
      <>
        {triggerButton}
        <OverlayContainer
          open={visible}
          variant="modal"
          title={title || formRefState.formRef?.title}
          width={width}
          closable={closable}
          maskClosable={maskClosable}
          onClose={handleCancel}
        >
          {renderFormContent}
        </OverlayContainer>
      </>
    );
  }

  // 抽屉模式
  if (mode === 'drawer') {
    return (
      <>
        {triggerButton}
        <OverlayContainer
          open={visible}
          variant="drawer"
          title={title || formRefState.formRef?.title}
          width={width}
          closable={closable}
          maskClosable={maskClosable}
          onClose={handleCancel}
        >
          {renderFormContent}
        </OverlayContainer>
      </>
    );
  }

  // 页面模式（全屏）
  if (mode === 'page') {
    return (
      <>
        {triggerButton}
        <OverlayContainer
          open={visible}
          variant="page"
          title={title || formRefState.formRef?.title}
          width="100%"
          closable={closable}
          maskClosable={maskClosable}
          onClose={handleCancel}
        >
          <div style={{ padding, height: '100%', overflow: 'auto' }}>{renderFormContent}</div>
        </OverlayContainer>
      </>
    );
  }

  return null;
};

// 设计器中的预览组件
export const FormRefPreview: React.FC<FormRefProps> = (props) => {
  return (
    <div className="flex min-h-[200px] flex-col items-center justify-center rounded-lg border-2 border-dashed border-gray-300 p-4">
      <PlaceholderIcon />
      <div className="text-center text-gray-500">
        <div className="font-medium">表单引用组件</div>
        {props.formRefId && <div className="mt-1 text-sm">ID: {props.formRefId}</div>}
        <div className="mt-1 text-sm">模式: {props.mode || 'embedded'}</div>
      </div>
    </div>
  );
};

export default FormRef;
