/**
 * FormRef 选择器编辑器
 * 用于在属性面板中选择表单引用
 */

import React, { useState, useEffect, useMemo } from 'react';
import { DocumentTextIcon, EyeIcon } from '@heroicons/react/24/outline';
import Button from '~/components/smart/interaction/Button';
import { Modal, Spin, Tag } from '~/components/smart/ui';
import { useFormRefManager } from '~/plugins/core-designer/components/studio/hooks/forms/useFormRefManager';
import type { FormRef } from '~/plugins/core-designer/components/studio/domain/schema/formref';

export interface FormRefSelectEditorProps {
  value?: string;
  onChange?: (value: string) => void;
  placeholder?: string;
  disabled?: boolean;
  allowClear?: boolean;
}

export const FormRefSelectEditor: React.FC<FormRefSelectEditorProps> = ({
  value,
  onChange,
  placeholder = '请选择表单',
  disabled = false,
  allowClear = true,
}) => {
  const { getAllFormRefs, loading, error } = useFormRefManager();
  const [formRefs, setFormRefs] = useState<FormRef[]>([]);
  const [modalVisible, setModalVisible] = useState(false);
  const [selectedFormRef, setSelectedFormRef] = useState<FormRef | null>(null);
  const [searchKeyword, setSearchKeyword] = useState('');

  // 加载表单引用列表
  useEffect(() => {
    loadFormRefs();
  }, []);

  const loadFormRefs = async () => {
    const refs = await getAllFormRefs();
    if (refs) {
      setFormRefs(refs);
    }
  };

  // 过滤表单引用
  const filteredFormRefs = useMemo(() => {
    if (!searchKeyword) return formRefs;

    return formRefs.filter(
      (formRef) =>
        formRef.name.toLowerCase().includes(searchKeyword.toLowerCase()) ||
        formRef.title.toLowerCase().includes(searchKeyword.toLowerCase()) ||
        (formRef.description &&
          formRef.description.toLowerCase().includes(searchKeyword.toLowerCase())),
    );
  }, [formRefs, searchKeyword]);

  // 选择表单引用
  const handleSelect = (formRefId: string) => {
    onChange?.(formRefId);
  };

  // 查看表单详情
  const handleViewFormRef = (formRef: FormRef) => {
    setSelectedFormRef(formRef);
    setModalVisible(true);
  };

  // 渲染表单引用选项
  const renderFormRefOption = (formRef: FormRef) => (
    <div
      key={formRef.id}
      className="flex items-center justify-between rounded p-2 hover:bg-gray-50"
    >
      <div className="flex-1">
        <div className="font-medium">{formRef.title}</div>
        <div className="text-sm text-gray-500">{formRef.name}</div>
        {formRef.description && (
          <div className="mt-1 text-xs text-gray-400">{formRef.description}</div>
        )}
        <div className="mt-1 flex items-center gap-2">
          <Tag size="small" color="blue">
            v{formRef.version}
          </Tag>
          <Tag size="small">{formRef.schema.fields.length} 字段</Tag>
        </div>
      </div>
      <div className="ml-2 flex items-center gap-1">
        <Button
          type="button"
          variant="ghost"
          className="p-1"
          onClick={(e: React.MouseEvent) => {
            e.stopPropagation();
            handleViewFormRef(formRef);
          }}
        >
          <EyeIcon className="h-4 w-4" />
        </Button>
      </div>
    </div>
  );

  // 渲染表单详情
  const renderFormRefDetail = () => {
    if (!selectedFormRef) return null;

    return (
      <div className="space-y-4">
        <div>
          <h4 className="mb-2 font-medium">基本信息</h4>
          <div className="grid grid-cols-2 gap-4 text-sm">
            <div>
              <span className="text-gray-500">名称：</span>
              <span>{selectedFormRef.name}</span>
            </div>
            <div>
              <span className="text-gray-500">标题：</span>
              <span>{selectedFormRef.title}</span>
            </div>
            <div>
              <span className="text-gray-500">版本：</span>
              <span>v{selectedFormRef.version}</span>
            </div>
            <div>
              <span className="text-gray-500">字段数：</span>
              <span>{selectedFormRef.schema.fields.length}</span>
            </div>
          </div>
          {selectedFormRef.description && (
            <div className="mt-2">
              <span className="text-gray-500">描述：</span>
              <span>{selectedFormRef.description}</span>
            </div>
          )}
        </div>

        <div>
          <h4 className="mb-2 font-medium">表单字段</h4>
          <div className="max-h-60 space-y-2 overflow-y-auto">
            {selectedFormRef.schema.fields.map((field, index) => (
              <div key={index} className="rounded border p-2 text-sm">
                <div className="flex items-center justify-between">
                  <div className="font-medium">{field.label || field.name}</div>
                  <div className="flex items-center gap-2">
                    <Tag size="small">{field.type}</Tag>
                    {field.required && (
                      <Tag size="small" color="red">
                        必填
                      </Tag>
                    )}
                  </div>
                </div>
                <div className="mt-1 text-gray-500">字段名: {field.name}</div>
                {field.placeholder && (
                  <div className="mt-1 text-xs text-gray-400">占位符: {field.placeholder}</div>
                )}
              </div>
            ))}
          </div>
        </div>

        <div>
          <h4 className="mb-2 font-medium">表单布局</h4>
          <div className="text-sm">
            <div>布局类型: {selectedFormRef.schema.layout.type}</div>
            {selectedFormRef.schema.layout.columns && (
              <div>列数: {selectedFormRef.schema.layout.columns}</div>
            )}
            {selectedFormRef.schema.layout.gap && (
              <div>间距: {selectedFormRef.schema.layout.gap}px</div>
            )}
          </div>
        </div>

        <div>
          <h4 className="mb-2 font-medium">表单动作</h4>
          <div className="flex flex-wrap gap-2">
            {selectedFormRef.schema.actions.map((action, index) => (
              <Tag key={index} color={action.variant === 'primary' ? 'blue' : 'default'}>
                {action.label} ({action.type})
              </Tag>
            ))}
          </div>
        </div>
      </div>
    );
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center p-4">
        <Spin spinning={true} size="small" />
      </div>
    );
  }

  if (error) {
    return <div className="p-2 text-sm text-red-500">加载表单列表失败: {error}</div>;
  }

  return (
    <>
      <div className="relative">
        <select
          value={value || ''}
          onChange={(e) => handleSelect(e.target.value)}
          disabled={disabled}
          className="w-full rounded-lg border border-gray-300 px-3 py-2 focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none disabled:cursor-not-allowed disabled:opacity-50"
        >
          <option value="">{placeholder}</option>
          {filteredFormRefs.map((formRef) => (
            <option key={formRef.id} value={formRef.id}>
              {formRef.title}
            </option>
          ))}
        </select>
        {allowClear && value && (
          <button
            type="button"
            onClick={() => handleSelect('')}
            className="absolute top-1/2 right-8 -translate-y-1/2 transform text-gray-400 hover:text-gray-600"
          >
            ×
          </button>
        )}
      </div>

      <Modal
        title={
          <div className="flex items-center gap-2">
            <DocumentTextIcon className="h-5 w-5" />
            <span>表单详情</span>
          </div>
        }
        open={modalVisible}
        onCancel={() => setModalVisible(false)}
        footer={
          <div className="flex justify-end gap-2">
            <Button type="button" variant="ghost" onClick={() => setModalVisible(false)}>
              关闭
            </Button>
            {selectedFormRef && (
              <Button
                type="button"
                variant="primary"
                onClick={() => {
                  handleSelect(selectedFormRef.id);
                  setModalVisible(false);
                }}
              >
                选择此表单
              </Button>
            )}
          </div>
        }
      >
        {renderFormRefDetail()}
      </Modal>
    </>
  );
};

export default FormRefSelectEditor;
